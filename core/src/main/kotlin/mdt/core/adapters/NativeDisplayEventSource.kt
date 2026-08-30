package mdt.core.adapters

import com.sun.jna.Pointer
import mdt.core.jna.DisplayReconfigurationCallback
import mdt.core.jna.NativeApis
import mdt.core.jna.cgErrorName
import mdt.core.ports.DisplayEventListener
import mdt.core.ports.DisplayEventSource
import mdt.core.ports.EventSink

/**
 * Fonte nativa: `CGDisplayRegisterReconfigurationCallback` + CFRunLoop.
 * O registro sozinho já mantém as enumerações CG frescas no processo (receita
 * ListFreshness), mesmo quando nenhum runloop roda.
 */
class NativeDisplayEventSource(
    private val onLog: EventSink = EventSink.Stdout,
) : DisplayEventSource {
    // Referência FORTE ao callback: se o GC recolher, o JNA invalida o trampoline nativo.
    private var callbackRef: DisplayReconfigurationCallback? = null

    @Volatile
    private var runLoopRef: Pointer? = null

    override fun register(listener: DisplayEventListener): Boolean {
        if (callbackRef != null) return true
        val cb = object : DisplayReconfigurationCallback {
            override fun invoke(display: Int, flags: Int, userInfo: Pointer?) {
                listener.onDisplayEvent(display, flags)
            }
        }
        val err = NativeApis.cg.CGDisplayRegisterReconfigurationCallback(cb, null)
        return if (err == 0) {
            callbackRef = cb
            true
        } else {
            onLog.log("watcher: CGDisplayRegisterReconfigurationCallback falhou (${cgErrorName(err)})")
            false
        }
    }

    override fun unregister() {
        callbackRef?.let { NativeApis.cg.CGDisplayRemoveReconfigurationCallback(it, null) }
        callbackRef = null
    }

    override fun deliverBlocking(): Long {
        // A fonte Mach da conexão CGS é agendada no runloop da thread onde o CG
        // inicializou — chamar daqui de outra thread deixa o loop sem fontes.
        runLoopRef = NativeApis.cf.CFRunLoopGetCurrent()
        val t0 = System.nanoTime()
        NativeApis.cf.CFRunLoopRun()
        runLoopRef = null
        return (System.nanoTime() - t0) / 1_000_000
    }

    override fun stopDelivering() {
        runLoopRef?.let { NativeApis.cf.CFRunLoopStop(it) }
    }
}
