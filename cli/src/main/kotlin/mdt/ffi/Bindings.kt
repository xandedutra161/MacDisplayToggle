package mdt.ffi

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

// CGConfigureOption — a Fase 0 testa os dois no disable (PLANO §2.1)
const val kCGConfigureForSession = 1
const val kCGConfigurePermanently = 2

const val kCFStringEncodingUTF8 = 0x08000100

// Display placeholder criado pelo macOS quando o último display real some (PLANO §2.3 item 4)
const val PLACEHOLDER_VENDOR = 0x756E6B6E // 'unkn'
const val PLACEHOLDER_MODEL = 0x76697274  // 'virt'

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
}

interface CoreFoundation : Library {
    fun CFUUIDCreateString(alloc: Pointer?, uuid: Pointer?): Pointer?

    // Boolean do CF é unsigned char (1 byte) — mapear como Byte e comparar != 0
    fun CFStringGetCString(theString: Pointer?, buffer: ByteArray, bufferSize: Long, encoding: Int): Byte
    fun CFRelease(cf: Pointer?)
}

object NativeApis {
    // Desde o Big Sur estes arquivos não existem no disco (vivem no dyld shared cache);
    // o dlopen com caminho absoluto funciona mesmo assim — nunca condicionar a carga
    // a uma checagem de existência do arquivo (PLANO §4/Fase 0 item 2).
    private const val CG = "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics"
    private const val CF = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
    private const val SKYLIGHT = "/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight"
    private const val COLORSYNC = "/System/Library/Frameworks/ColorSync.framework/ColorSync"

    val cg: CoreGraphics = Native.load(CG, CoreGraphics::class.java)
    val cf: CoreFoundation = Native.load(CF, CoreFoundation::class.java)

    private val cgLib = NativeLibrary.getInstance(CG)
    private val skyLib = NativeLibrary.getInstance(SKYLIGHT)

    /** Mesma forma da CGGetOnlineDisplayList; ao contrário da pública, enumera também desabilitados (PLANO §2.2). */
    val slsGetDisplayList: Function = resolve(skyLib to "SLSGetDisplayList")

    /** SLS primeiro, fallback CGS (re-export do mesmo símbolo — PLANO §2.1/§3). */
    val configureDisplayEnabled: Function =
        resolve(skyLib to "SLSConfigureDisplayEnabled", cgLib to "CGSConfigureDisplayEnabled")

    /** Fallback ColorSync: o símbolo pode não resolver no CoreGraphics (PLANO §4/Fase 0 item 2). */
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
    1001 -> "kCGErrorIllegalArgument(1001) — se foi num religamento, hardware provavelmente removido (PLANO §2.3 item 4)"
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
