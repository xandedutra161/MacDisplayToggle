package mdt.core.application

import java.time.OffsetDateTime
import mdt.core.domain.DisplayError
import mdt.core.domain.DisplayInfo
import mdt.core.DisplayManager
import mdt.core.ReconcileReport
import mdt.core.domain.SavedDisplay
import mdt.core.Watcher
import mdt.core.domain.DisplayPolicy
import mdt.core.jna.kCGConfigureForSession
import mdt.core.jna.kCGConfigurePermanently

enum class DisplayConfigurationScope(internal val flag: Int) {
    PERMANENT(kCGConfigurePermanently),
    SESSION(kCGConfigureForSession),
}

class DisplayHandle internal constructor(internal val saved: SavedDisplay) {
    val id: Int get() = saved.id
    val uuid: String? get() = saved.uuid
    val label: String get() = saved.label()
    val savedAt: String get() = saved.savedAt

    fun matches(other: DisplayHandle): Boolean = saved.matches(other.saved)
}

data class ExternalDisplayView(
    val id: Int,
    val name: String,
    val disabled: Boolean,
    val active: Boolean,
    val online: Boolean,
    val handle: DisplayHandle,
)

data class ExternalDisplaySnapshot(
    val externalDisplays: List<ExternalDisplayView>,
    val isNotebook: Boolean,
    val clamshellRisk: Boolean,
)

data class PendingExternalDisable(
    val handle: DisplayHandle,
    val deadlineMs: Long,
)

interface ExternalDisplayToggleFacade {
    fun snapshot(): ExternalDisplaySnapshot
    fun managedExternalDisplays(): List<DisplayHandle>
    fun findManagedExternalDisplay(target: String): DisplayHandle?
    fun recoveryExternalDisplay(target: String): DisplayHandle
    fun disableExternal(
        displayId: Int,
        scope: DisplayConfigurationScope = DisplayConfigurationScope.PERMANENT,
    ): DisableResult
    fun disableExternalWithAutoRevert(
        displayId: Int,
        autoRevertSeconds: Long,
        scope: DisplayConfigurationScope = DisplayConfigurationScope.PERMANENT,
    ): DisableResult
    fun confirmDisable(handle: DisplayHandle): Boolean
    fun enableExternal(handle: DisplayHandle): EnableResult
    fun onlineId(handle: DisplayHandle): Int?
    fun forgetManagedExternal(handle: DisplayHandle)
    fun enableAllManaged(): List<EnableResult>
    fun reconcileAtLaunch(autoEnableOrphans: Boolean = false): ReconcileReport
    fun startWatcher(pollOnly: Boolean = false, settleMs: Long = 1_500): Watcher
    fun releaseOnShutdown(): List<EnableResult>
    fun armShutdownRecovery(handle: DisplayHandle)
    fun disarmShutdownRecovery()
}

