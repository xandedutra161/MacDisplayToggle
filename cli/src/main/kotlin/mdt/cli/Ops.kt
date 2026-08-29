package mdt.cli

import mdt.ffi.cgErrorName
import mdt.ffi.kCGConfigurePermanently
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object Ops {

    /**
     * Desabilita e confirma POR ENUMERAÇÃO (fora da lista pública). O retorno da
     * transação sozinho não prova nada (PLANO §2.3 item 1).
     */
    fun disableVerified(targetId: Int, flag: Int) {
        val future = Transactions.fire(targetId, false, flag)
        val err = awaitTransaction(future, 15)
        if (err != null && err != 0) throw PocError("disable falhou na transação: ${cgErrorName(err)}")
        if (err == null) println("  disable: Complete ainda em voo após 15 s (§2.3 item 2) — verificando por enumeração mesmo assim")

        val deadline = System.currentTimeMillis() + 6_000
        while (System.currentTimeMillis() < deadline) {
            if (targetId !in Displays.onlineIds()) {
                val stillInSls = targetId in Displays.slsIds()
                if (stillInSls) {
                    println("  disable: confirmado por enumeração — fora da lista pública, presente na SLS (desabilitado)")
                } else {
                    println("  disable: fora da lista pública e não achado na SLS pelo mesmo ID (ID pode ter sido reatribuído — conferir com 'list')")
                }
                return
            }
            Thread.sleep(300)
        }
        throw PocError("display $targetId continua na lista online após o disable — a transação não teve efeito")
    }

    /**
     * Religa com verificação por enumeração e retry (PLANO §2.3 item 1): enable em
     * fire-and-forget, janela de verificação de ~4,5 s, até 3 tentativas, nunca
     * re-emitindo enquanto um Complete anterior estiver em voo.
     * @return o ID online do display religado, ou null se não voltou.
     */
    fun enableVerified(saved: SavedDisplay, attempts: Int = 3, windowMs: Long = 4_500): Int? {
        matchOnline(saved)?.let { return it }
        var lastFired: CompletableFuture<Int>? = null
        repeat(attempts) { i ->
            // Re-resolução por UUID via SLS; ID salvo é o fallback — inclusive no
            // estado só-placeholder, em que o lookup por UUID falha (§2.3 item 4).
            val id = saved.uuid?.let { Displays.findByUuidInSls(it) } ?: saved.id
            val fired = Transactions.fireIfIdle(id, true, kCGConfigurePermanently)
            if (fired != null) {
                println("  enable: tentativa ${i + 1}/$attempts → ConfigureDisplayEnabled($id, true) [fire-and-forget]")
                lastFired = fired
            } else {
                println("  enable: tentativa ${i + 1}/$attempts — Complete anterior ainda em voo; só verificando (§2.3 item 1)")
            }
            pollWindow(saved, windowMs)?.let { return it }
        }
        lastFired?.let { f ->
            if (f.isDone) {
                val err = runCatching { f.get() }.getOrNull()
                if (err != null && err != 0) println("  enable: última transação retornou ${cgErrorName(err)}")
            } else {
                println("  enable: transação ainda em voo (Complete pode bloquear ~10 s além do retorno do display)")
            }
        }
        return null
    }

    /** Espera o retorno da transação com timeout (§2.3 item 2); null = ainda em voo. */
    fun awaitTransaction(future: CompletableFuture<Int>, timeoutSeconds: Long): Int? = try {
        future.get(timeoutSeconds, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
        null
    } catch (e: ExecutionException) {
        throw PocError("falha na chamada nativa da transação: ${e.cause?.message ?: e.message}")
    }

    /** O display salvo está online AGORA? Match primário por UUID; ID só quando não há UUID para desmentir. */
    fun matchOnline(saved: SavedDisplay): Int? {
        val online = Displays.onlineIds()
        if (saved.uuid != null) {
            for (id in online) {
                if (Displays.uuidOf(id)?.equals(saved.uuid, ignoreCase = true) == true) return id
            }
            // ID pode ser reatribuído enquanto desabilitado (§2.2) — só aceitar o ID
            // se não houver UUID que prove o contrário.
            if (saved.id in online && Displays.uuidOf(saved.id) == null) return saved.id
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

/**
 * Failsafe interno de encerramento: se o processo morrer/for interrompido com um
 * display desabilitado, tenta religar no shutdown hook. NÃO substitui o watchdog
 * externo do protocolo — um SIGSEGV de binding derruba a JVM sem rodar hooks.
 */
object PanicGuard {
    @Volatile
    private var armed: SavedDisplay? = null

    init {
        Runtime.getRuntime().addShutdownHook(Thread({
            val saved = armed ?: return@Thread
            System.err.println("\n[failsafe] encerrando com display desabilitado — tentando religar ${saved.label()}…")
            try {
                val id = Ops.enableVerified(saved)
                if (id != null) {
                    StateStore.forget(saved)
                    System.err.println("[failsafe] religado (id=$id, comprovado por enumeração).")
                } else {
                    System.err.println("[failsafe] NÃO religou — use o watchdog externo ou o playbook de emergência (PLANO §2.4).")
                }
            } catch (t: Throwable) {
                System.err.println("[failsafe] erro ao religar: ${t.message}")
            }
        }, "panic-guard"))
    }

    fun arm(saved: SavedDisplay) {
        armed = saved
    }

    fun disarm() {
        armed = null
    }
}
