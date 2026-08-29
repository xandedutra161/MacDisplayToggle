package mdt.cli

import com.sun.jna.ptr.IntByReference
import mdt.ffi.NativeApis
import mdt.ffi.PLACEHOLDER_MODEL
import mdt.ffi.PLACEHOLDER_VENDOR
import mdt.ffi.cgErrorName
import mdt.ffi.kCFStringEncodingUTF8

class PocError(message: String) : RuntimeException(message)

data class DisplayState(
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

    /** UUID como string maiúscula, ou null se o sistema não tiver UUID para esse ID. */
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

    /** União das listas pública (online/ativa) e SLS — a comparação entre elas é o que revela desabilitados. */
    fun snapshot(): List<DisplayState> {
        val online = onlineIds()
        val active = activeIds().toSet()
        val sls = slsIds()
        val onlineSet = online.toSet()
        val slsSet = sls.toSet()
        val ids = LinkedHashSet<Int>().apply { addAll(online); addAll(sls) }
        return ids.map { id ->
            DisplayState(
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
        if (err != 0) throw PocError("$what falhou: ${cgErrorName(err)}")
    }
}
