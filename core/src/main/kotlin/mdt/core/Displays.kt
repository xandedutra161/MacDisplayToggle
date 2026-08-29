package mdt.core

import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import mdt.core.ffi.DisplayReconfigurationCallback
import mdt.core.ffi.NativeApis
import mdt.core.ffi.PLACEHOLDER_MODEL
import mdt.core.ffi.PLACEHOLDER_VENDOR
import mdt.core.ffi.cgErrorName
import mdt.core.ffi.kCFStringEncodingUTF8
import java.time.OffsetDateTime
import java.util.Locale

class DisplayError(message: String) : RuntimeException(message)

/** Modelo de domínio da Fase 1 (PLANO §4): id, uuid, nome, builtin, ativo/desabilitado. */
data class DisplayInfo(
    val id: Int,
    val uuid: String?,
    val vendor: Int,
    val model: Int,
    val serial: Int,
    val builtin: Boolean,
    val active: Boolean,
    val online: Boolean,
    val inSls: Boolean,
) {
    val isPlaceholder: Boolean get() = vendor == PLACEHOLDER_VENDOR && model == PLACEHOLDER_MODEL
    val isDisabled: Boolean get() = inSls && !online
    val isActiveReal: Boolean get() = active && !isPlaceholder

    // Fase 1: vendor/model como nome (PLANO §3); nome amigável via NSScreen fica p/ Fase 2
    val name: String
        get() = when {
            isPlaceholder -> "Placeholder do macOS"
            builtin -> "Display embutido"
            else -> "Display %04X:%04X".format(Locale.ROOT, vendor, model)
        }

    fun toSaved(): SavedDisplay =
        SavedDisplay(id, uuid, vendor, model, serial, builtin, savedAt = OffsetDateTime.now().withNano(0).toString())
}

object Displays {

    fun onlineIds(): List<Int> = cgList("CGGetOnlineDisplayList") { max, arr, count ->
        NativeApis.cg.CGGetOnlineDisplayList(max, arr, count)
    }

    fun activeIds(): List<Int> = cgList("CGGetActiveDisplayList") { max, arr, count ->
        NativeApis.cg.CGGetActiveDisplayList(max, arr, count)
    }

    /** Lista privada do SkyLight — inclui displays desabilitados (PLANO §2.2). */
    fun slsIds(): List<Int> {
        val countRef = IntByReference()
        check0(NativeApis.slsGetDisplayList.invokeInt(arrayOf<Any?>(0, null, countRef)), "SLSGetDisplayList(contagem)")
        val n = countRef.value
        if (n <= 0) return emptyList()
        val ids = IntArray(n)
        check0(NativeApis.slsGetDisplayList.invokeInt(arrayOf<Any?>(n, ids, countRef)), "SLSGetDisplayList")
        return ids.take(countRef.value)
    }

    private inline fun cgList(what: String, call: (Int, IntArray?, IntByReference) -> Int): List<Int> {
        val countRef = IntByReference()
        check0(call(0, null, countRef), "$what(contagem)")
        val n = countRef.value
        if (n <= 0) return emptyList()
        val ids = IntArray(n)
        check0(call(n, ids, countRef), what)
        return ids.take(countRef.value)
    }

    /**
     * UUID como string maiúscula, ou null. Descoberta da Fase 0: para display
     * DESABILITADO isto retorna null no Tahoe 26.5.2 — a re-resolução efetiva de
     * religamento é o ID persistido (e serial como plano B).
     */
    fun uuidOf(id: Int): String? {
        val uuidRef = NativeApis.createUuidFromDisplayId.invokePointer(arrayOf<Any?>(id)) ?: return null
        try {
            val strRef = NativeApis.cf.CFUUIDCreateString(null, uuidRef) ?: return null
            try {
                val buf = ByteArray(128)
                if (NativeApis.cf.CFStringGetCString(strRef, buf, buf.size.toLong(), kCFStringEncodingUTF8) == 0.toByte()) return null
                val len = buf.indexOf(0).let { if (it < 0) buf.size else it }
                return String(buf, 0, len, Charsets.UTF_8).uppercase()
            } finally {
                NativeApis.cf.CFRelease(strRef)
            }
        } finally {
            NativeApis.cf.CFRelease(uuidRef)
        }
    }

    fun findByUuidInSls(uuid: String): Int? =
        slsIds().firstOrNull { uuidOf(it)?.equals(uuid, ignoreCase = true) == true }

    /** Plano B de re-resolução (Fase 0, descoberta 1): serial/vendor/model são legíveis mesmo desabilitado. */
    fun findBySerialInSls(vendor: Int, model: Int, serial: Int): Int? {
        if (serial == 0 && vendor == 0) return null
        return slsIds().firstOrNull {
            NativeApis.cg.CGDisplayVendorNumber(it) == vendor &&
                NativeApis.cg.CGDisplayModelNumber(it) == model &&
                NativeApis.cg.CGDisplaySerialNumber(it) == serial
        }
    }

    /** União das listas pública (online/ativa) e SLS — a comparação entre elas revela desabilitados. */
    fun snapshot(): List<DisplayInfo> {
        val online = onlineIds()
        val active = activeIds().toSet()
        val sls = slsIds()
        val onlineSet = online.toSet()
        val slsSet = sls.toSet()
        val ids = LinkedHashSet<Int>().apply { addAll(online); addAll(sls) }
        return ids.map { id ->
            DisplayInfo(
                id = id,
                uuid = uuidOf(id),
                vendor = NativeApis.cg.CGDisplayVendorNumber(id),
                model = NativeApis.cg.CGDisplayModelNumber(id),
                serial = NativeApis.cg.CGDisplaySerialNumber(id),
                builtin = NativeApis.cg.CGDisplayIsBuiltin(id) != 0,
                active = id in active,
                online = id in onlineSet,
                inSls = id in slsSet,
            )
        }
    }

    private fun check0(err: Int, what: String) {
        if (err != 0) throw DisplayError("$what falhou: ${cgErrorName(err)}")
    }
}

/**
 * Frescor das listas (validado na Fase 1): um processo JVM de longa duração SEM
 * callback registrado lê listas CG stale (o processo A da Fase 0 ficou 20+ s cego a
 * um religamento externo). Com `CGDisplayRegisterReconfigurationCallback` chamado —
 * mesmo que o runloop nunca rode e o callback nunca dispare (JVM sem AppKit não tem
 * fontes de runloop) — as enumerações voltam a refletir a realidade em segundos.
 * Chamar [ensure] uma vez antes de qualquer loop longo de polling.
 */
object ListFreshness {
    private var cb: DisplayReconfigurationCallback? = null // referência forte (GC do JNA)

    @Synchronized
    fun ensure() {
        if (cb != null) return
        val c = object : DisplayReconfigurationCallback {
            override fun invoke(display: Int, flags: Int, userInfo: Pointer?) {}
        }
        if (NativeApis.cg.CGDisplayRegisterReconfigurationCallback(c, null) == 0) cb = c
    }
}
