package mdt.core.domain

import mdt.core.domain.DisplayInfo

enum class DisableBlock {
    PLACEHOLDER,
    BUILTIN,
    ALREADY_DISABLED,
    LAST_ACTIVE_REAL,
}

object DisplayPolicy {
    fun isProductVisible(display: DisplayInfo): Boolean =
        !display.isPlaceholder && !isIdentitylessDisabledEntry(display)

    fun isOperableExternal(display: DisplayInfo): Boolean =
        isProductVisible(display) && !display.builtin

    fun activeRealCount(snapshot: List<DisplayInfo>): Int =
        snapshot.count { it.isActiveReal }

    fun remainingActiveRealAfterDisable(snapshot: List<DisplayInfo>, target: DisplayInfo): Int =
        snapshot.count { it.isActiveReal && it.id != target.id }

    fun disableBlock(snapshot: List<DisplayInfo>, target: DisplayInfo): DisableBlock? = when {
        target.isPlaceholder -> DisableBlock.PLACEHOLDER
        target.builtin -> DisableBlock.BUILTIN
        !target.online -> DisableBlock.ALREADY_DISABLED
        target.isActiveReal && remainingActiveRealAfterDisable(snapshot, target) < 1 -> DisableBlock.LAST_ACTIVE_REAL
        else -> null
    }

    private fun isIdentitylessDisabledEntry(display: DisplayInfo): Boolean =
        display.isDisabled && display.uuid == null && display.vendor == 0 && display.serial == 0
}
