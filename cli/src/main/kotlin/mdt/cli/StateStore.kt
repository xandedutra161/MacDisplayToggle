package mdt.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Identidade persistida ANTES de desabilitar (PLANO §2.2): UUID é a chave primária
 * de re-resolução, ID o fallback, serial identidade extra.
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

@Serializable
data class PocState(val disabledByUs: List<SavedDisplay> = emptyList())

object StateStore {
    private val dir: Path =
        Path.of(System.getProperty("user.home"), "Library", "Application Support", "MacDisplayToggle")
    val path: Path = dir.resolve("state.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): PocState {
        if (!Files.exists(path)) return PocState()
        return try {
            json.decodeFromString<PocState>(Files.readString(path))
        } catch (e: Exception) {
            System.err.println("aviso: estado em $path ilegível (${e.message}) — tratando como vazio")
            PocState()
        }
    }

    fun save(state: PocState) {
        Files.createDirectories(dir)
        // Escrita atômica: um crash no meio do write não pode corromper o registro
        // de que há um display desabilitado esperando religamento.
        val tmp = dir.resolve("state.json.tmp")
        Files.writeString(tmp, json.encodeToString(state))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun remember(d: SavedDisplay) {
        val s = load()
        save(s.copy(disabledByUs = s.disabledByUs.filterNot { it.matches(d) } + d))
    }

    fun forget(d: SavedDisplay) {
        val s = load()
        save(s.copy(disabledByUs = s.disabledByUs.filterNot { it.matches(d) }))
    }
}
