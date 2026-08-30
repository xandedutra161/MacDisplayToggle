package mdt.core

import mdt.core.adapters.NativeDisplayEventSource
import mdt.core.adapters.NativeDisplayGateway
import mdt.core.adapters.NativeTransactionRunner
import mdt.core.application.DisplayReconciler
import mdt.core.domain.DisableBlock
import mdt.core.domain.DisplayError
import mdt.core.domain.DisplayInfo
import mdt.core.domain.DisplayPolicy
import mdt.core.domain.SavedDisplay
import mdt.core.jna.NativeApis
import mdt.core.jna.kCGConfigurePermanently
import mdt.core.ports.DisplayEventSource
import mdt.core.ports.DisplayGateway
import mdt.core.ports.DisplayStateRepository
import mdt.core.ports.EventSink
import mdt.core.ports.TransactionRunner
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * As regras de segurança vivem AQUI, não na UI.
 * - trava do último display ativo real (filtro de placeholder);
 * - no-op recusado por enumeração (a API abortaria com 1001 de qualquer forma);
 * - religamento sempre verificado por enumeração, com retry;
 * - identidade persistida ANTES de desabilitar;
 * - no launch: reconciliar SEMPRE antes de armar o watcher; NUNCA re-aplicar
 *   disconnect no launch;
 * - no encerramento: religar apenas o que NÓS desabilitamos.
 */