class DefaultExternalDisplayToggleFacade(
    private val manager: DisplayManager = DisplayManager(),
) : ExternalDisplayToggleFacade {

    override fun snapshot(): ExternalDisplaySnapshot {
        val visibleDisplays = manager.snapshot().filter(DisplayPolicy::isProductVisible)
        return ExternalDisplaySnapshot(
            externalDisplays = visibleDisplays
                .filter(DisplayPolicy::isOperableExternal)
                .map { it.toExternalView() },
            isNotebook = manager.isNotebook,
            clamshellRisk = manager.isNotebook && visibleDisplays.none { it.builtin && it.active },
        )
    }

    override fun managedExternalDisplays(): List<DisplayHandle> =
        manager.stateRepository.load().disabledByUs
            .filterNot { it.builtin }
            .map(::DisplayHandle)

    override fun findManagedExternalDisplay(target: String): DisplayHandle? {
        val managed = managedExternalDisplays()
        val asId = target.toUIntOrNull()?.toInt()
        if (asId != null) return managed.firstOrNull { it.id == asId }
        val uuidPrefix = target.uppercase()
        return managed.firstOrNull { it.uuid?.uppercase()?.startsWith(uuidPrefix) == true }
    }

    override fun recoveryExternalDisplay(target: String): DisplayHandle {
        val asId = target.toUIntOrNull()?.toInt()
        if (asId != null) {
            rejectBuiltinIfKnown(asId)
            return DisplayHandle(
                SavedDisplay(
                    id = asId,
                    uuid = manager.displayGateway.uuidOf(asId),
                    savedAt = nowIso(),
                ),
            )
        }

        val uuidPrefix = target.uppercase()
        val matches = manager.displayGateway.slsIds()
            .mapNotNull { id -> manager.displayGateway.uuidOf(id)?.let { id to it } }
            .filter { (_, uuid) -> uuid.startsWith(uuidPrefix) }
        return when {
            matches.size == 1 -> {
                rejectBuiltinIfKnown(matches[0].first)
                DisplayHandle(SavedDisplay(id = matches[0].first, uuid = matches[0].second, savedAt = nowIso()))
            }
            matches.isEmpty() -> throw DisplayError("uuid '$target' não está nem no estado salvo nem na lista SLS")
            else -> throw DisplayError("uuid '$target' é ambíguo na lista SLS — use mais caracteres")
        }
    }

    override fun disableExternal(displayId: Int, scope: DisplayConfigurationScope): DisableResult =
        runDisable(displayId) {
            DisableResult.Disabled(DisplayHandle(manager.disable(displayId, scope.flag)))
        }

    override fun disableExternalWithAutoRevert(
        displayId: Int,
        autoRevertSeconds: Long,
        scope: DisplayConfigurationScope,
    ): DisableResult =
        runDisable(displayId) {
            val handle = DisplayHandle(manager.disableWithAutoRevert(displayId, autoRevertSeconds, scope.flag))
            DisableResult.Disabled(
                handle = handle,
                pendingRevert = PendingExternalDisable(
                    handle = handle,
                    deadlineMs = System.currentTimeMillis() + autoRevertSeconds * 1_000,
                ),
            )
        }

    /**
     * Motivos previsíveis viram tipos ANTES de chamar o manager; a trava canônica
     * continua no núcleo — se ela disparar numa corrida (snapshot mudou entre a
     * checagem e a transação), o `DisplayError` chega mapeado em `Failed`.
     */
    private inline fun runDisable(displayId: Int, disable: () -> DisableResult.Disabled): DisableResult {
        val snap = manager.snapshot()
        val target = snap.firstOrNull { it.id == displayId }
            ?: return DisableResult.NotFound(displayId)
        DisplayPolicy.disableBlock(snap, target)?.let { return DisableResult.Blocked(it) }
        return try {
            disable()
        } catch (e: DisplayError) {
            DisableResult.Failed(e.message ?: "falha ao desabilitar o monitor")
        }
    }

    override fun confirmDisable(handle: DisplayHandle): Boolean =
        manager.confirmDisable(handle.saved)

    override fun enableExternal(handle: DisplayHandle): EnableResult = try {
        when (val onlineId = manager.enable(handle.saved)) {
            null -> EnableResult.VerificationTimedOut(handle)
            else -> EnableResult.Enabled(handle, onlineId)
        }
    } catch (e: DisplayError) {
        EnableResult.Failed(handle, e.message ?: "falha ao religar o monitor")
    }

    override fun onlineId(handle: DisplayHandle): Int? =
        manager.displayOperations.matchOnline(handle.saved)

    override fun forgetManagedExternal(handle: DisplayHandle) {
        manager.stateRepository.forget(handle.saved)
    }

    override fun enableAllManaged(): List<EnableResult> =
        managedExternalDisplays().map { enableExternal(it) }

    override fun reconcileAtLaunch(autoEnableOrphans: Boolean): ReconcileReport =
        manager.reconcileAtLaunch(autoEnableOrphans)

    override fun startWatcher(pollOnly: Boolean, settleMs: Long): Watcher =
        manager.startWatcher(pollOnly, settleMs)

    override fun releaseOnShutdown(): List<EnableResult> =
        enableAllManaged()

    override fun armShutdownRecovery(handle: DisplayHandle) {
        manager.armShutdownRecovery(handle.saved)
    }

    override fun disarmShutdownRecovery() {
        manager.disarmShutdownRecovery()
    }

    private fun DisplayInfo.toExternalView(): ExternalDisplayView =
        ExternalDisplayView(
            id = id,
            name = name,
            disabled = isDisabled,
            active = active,
            online = online,
            handle = DisplayHandle(toSaved()),
        )

    private fun rejectBuiltinIfKnown(displayId: Int) {
        val display = manager.snapshot().firstOrNull { it.id == displayId } ?: return
        if (display.builtin) {
            throw DisplayError("a tela embutida não é um alvo operável — o app desabilita apenas monitores externos")
        }
    }

    private fun nowIso(): String =
        OffsetDateTime.now().withNano(0).toString()
}
