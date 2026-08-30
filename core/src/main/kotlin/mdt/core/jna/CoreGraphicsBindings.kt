package mdt.core.jna

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

// CGConfigureOption — kCGConfigurePermanently fixado como default na validação em máquina real
const val kCGConfigureForSession = 1
const val kCGConfigurePermanently = 2

const val kCFStringEncodingUTF8 = 0x08000100

// CGDisplayChangeSummaryFlags — para logar eventos do watcher
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
 * Callback de reconfiguração de displays. Quem registrar precisa manter
 * referência FORTE ao objeto — se o GC recolher, o JNA invalida o trampoline nativo.
 * Só dispara se a thread registradora tiver um CFRunLoop rodando.
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

/** IOKit — detecção de notebook pela bateria, nunca pelo built-in nas listas. */
interface IOKit : Library {
    fun IOServiceMatching(name: String): Pointer?

    // mainPort = 0 é o default (kIOMainPortDefault); consome a referência de `matching`
    fun IOServiceGetMatchingService(mainPort: Int, matching: Pointer?): Int
    fun IOObjectRelease(obj: Int): Int
}

fun cgErrorName(err: Int): String = when (err) {
    0 -> "success(0)"
    1000 -> "kCGErrorFailure(1000)"
    1001 -> "kCGErrorIllegalArgument(1001) — em enable redundante significa 'já habilitado'; em religamento pós-remoção, hardware ausente"
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
