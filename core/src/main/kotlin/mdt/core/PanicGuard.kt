package mdt.core

import mdt.core.domain.SavedDisplay
import mdt.core.ports.DisplayStateRepository

/**
 * Failsafe interno de encerramento: se o processo morrer/for interrompido com um
 * display desabilitado, tenta religar no shutdown hook. NÃO substitui o watchdog
 * externo do protocolo — um SIGSEGV/SIGKILL derruba a JVM sem rodar hooks
 * (recuperação nesse caso: `reconcile` na próxima execução).
 *
 * As dependências chegam no [arm] (vindas do `DisplayManager` que desabilitou),
 * para a recuperação usar o MESMO gateway/runner/estado da operação original —
 * antes o hook usava singletons próprios e podia divergir do manager.
 * Logs em stderr direto: último recurso de um processo morrendo (exceção
 * registrada em `ports/EventSink.kt`).
 */
object PanicGuard {
    private class Armed(
        val saved: SavedDisplay,
        val operations: DisplayOperations,
        val repository: DisplayStateRepository,
    )

    @Volatile
    private var armed: Armed? = null

    init {
        Runtime.getRuntime().addShutdownHook(Thread(::runRecovery, "panic-guard"))
    }

    fun arm(saved: SavedDisplay, operations: DisplayOperations, repository: DisplayStateRepository) {
        armed = Armed(saved, operations, repository)
    }

    fun disarm() {
        armed = null
    }

    internal fun runRecovery() {
        val a = armed ?: return
        System.err.println("\n[failsafe] encerrando com display desabilitado — tentando religar ${a.saved.label()}…")
        try {
            val id = a.operations.enableVerified(a.saved)
            if (id != null) {
                a.repository.forget(a.saved)
                System.err.println("[failsafe] religado (id=$id, comprovado por enumeração).")
            } else {
                System.err.println("[failsafe] NÃO religou — use o watchdog externo ou o playbook de emergência.")
            }
        } catch (t: Throwable) {
            System.err.println("[failsafe] erro ao religar: ${t.message}")
        }
    }
}
