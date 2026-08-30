package mdt.core.application

import mdt.core.domain.DisplayInfo
import mdt.core.DisplayOperations
import mdt.core.domain.DisplayState
import mdt.core.domain.SavedDisplay
import mdt.core.domain.DisplayPolicy
import mdt.core.jna.kCGConfigurePermanently
import mdt.core.ports.DisplayGateway
import mdt.core.ports.DisplayStateRepository
import mdt.core.ports.EventSink

class DisplayReconciler(
    private val displayGateway: DisplayGateway,
    private val stateRepository: DisplayStateRepository,
    private val displayOperations: DisplayOperations,
    private val enableManagedDisplay: (SavedDisplay) -> Int?,
    private val onLog: EventSink,
) {
    private var lastOnline: List<Int>? = null

    fun reconcile(reason: String) {
        try {
            val snap = displayGateway.snapshot()
            val online = snap.filter { it.online }.map { it.id }
            if (lastOnline != null && lastOnline != online) {
                onLog.log("watcher: lista online mudou $lastOnline → $online [$reason]")
            }
            lastOnline = online

            val state = stateRepository.load()
            if (DisplayPolicy.activeRealCount(snap) == 0) {
                restoreEmergency(snap, state)
                return
            }

            for (saved in state.disabledByUs) {
                reconcileSavedDisplay(saved, snap, reason)
            }
        } catch (e: Throwable) {
            onLog.log("watcher: erro no reconcile: ${e.message}")
        }
    }

    private fun reconcileSavedDisplay(saved: SavedDisplay, snap: List<DisplayInfo>, reason: String) {
        val onlineId = displayOperations.matchOnline(saved)
        if (onlineId != null) {
            reapplyDisableIfSafe(saved, snap, onlineId, reason)
        } else {
            forgetIfPhysicallyGone(saved, snap)
        }
    }

    private fun reapplyDisableIfSafe(saved: SavedDisplay, snap: List<DisplayInfo>, onlineId: Int, reason: String) {
        val remaining = snap.count { it.isActiveReal && it.id != onlineId }
        if (remaining >= 1) {
            onLog.log("watcher: ${saved.label()} voltou online (wake?); re-aplicando disconnect [$reason]")
            try {
                displayOperations.disableVerified(onlineId, kCGConfigurePermanently)
            } catch (e: Throwable) {
                onLog.log("watcher: re-aplicação falhou: ${e.message}")
            }
        } else {
            onLog.log("watcher: NÃO re-aplico ${saved.label()} — seria o último display ativo real; removendo do estado desejado")
            stateRepository.forget(saved)
        }
    }

    private fun forgetIfPhysicallyGone(saved: SavedDisplay, snap: List<DisplayInfo>) {
        val presentInSls = (saved.uuid?.let { displayGateway.findByUuidInSls(it) } != null) ||
            saved.id in displayGateway.slsIds() ||
            displayGateway.findBySerialInSls(saved.vendor, saved.model, saved.serial) != null
        val placeholderActive = snap.any { it.isPlaceholder && it.active }
        if (!presentInSls && !placeholderActive) {
            onLog.log("watcher: ${saved.label()} sumiu da lista SLS (cabo removido?) — limpando estado")
            stateRepository.forget(saved)
        }
    }

    private fun restoreEmergency(snap: List<DisplayInfo>, state: DisplayState) {
        onLog.log("watcher: ZERO displays ativos reais — restauração de emergência (restoreIfNoActiveDisplay)")
        // Preferir o que SALVAMOS como builtin: CGDisplayIsBuiltin responde lixo para
        // IDs stale — o flag persistido é confiável, o consultado não.
        for (saved in state.disabledByUs.sortedByDescending { it.builtin }) {
            if (enableManagedDisplay(saved) != null) {
                onLog.log("watcher: restaurado ${saved.label()}")
                return
            }
        }

        // Não-nossos: tentar direto via lista SLS (sem registrar no nosso estado)
        for (d in snap.filter { it.isDisabled && !it.isPlaceholder }) {
            if (displayOperations.enableVerified(d.toSaved()) != null) {
                onLog.log("watcher: restaurado id=${d.id} (via lista SLS)")
                return
            }
        }

        // Último recurso — heurística: o built-in costuma ter ID 1 no Apple Silicon
        onLog.log("watcher: último recurso — enable(1) (heurística ID=1)")
        displayOperations.enableVerified(SavedDisplay(id = 1))
    }
}
