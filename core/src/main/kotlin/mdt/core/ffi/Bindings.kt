package mdt.core.ffi

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

// CGConfigureOption — Fase 0 fixou kCGConfigurePermanently como default (OBSERVACOES §a)
const val kCGConfigureForSession = 1
const val kCGConfigurePermanently = 2

const val kCFStringEncodingUTF8 = 0x08000100

// Display placeholder criado pelo macOS quando o último display real some (PLANO §2.3 item 4)
const val PLACEHOLDER_VENDOR = 0x756E6B6E // 'unkn'
const val PLACEHOLDER_MODEL = 0x76697274  // 'virt'

// CGDisplayChangeSummaryFlags — para logar eventos do watcher (Fase 1)
const val kCGDisplayBeginConfigurationFlag = 0x1
const val kCGDisplayMovedFlag = 0x2
const val kCGDisplaySetMainFlag = 0x4
const val kCGDisplaySetModeFlag = 0x8
const val kCGDisplayAddFlag = 0x10
const val kCGDisplayRemoveFlag = 0x20
const val kCGDisplayMirrorFlag = 0x40
const val kCGDisplayUnMirrorFlag = 0x80
const val kCGDisplayEnabledFlag = 0x100
const val kCGDisplayDisabledFlag = 0x200
const val kCGDisplayDesktopShapeChangedFlag = 0x1000

fun reconfigFlagNames(flags: Int): String {
    if (flags == 0) return "0x0"
    val names = buildList {
        if (flags and kCGDisplayBeginConfigurationFlag != 0) add("Begin")
        if (flags and kCGDisplayMovedFlag != 0) add("Moved")
        if (flags and kCGDisplaySetMainFlag != 0) add("SetMain")
        if (flags and kCGDisplaySetModeFlag != 0) add("SetMode")
        if (flags and kCGDisplayAddFlag != 0) add("Add")
        if (flags and kCGDisplayRemoveFlag != 0) add("Remove")
        if (flags and kCGDisplayMirrorFlag != 0) add("Mirror")
        if (flags and kCGDisplayUnMirrorFlag != 0) add("Unmirror")
        if (flags and kCGDisplayEnabledFlag != 0) add("Enabled")
        if (flags and kCGDisplayDisabledFlag != 0) add("Disabled")
        if (flags and kCGDisplayDesktopShapeChangedFlag != 0) add("DesktopShapeChanged")
    }
    return "0x${Integer.toHexString(flags)}(${names.joinToString("+")})"
}

/**
 * Callback de reconfiguração de displays (Fase 1). Quem registrar precisa manter
 * referência FORTE ao objeto — se o GC recolher, o JNA invalida o trampoline nativo.
 * Só dispara se a thread registradora tiver um CFRunLoop rodando (PLANO §4/Fase 1).
 */
interface DisplayReconfigurationCallback : Callback {
    fun invoke(display: Int, flags: Int, userInfo: Pointer?)
}

interface CoreGraphics : Library {
    fun CGGetOnlineDisplayList(maxDisplays: Int, onlineDisplays: IntArray?, displayCount: IntByReference): Int
    fun CGGetActiveDisplayList(maxDisplays: Int, activeDisplays: IntArray?, displayCount: IntByReference): Int
    fun CGDisplayIsBuiltin(display: Int): Int
    fun CGDisplayIsActive(display: Int): Int
    fun CGDisplayVendorNumber(display: Int): Int
    fun CGDisplayModelNumber(display: Int): Int
    fun CGDisplaySerialNumber(display: Int): Int
    fun CGBeginDisplayConfiguration(config: PointerByReference): Int
    fun CGCompleteDisplayConfiguration(config: Pointer?, option: Int): Int
    fun CGCancelDisplayConfiguration(config: Pointer?): Int
    fun CGDisplayRegisterReconfigurationCallback(callback: DisplayReconfigurationCallback, userInfo: Pointer?): Int
    fun CGDisplayRemoveReconfigurationCallback(callback: DisplayReconfigurationCallback, userInfo: Pointer?): Int
}

interface CoreFoundation : Library {
    fun CFUUIDCreateString(alloc: Pointer?, uuid: Pointer?): Pointer?

