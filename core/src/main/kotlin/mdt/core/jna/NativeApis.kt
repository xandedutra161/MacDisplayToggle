package mdt.core.jna

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary

object NativeApis {
    // Desde o Big Sur estes arquivos não existem no disco (vivem no dyld shared cache);
    // o dlopen com caminho absoluto funciona mesmo assim — nunca condicionar a carga
    // a uma checagem de existência do arquivo.
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

    /** Mesma forma da CGGetOnlineDisplayList; ao contrário da pública, enumera também desabilitados. */
    val slsGetDisplayList: Function = resolve(skyLib to "SLSGetDisplayList")

    /** SLS primeiro, fallback CGS (re-export do mesmo símbolo). */
    val configureDisplayEnabled: Function =
        resolve(skyLib to "SLSConfigureDisplayEnabled", cgLib to "CGSConfigureDisplayEnabled")

    /**
     * Fallback ColorSync: no Tahoe 26.5.2 o símbolo NÃO existe no CoreGraphics
     * (verificado em máquina real — o fallback é a via real).
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
