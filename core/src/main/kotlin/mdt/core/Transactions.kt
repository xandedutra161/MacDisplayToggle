package mdt.core

import com.sun.jna.ptr.PointerByReference
import mdt.core.ffi.NativeApis
import java.util.concurrent.CompletableFuture

/**
 * Transação CGBegin/SLSConfigureDisplayEnabled/CGComplete numa thread descartável.
 *
 * O Complete pode bloquear ~10 s (retraining do link) e a chamada nativa não é
 * cancelável via JNA (PLANO §2.3 item 2): no timeout abandona-se a espera (a thread
 * fica presa até a chamada retornar) e nenhuma outra transação é iniciada enquanto
 * esta não retornar — quem garante isso é [fireIfIdle] (check + start atômicos, o que
 * cobre corridas entre threads, ex.: worker do watcher × shutdown hook).
 */
object Transactions {
    @Volatile
    private var inFlightThread: Thread? = null

    val inFlight: Boolean get() = inFlightThread?.isAlive == true

    /** Dispara a transação se não houver outra em voo; null caso contrário. */
    @Synchronized
    fun fireIfIdle(displayId: Int, enabled: Boolean, flag: Int): CompletableFuture<Int>? {
        if (inFlight) return null
        val future = CompletableFuture<Int>()
        val t = Thread({
            try {
                future.complete(run(displayId, enabled, flag))
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }, "display-config")
        t.isDaemon = true // uma nativa presa não pode impedir o processo de sair
        inFlightThread = t
        t.start()
        return future
    }

    fun fire(displayId: Int, enabled: Boolean, flag: Int): CompletableFuture<Int> =
        fireIfIdle(displayId, enabled, flag)
            ?: throw DisplayError("transação de configuração anterior ainda em voo")

    private fun run(displayId: Int, enabled: Boolean, flag: Int): Int {
        val ref = PointerByReference()
        var err = NativeApis.cg.CGBeginDisplayConfiguration(ref)
        if (err != 0) return err
        val config = ref.value
        err = NativeApis.configureDisplayEnabled.invokeInt(arrayOf<Any?>(config, displayId, if (enabled) 1 else 0))
        if (err != 0) {
            NativeApis.cg.CGCancelDisplayConfiguration(config)
            return err
        }
        err = NativeApis.cg.CGCompleteDisplayConfiguration(config, flag)
        if (err != 0) {
            NativeApis.cg.CGCancelDisplayConfiguration(config)
            return err
        }
        return 0
    }
}
