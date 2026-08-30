package mdt.core.domain

import kotlinx.serialization.Serializable

/**
 * Identidade persistida ANTES de desabilitar. Descoberta em máquina real:
 * com o display desabilitado o UUID não resolve — o ID é a chave efetiva de
 * religamento e vendor/model/serial são o plano B de matching.
 */
@Serializable
data class SavedDisplay(
    val id: Int,
    val uuid: String? = null,
    val vendor: Int = 0,
    val model: Int = 0,
    val serial: Int = 0,
    val builtin: Boolean = false,
    val savedAt: String = "",
) {
    fun label(): String = "id=${Integer.toUnsignedString(id)}" + (uuid?.let { " uuid=$it" } ?: "")

    fun matches(other: SavedDisplay): Boolean =
        if (uuid != null && other.uuid != null) uuid.equals(other.uuid, ignoreCase = true) else id == other.id
}

/** `disabledByUs` = desabilitados POR NÓS e desejados desabilitados (o watcher re-aplica). */
@Serializable
data class DisplayState(val disabledByUs: List<SavedDisplay> = emptyList())
