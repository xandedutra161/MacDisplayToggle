package mdt.core

import mdt.core.ffi.NativeApis
import mdt.core.ffi.kCGConfigurePermanently
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Núcleo da Fase 1 (PLANO §4): as regras de segurança vivem AQUI, não na UI.
 * - trava do último display ativo real (filtro de placeholder — §2.3 item 4);
 * - no-op recusado por enumeração (a API aborta com 1001 de qualquer forma — Fase 0 obs. b);
 * - religamento sempre verificado por enumeração, com retry (§2.3 item 1);
 * - identidade persistida ANTES de desabilitar (§2.2);
 * - no launch: reconciliar SEMPRE antes de armar o watcher; NUNCA re-aplicar
 *   disconnect no launch (§2.3 item 5);
 * - no encerramento: religar apenas o que NÓS desabilitamos.
 */
class DisplayManager(internal val onLog: (String) -> Unit = ::println) {

    /** Notebook detectado pela BATERIA (IOKit — §2.3 item 6), nunca pelo built-in nas listas. */
    val isNotebook: Boolean by lazy {
        val matching = NativeApis.iokit.IOServiceMatching("AppleSmartBattery") ?: return@lazy false
        val service = NativeApis.iokit.IOServiceGetMatchingService(0, matching) // consome `matching`
        if (service == 0) {
            false
        } else {
            NativeApis.iokit.IOObjectRelease(service)
            true
        }
    }

    fun snapshot(): List<DisplayInfo> = Displays.snapshot()

    /**
     * Desabilita com todas as travas. Persiste a identidade antes; remove o registro
     * se a transação falhar (não virou um "desabilitado por nós").
     */
    fun disable(targetId: Int, flag: Int = kCGConfigurePermanently): SavedDisplay {
        val snap = snapshot()
        val cur = snap.firstOrNull { it.id == targetId }
            ?: throw DisplayError("display $targetId não encontrado em nenhuma lista")
        if (cur.isPlaceholder) throw DisplayError("alvo é o display placeholder do macOS, não um display real")
        // Decisão de produto (2026-08-29): a tela embutida é INTOCÁVEL — o app existe
        // para desabilitar monitores EXTERNOS. Religar embutido continua permitido.
        if (cur.builtin) throw DisplayError("a tela embutida não pode ser desabilitada — o app desabilita apenas monitores externos")
        if (!cur.online) throw DisplayError("no-op recusado: display $targetId já está desabilitado (§2.2)")
        val remaining = snap.count { it.isActiveReal && it.id != cur.id }
        if (cur.isActiveReal && remaining < 1) {
            throw DisplayError(
                "TRAVA DE SEGURANÇA: ${cur.name} (id=$targetId) é o último display ativo real — " +
                    "desabilitá-lo apagaria/dormiria a máquina (§2.3 item 3)"
            )
        }
        val saved = cur.toSaved()
        StateStore.remember(saved) // identidade em disco ANTES de desabilitar (§2.2)
        try {
            Ops.disableVerified(cur.id, flag)
        } catch (e: Throwable) {
            StateStore.forget(saved)
            throw e
        }
        onLog("desabilitado: ${saved.label()}")
        return saved
    }

    /**
     * Religa com verificação. Remove do estado desejado ANTES de agir (para um watcher
     * concorrente não re-aplicar o disconnect no meio do religamento) e re-registra
     * se falhar (o display continua sendo um "desabilitado por nós" a recuperar).
     */
    fun enable(saved: SavedDisplay): Int? {
        StateStore.forget(saved)
        val id = Ops.enableVerified(saved)
        if (id == null) {
            StateStore.remember(saved)
        } else {
            onLog("religado: ${saved.label()} (id=$id, comprovado por enumeração)")
        }
        return id
    }

    /** "Religar todos" — apenas os NOSSOS (displays de outros apps não são nossos para mexer). */
    fun enableAllOurs(): Map<SavedDisplay, Int?> =
        StateStore.load().disabledByUs.associateWith { enable(it) }

    /** Ao encerrar o app (Fase 2 chamará no quit): religar apenas o que nós desabilitamos. */
    fun releaseOnShutdown(): Map<SavedDisplay, Int?> = enableAllOurs()

    /**
     * Reconciliação de inicialização (padrão `reconcile` do Crisp — § Fase 1):
     * o que já está online sai do conjunto desejado; o que continua desabilitado é
     * órfão de sessão anterior (crash) — oferecido/religado, NUNCA re-desabilitado.
     * Rodar SEMPRE antes de armar o watcher (senão o 1º tick re-aplicaria disconnect
     * no launch, violando a regra §2.3 item 5).
     */
    fun reconcileAtLaunch(autoEnableOrphans: Boolean = false): ReconcileReport {
        val alreadyOnline = mutableListOf<SavedDisplay>()
        val orphansDetected = mutableListOf<SavedDisplay>()
        val orphansEnabled = mutableListOf<SavedDisplay>()
        val orphansStuck = mutableListOf<SavedDisplay>()
        for (saved in StateStore.load().disabledByUs) {
            val online = Ops.matchOnline(saved)
            if (online != null) {
                StateStore.forget(saved)
                alreadyOnline += saved
                onLog("reconcile: ${saved.label()} já está online (id=$online) — removido do estado desejado")
            } else if (autoEnableOrphans) {
                onLog("reconcile: religando órfão de sessão anterior ${saved.label()}")
                if (enable(saved) != null) orphansEnabled += saved else orphansStuck += saved
            } else {
                orphansDetected += saved
                onLog("reconcile: ÓRFÃO detectado ${saved.label()} (sessão anterior) — religue com 'enable' ou 'reconcile --auto'")
            }
        }
        return ReconcileReport(alreadyOnline, orphansDetected, orphansEnabled, orphansStuck)
    }

    /**
     * Watcher da Fase 1. Chame [reconcileAtLaunch] antes. Para callbacks ativos, a
     * thread onde o CG inicializou deve chamar [Watcher.runLoopBlocking] (na CLI, a
     * main; na Fase 2 o runloop do AppKit cobre) — sem isso opera só por polling.
     */
    fun startWatcher(pollOnly: Boolean = false, settleMs: Long = 1_500): Watcher =
        Watcher(this, onLog, pollOnly, settleMs).also { it.start() }

    // ---- Timer de reversão automática opcional (estilo diálogo de resolução — § Fase 1) ----

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
            if (Ops.matchOnline(saved) == null) {
                onLog("auto-revert: sem confirmação em ${revertSeconds}s — religando ${saved.label()}")
                enable(saved)
            }
        }, revertSeconds, TimeUnit.SECONDS)
        return saved
    }

    /** "Manter" — confirma a mudança e cancela o auto-revert pendente. */
    fun confirmDisable(saved: SavedDisplay): Boolean =
        pendingReverts.remove(revertKey(saved))?.cancel(false) ?: false

    private fun revertKey(saved: SavedDisplay) = saved.uuid ?: saved.id.toString()
}

data class ReconcileReport(
    val alreadyOnline: List<SavedDisplay>,
    val orphansDetected: List<SavedDisplay>,
    val orphansEnabled: List<SavedDisplay>,
    val orphansStuck: List<SavedDisplay>,
)
