package mdt.core.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mdt.core.domain.DisplayState
import mdt.core.domain.SavedDisplay
import mdt.core.ports.DisplayStateRepository
import mdt.core.ports.EventSink

class JsonDisplayStateRepository(
    override val path: Path,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
    private val onLog: EventSink = EventSink.Stderr,
) : DisplayStateRepository {

    override fun load(): DisplayState {
        if (!Files.exists(path)) return DisplayState()
        return try {
            json.decodeFromString<DisplayState>(Files.readString(path))
        } catch (e: Exception) {
            onLog.log("aviso: estado em $path ilegível (${e.message}) — tratando como vazio")
            DisplayState()
        }
    }

    override fun save(state: DisplayState) {
        val dir = path.parent ?: Path.of(".")
        Files.createDirectories(dir)
        // Escrita atômica: um crash no meio do write não pode corromper o registro
        // de que há um display desabilitado esperando religamento.
        val tmp = dir.resolve("${path.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(state))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun remember(display: SavedDisplay) {
        val state = load()
        save(state.copy(disabledByUs = state.disabledByUs.filterNot { it.matches(display) } + display))
    }

    override fun forget(display: SavedDisplay) {
        val state = load()
        save(state.copy(disabledByUs = state.disabledByUs.filterNot { it.matches(display) }))
    }
}