    // Boolean do CF é unsigned char (1 byte) — mapear como Byte e comparar != 0
    fun CFStringGetCString(theString: Pointer?, buffer: ByteArray, bufferSize: Long, encoding: Int): Byte
    fun CFRelease(cf: Pointer?)
    fun CFRunLoopRun()
    fun CFRunLoopGetCurrent(): Pointer?
    fun CFRunLoopStop(rl: Pointer?)
}

/** IOKit — detecção de notebook pela bateria (PLANO §2.3 item 6), nunca pelo built-in nas listas. */
interface IOKit : Library {
    fun IOServiceMatching(name: String): Pointer?

    // mainPort = 0 é o default (kIOMainPortDefault); consome a referência de `matching`
    fun IOServiceGetMatchingService(mainPort: Int, matching: Pointer?): Int
    fun IOObjectRelease(obj: Int): Int
}

object NativeApis {
    // Desde o Big Sur estes arquivos não existem no disco (vivem no dyld shared cache);
    // o dlopen com caminho absoluto funciona mesmo assim — nunca condicionar a carga
    // a uma checagem de existência do arquivo (PLANO §4/Fase 0 item 2).
    private const val CG = "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics"
    private const val CF = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
    private const val SKYLIGHT = "/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight"
    private const val COLORSYNC = "/System/Library/Frameworks/ColorSync.framework/ColorSync"
    private const val IOKIT = "/System/Library/Frameworks/IOKit.framework/IOKit"

    val cg: CoreGraphics = Native.load(CG, CoreGraphics::class.java)
    val cf: CoreFoundation = Native.load(CF, CoreFoundation::class.java)
    val iokit: IOKit = Native.load(IOKIT, IOKit::class.java)

    private val cgLib = NativeLibrary.getInstance(CG)
    private val skyLib = NativeLibrary.getInstance(SKYLIGHT)

    /** Mesma forma da CGGetOnlineDisplayList; ao contrário da pública, enumera também desabilitados (PLANO §2.2). */
    val slsGetDisplayList: Function = resolve(skyLib to "SLSGetDisplayList")

    /** SLS primeiro, fallback CGS (re-export do mesmo símbolo — PLANO §2.1/§3). */
    val configureDisplayEnabled: Function =
        resolve(skyLib to "SLSConfigureDisplayEnabled", cgLib to "CGSConfigureDisplayEnabled")

    /**
     * Fallback ColorSync: no Tahoe 26.5.2 o símbolo NÃO existe no CoreGraphics
     * (verificado na Fase 0 — o fallback é a via real).
     */
    val createUuidFromDisplayId: Function = try {
        cgLib.getFunction("CGDisplayCreateUUIDFromDisplayID")
    } catch (_: UnsatisfiedLinkError) {
        NativeLibrary.getInstance(COLORSYNC).getFunction("CGDisplayCreateUUIDFromDisplayID")
    }

    private fun resolve(vararg candidates: Pair<NativeLibrary, String>): Function {
        for ((lib, name) in candidates) {
            try {
                return lib.getFunction(name)
            } catch (_: UnsatisfiedLinkError) {
            }
        }
        throw UnsatisfiedLinkError("nenhum destes símbolos resolveu: ${candidates.joinToString { it.second }}")
    }
}

fun cgErrorName(err: Int): String = when (err) {
    0 -> "success(0)"
    1000 -> "kCGErrorFailure(1000)"
    1001 -> "kCGErrorIllegalArgument(1001) — em enable redundante significa 'já habilitado' (Fase 0 obs. b); em religamento pós-remoção, hardware ausente (PLANO §2.3 item 4)"
    1002 -> "kCGErrorInvalidConnection(1002)"
    1003 -> "kCGErrorInvalidContext(1003)"
    1004 -> "kCGErrorCannotComplete(1004)"
    1006 -> "kCGErrorNotImplemented(1006)"
    1007 -> "kCGErrorRangeCheck(1007)"
    1008 -> "kCGErrorTypeCheck(1008)"
    1010 -> "kCGErrorInvalidOperation(1010)"
    1011 -> "kCGErrorNoneAvailable(1011)"
    else -> "CGError($err)"
}
