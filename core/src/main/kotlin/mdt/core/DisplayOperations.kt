package mdt.core

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import mdt.core.adapters.NativeDisplayGateway
import mdt.core.adapters.NativeTransactionRunner
import mdt.core.domain.DisplayError
import mdt.core.domain.SavedDisplay
import mdt.core.jna.cgErrorName
import mdt.core.jna.kCGConfigurePermanently
import mdt.core.ports.DisplayGateway
import mdt.core.ports.EventSink
import mdt.core.ports.TransactionRunner

class DisplayOperations(
    private val displayGateway: DisplayGateway = NativeDisplayGateway,
    private val transactionRunner: TransactionRunner = NativeTransactionRunner,
    private val onLog: EventSink = EventSink.Stdout,
) {

    /**
     * Desabilita e confirma POR ENUMERAÇÃO (fora da lista pública). O retorno da
     * transação sozinho não prova nada.
     */
    fun disableVerified(targetId: Int, flag: Int) {
        val future = transactionRunner.fire(targetId, false, flag)
        val err = awaitTransaction(future, 15)
        if (err != null && err != 0) throw DisplayError("disable falhou na transação: ${cgErrorName(err)}")
        if (err == null) onLog.log("  disable: Complete ainda em voo após 15 s — verificando por enumeração mesmo assim")

        val deadline = System.currentTimeMillis() + 6_000
        while (System.currentTimeMillis() < deadline) {
            if (targetId !in displayGateway.onlineIds()) {
                val stillInSls = targetId in displayGateway.slsIds()
                if (stillInSls) {
                    onLog.log("  disable: confirmado por enumeração — fora da lista pública, presente na SLS (desabilitado)")
                } else {
                    onLog.log("  disable: fora da lista pública e não achado na SLS pelo mesmo ID (ID pode ter sido reatribuído — conferir com 'list')")
                }
                return
            }
            Thread.sleep(300)
        }
        throw DisplayError("display $targetId continua na lista online após o disable — a transação não teve efeito")
    }

    /**
     * Religa com verificação por enumeração e retry: enable em
     * fire-and-forget, janela de verificação de ~4,5 s, até 3 tentativas, nunca
     * re-emitindo enquanto um Complete anterior estiver em voo.
     * Re-resolução: UUID → serial (plano B) → ID salvo.
     * @return o ID online do display religado, ou null se não voltou.
     */
    fun enableVerified(saved: SavedDisplay, attempts: Int = 3, windowMs: Long = 4_500): Int? {
        matchOnline(saved)?.let { return it }
        var lastFired: CompletableFuture<Int>? = null
        repeat(attempts) { i ->
            val id = saved.uuid?.let { displayGateway.findByUuidInSls(it) }
                ?: displayGateway.findBySerialInSls(saved.vendor, saved.model, saved.serial)
                ?: saved.id
            val fired = transactionRunner.fireIfIdle(id, true, kCGConfigurePermanently)
            if (fired != null) {
                onLog.log("  enable: tentativa ${i + 1}/$attempts → ConfigureDisplayEnabled($id, true) [fire-and-forget]")
                lastFired = fired
            } else {
                onLog.log("  enable: tentativa ${i + 1}/$attempts — Complete anterior ainda em voo; só verificando")
            }
            pollWindow(saved, windowMs)?.let { return it }
        }
        lastFired?.let { f ->
            if (f.isDone) {
                val err = runCatching { f.get() }.getOrNull()
                if (err != null && err != 0) onLog.log("  enable: última transação retornou ${cgErrorName(err)}")
            } else {
                onLog.log("  enable: transação ainda em voo (Complete pode bloquear ~10 s além do retorno do display)")
            }
        }
        return null
    }

    /** Espera o retorno da transação com timeout; null = ainda em voo. */
    fun awaitTransaction(future: CompletableFuture<Int>, timeoutSeconds: Long): Int? = try {
        future.get(timeoutSeconds, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
        null
    } catch (e: ExecutionException) {
        throw DisplayError("falha na chamada nativa da transação: ${e.cause?.message ?: e.message}")
    }

    /** O display salvo está online AGORA? Match primário por UUID; ID só quando não há UUID para desmentir. */
    fun matchOnline(saved: SavedDisplay): Int? {
        val online = displayGateway.onlineIds()
        if (saved.uuid != null) {
            for (id in online) {
                if (displayGateway.uuidOf(id)?.equals(saved.uuid, ignoreCase = true) == true) return id
            }
            // ID pode ser reatribuído enquanto desabilitado — só aceitar o ID
            // se não houver UUID que prove o contrário.
            if (saved.id in online && displayGateway.uuidOf(saved.id) == null) return saved.id
            return null
        }
        return if (saved.id in online) saved.id else null
    }

    private fun pollWindow(saved: SavedDisplay, windowMs: Long): Int? {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            matchOnline(saved)?.let { return it }
            Thread.sleep(500)
        }
        return null
    }
}
