package mdt.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mdt.core.application.DisableResult
import mdt.core.application.DisplayHandle
import mdt.core.application.EnableResult
import mdt.core.application.ExternalDisplayToggleFacade
import mdt.core.application.ExternalDisplayView
import mdt.core.domain.DisableBlock

class PendingRevert(val handle: DisplayHandle, val deadlineMs: Long)

/**
 * Estado da UI. As operações rodam em Dispatchers.IO (religamento verifica por
 * enumeração e pode levar segundos — nunca na thread de UI); escrita de snapshot
 * state fora da UI thread é segura no Compose.
 */
class AppState(private val facade: ExternalDisplayToggleFacade, private val scope: CoroutineScope) {
    var externalDisplays by mutableStateOf(emptyList<ExternalDisplayView>())
    var busy by mutableStateOf(false)
    var lastMessage by mutableStateOf<String?>(null)
    var pendingRevert by mutableStateOf<PendingRevert?>(null)
    var clamshellRisk by mutableStateOf(false)

    var isNotebook by mutableStateOf(false)
        private set

    fun refresh() {
        scope.launch {
            runCatching {
                val snapshot = facade.snapshot()
                externalDisplays = snapshot.externalDisplays
                isNotebook = snapshot.isNotebook
                clamshellRisk = snapshot.clamshellRisk
            }.onFailure { lastMessage = "erro ao listar: ${it.message}" }
        }
    }

    /** Desliga com timer de reversão automática. */
    fun requestDisable(d: ExternalDisplayView, revertSeconds: Long = 20) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching {
                when (val r = facade.disableExternalWithAutoRevert(d.id, revertSeconds)) {
                    is DisableResult.Disabled -> {
                        r.pendingRevert?.let { pendingRevert = PendingRevert(it.handle, it.deadlineMs) }
                        lastMessage = "\"${d.name}\" desligado — reverte sozinho em ${revertSeconds}s se você não mantiver"
                    }
                    is DisableResult.Blocked -> lastMessage = "não desliguei: ${blockedMessage(r.reason)}"
                    is DisableResult.NotFound -> lastMessage = "não desliguei: \"${d.name}\" não está mais conectado"
                    is DisableResult.Failed -> lastMessage = "não desliguei: ${r.message}"
                }
            }.onFailure { lastMessage = "não desliguei: ${it.message}" }
            busy = false
            refresh()
        }
    }

    private fun blockedMessage(reason: DisableBlock): String = when (reason) {
        DisableBlock.BUILTIN -> "a tela embutida não pode ser desligada"
        DisableBlock.LAST_ACTIVE_REAL -> "é o último display ativo — desligá-lo apagaria a tela da máquina"
        DisableBlock.ALREADY_DISABLED -> "já está desligado"
        DisableBlock.PLACEHOLDER -> "não é um monitor real"
    }

    fun keepDisabled() {
        val p = pendingRevert ?: return
        facade.confirmDisable(p.handle)
        pendingRevert = null
        lastMessage = "mantido desligado — religue pelo toggle ou \"Religar todos\""
    }

    fun revertNow() {
        val p = pendingRevert ?: return
        pendingRevert = null
        enableHandle(p.handle)
    }

    fun enable(d: ExternalDisplayView) = enableHandle(d.handle)

    private fun enableHandle(handle: DisplayHandle) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching {
                facade.confirmDisable(handle) // cancela auto-revert pendente deste monitor
                lastMessage = when (val r = facade.enableExternal(handle)) {
                    is EnableResult.Enabled -> "religado (id=${r.onlineId}, comprovado por enumeração)"
                    is EnableResult.VerificationTimedOut ->
                        "NÃO religou — tente \"Religar todos\" ou o playbook de emergência"
                    is EnableResult.Failed -> "erro ao religar: ${r.message}"
                }
            }.onFailure { lastMessage = "erro: ${it.message}" }
            if (pendingRevert?.handle?.matches(handle) == true) pendingRevert = null
            busy = false
            refresh()
        }
    }

    /** Sempre visível na UI; religa apenas o que NÓS desabilitamos. */
    fun enableAllOurs() {
        if (busy) return
        busy = true
        scope.launch {
            runCatching {
                val res = facade.enableAllManaged()
                val ok = res.count { it is EnableResult.Enabled }
                lastMessage = if (res.isEmpty()) "nenhum monitor externo desligado por nós" else "religados: $ok/${res.size}"
            }.onFailure { lastMessage = "erro: ${it.message}" }
            pendingRevert = null
            busy = false
            refresh()
        }
    }
}
