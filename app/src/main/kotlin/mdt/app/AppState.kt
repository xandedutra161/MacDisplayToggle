package mdt.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mdt.core.DisplayInfo
import mdt.core.DisplayManager
import mdt.core.SavedDisplay

class PendingRevert(val saved: SavedDisplay, val deadlineMs: Long)

/**
 * Estado da UI. As operações rodam em Dispatchers.IO (religamento verifica por
 * enumeração e pode levar segundos — nunca na thread de UI); escrita de snapshot
 * state fora da UI thread é segura no Compose.
 */
class AppState(val manager: DisplayManager, private val scope: CoroutineScope) {
    var displays by mutableStateOf(emptyList<DisplayInfo>())
    var busy by mutableStateOf(false)
    var lastMessage by mutableStateOf<String?>(null)
    var pendingRevert by mutableStateOf<PendingRevert?>(null)

    val isNotebook: Boolean get() = manager.isNotebook

    /** Cenário clamshell AGORA: notebook sem built-in ativo nas listas (§2.3 itens 3/6). */
    val clamshellRisk: Boolean
        get() = isNotebook && displays.none { it.builtin && it.active }

    fun refresh() {
        scope.launch {
            runCatching {
                displays = manager.snapshot().filter { d ->
                    // ocultar o placeholder e entradas SLS sem identidade (ghost sem UUID/vendor/serial)
                    !d.isPlaceholder && !(d.isDisabled && d.uuid == null && d.vendor == 0 && d.serial == 0)
                }
            }.onFailure { lastMessage = "erro ao listar: ${it.message}" }
        }
    }

    /** Desabilita com timer de reversão automática (§ Fase 1 — estilo diálogo de resolução). */
    fun requestDisable(d: DisplayInfo, revertSeconds: Long = 20) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching {
                val saved = manager.disableWithAutoRevert(d.id, revertSeconds)
                pendingRevert = PendingRevert(saved, System.currentTimeMillis() + revertSeconds * 1000)
                lastMessage = "\"${d.name}\" desabilitado — reverte sozinho em ${revertSeconds}s se você não mantiver"
            }.onFailure { lastMessage = "não desabilitei: ${it.message}" }
            busy = false
            refresh()
        }
    }

    fun keepDisabled() {
        val p = pendingRevert ?: return
        manager.confirmDisable(p.saved)
        pendingRevert = null
        lastMessage = "mantido desabilitado — religue pelo toggle ou \"Religar todos\""
    }

    fun revertNow() {
        val p = pendingRevert ?: return
        pendingRevert = null
        enableSaved(p.saved)
    }

    fun enable(d: DisplayInfo) = enableSaved(d.toSaved())

    private fun enableSaved(saved: SavedDisplay) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching {
                manager.confirmDisable(saved) // cancela auto-revert pendente deste display
                val id = manager.enable(saved)
                lastMessage = if (id != null) "religado (id=$id, comprovado por enumeração)"
                else "NÃO religou — tente \"Religar todos\" ou o playbook de emergência (PLANO §2.4)"
            }.onFailure { lastMessage = "erro: ${it.message}" }
            if (pendingRevert?.saved?.matches(saved) == true) pendingRevert = null
            busy = false
            refresh()
        }
    }

    /** Sempre visível na UI (PLANO §4/Fase 2); religa apenas o que NÓS desabilitamos. */
    fun enableAllOurs() {
        if (busy) return
        busy = true
        scope.launch {
            runCatching {
                val res = manager.enableAllOurs()
                val ok = res.values.count { it != null }
                lastMessage = if (res.isEmpty()) "nenhum display desabilitado por nós" else "religados: $ok/${res.size}"
            }.onFailure { lastMessage = "erro: ${it.message}" }
            pendingRevert = null
            busy = false
            refresh()
        }
    }
}