class DisplayManager(
    internal val stateRepository: DisplayStateRepository = StateStore,
    internal val displayGateway: DisplayGateway = NativeDisplayGateway,
    transactionRunner: TransactionRunner = NativeTransactionRunner,
    notebookDetector: () -> Boolean = ::detectNotebook,
    internal val onLog: EventSink = EventSink.Stdout,
) {
    internal val displayOperations: DisplayOperations =
        DisplayOperations(displayGateway, transactionRunner, onLog)


    /** Notebook detectado pela BATERIA (IOKit), nunca pelo built-in nas listas. */
    val isNotebook: Boolean by lazy(notebookDetector)

    fun snapshot(): List<DisplayInfo> = displayGateway.snapshot()

    /**
     * Desabilita com todas as travas. Persiste a identidade antes; remove o registro
     * se a transação falhar (não virou um "desabilitado por nós").
     */
    fun disable(targetId: Int, flag: Int = kCGConfigurePermanently): SavedDisplay {
        val snap = snapshot()
        val cur = snap.firstOrNull { it.id == targetId }
            ?: throw DisplayError("display $targetId não encontrado em nenhuma lista")
        DisplayPolicy.disableBlock(snap, cur)?.let { throw DisplayError(disableBlockMessage(it, cur, targetId)) }
        val saved = cur.toSaved()
        stateRepository.remember(saved) // identidade em disco ANTES de desabilitar
        try {
            displayOperations.disableVerified(cur.id, flag)
        } catch (e: Throwable) {
            stateRepository.forget(saved)
            throw e
        }
        onLog.log("desabilitado: ${saved.label()}")
        return saved
    }

    /**
     * Religa com verificação. Remove do estado desejado ANTES de agir (para um watcher
     * concorrente não re-aplicar o disconnect no meio do religamento) e re-registra
     * se falhar (o display continua sendo um "desabilitado por nós" a recuperar).
     */
    fun enable(saved: SavedDisplay): Int? {
        stateRepository.forget(saved)
        val id = displayOperations.enableVerified(saved)
        if (id == null) {
            stateRepository.remember(saved)
        } else {
            onLog.log("religado: ${saved.label()} (id=$id, comprovado por enumeração)")
        }
        return id
    }

    /** "Religar todos" — apenas os NOSSOS (displays de outros apps não são nossos para mexer). */
    fun enableAllOurs(): Map<SavedDisplay, Int?> =
        stateRepository.load().disabledByUs.associateWith { enable(it) }

    /** Ao encerrar o app (o app chama no quit): religar apenas o que nós desabilitamos. */
    fun releaseOnShutdown(): Map<SavedDisplay, Int?> = enableAllOurs()

    /** Arma o failsafe de encerramento com o gateway/runner/estado DESTE manager. */
    fun armShutdownRecovery(saved: SavedDisplay) {
        PanicGuard.arm(saved, displayOperations, stateRepository)
    }

    fun disarmShutdownRecovery() {
        PanicGuard.disarm()
    }

    /**
     * Reconciliação de inicialização (padrão `reconcile`):
     * o que já está online sai do conjunto desejado; o que continua desabilitado é
     * órfão de sessão anterior (crash) — oferecido/religado, NUNCA re-desabilitado.
     * Rodar SEMPRE antes de armar o watcher (senão o 1º tick re-aplicaria disconnect
     * no launch).
     */
    fun reconcileAtLaunch(autoEnableOrphans: Boolean = false): ReconcileReport {
        val alreadyOnline = mutableListOf<SavedDisplay>()
        val orphansDetected = mutableListOf<SavedDisplay>()
        val orphansEnabled = mutableListOf<SavedDisplay>()
        val orphansStuck = mutableListOf<SavedDisplay>()
        for (saved in stateRepository.load().disabledByUs) {
            val online = displayOperations.matchOnline(saved)
            if (online != null) {
                stateRepository.forget(saved)
                alreadyOnline += saved
                onLog.log("reconcile: ${saved.label()} já está online (id=$online) — removido do estado desejado")
            } else if (autoEnableOrphans) {
                onLog.log("reconcile: religando órfão de sessão anterior ${saved.label()}")
                if (enable(saved) != null) orphansEnabled += saved else orphansStuck += saved
            } else {
                orphansDetected += saved
                onLog.log("reconcile: ÓRFÃO detectado ${saved.label()} (sessão anterior) — religue com 'enable' ou 'reconcile --auto'")
            }
        }
        return ReconcileReport(alreadyOnline, orphansDetected, orphansEnabled, orphansStuck)
    }

    /**
     * Watcher de reconfiguração. Chame [reconcileAtLaunch] antes. Para callbacks ativos, a
     * thread onde o CG inicializou deve chamar [Watcher.runLoopBlocking] (na CLI, a
     * main; no app o runloop do AppKit cobre) — sem isso opera só por polling.
     */
    fun startWatcher(
        pollOnly: Boolean = false,
        settleMs: Long = 1_500,
        eventSource: DisplayEventSource = NativeDisplayEventSource(onLog),
    ): Watcher =
        Watcher(
            reconciler = DisplayReconciler(
                displayGateway = displayGateway,
                stateRepository = stateRepository,
                displayOperations = displayOperations,
                enableManagedDisplay = ::enable,
                onLog = onLog,
            ),
            eventSource = eventSource,
            onLog = onLog,
            pollOnly = pollOnly,
            settleMs = settleMs,
        ).also { it.start() }

    // ---- Timer de reversão automática opcional (estilo diálogo de resolução) ----

    private val revertExec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "auto-revert").apply { isDaemon = true }
    }
    private val pendingReverts = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /** Desabilita e agenda religamento automático em [revertSeconds], a menos que [confirmDisable] seja chamado. */
    fun disableWithAutoRevert(targetId: Int, revertSeconds: Long, flag: Int = kCGConfigurePermanently): SavedDisplay {
        val saved = disable(targetId, flag)
        val key = revertKey(saved)
        pendingReverts[key] = revertExec.schedule({
            pendingReverts.remove(key)
            if (displayOperations.matchOnline(saved) == null) {
                onLog.log("auto-revert: sem confirmação em ${revertSeconds}s — religando ${saved.label()}")
                enable(saved)
            }
        }, revertSeconds, TimeUnit.SECONDS)
        return saved
    }

    /** "Manter" — confirma a mudança e cancela o auto-revert pendente. */
    fun confirmDisable(saved: SavedDisplay): Boolean =
        pendingReverts.remove(revertKey(saved))?.cancel(false) ?: false

    private fun revertKey(saved: SavedDisplay) = saved.uuid ?: saved.id.toString()

    private fun disableBlockMessage(block: DisableBlock, cur: DisplayInfo, targetId: Int): String = when (block) {
        DisableBlock.PLACEHOLDER -> "alvo é o display placeholder do macOS, não um display real"
        DisableBlock.BUILTIN ->
            "a tela embutida não pode ser desabilitada — o app desabilita apenas monitores externos"

        DisableBlock.ALREADY_DISABLED -> "no-op recusado: display $targetId já está desabilitado"
        DisableBlock.LAST_ACTIVE_REAL ->
            "TRAVA DE SEGURANÇA: ${cur.name} (id=$targetId) é o último display ativo real — " +
                "desabilitá-lo apagaria/dormiria a máquina"
    }
}

data class ReconcileReport(
    val alreadyOnline: List<SavedDisplay>,
    val orphansDetected: List<SavedDisplay>,
    val orphansEnabled: List<SavedDisplay>,
    val orphansStuck: List<SavedDisplay>,
)

private fun detectNotebook(): Boolean {
    val matching = NativeApis.iokit.IOServiceMatching("AppleSmartBattery") ?: return false
    val service = NativeApis.iokit.IOServiceGetMatchingService(0, matching) // consome `matching`
    return if (service == 0) {
        false
    } else {
        NativeApis.iokit.IOObjectRelease(service)
        true
    }
}
