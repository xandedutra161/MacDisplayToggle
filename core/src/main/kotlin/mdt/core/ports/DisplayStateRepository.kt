package mdt.core.ports

import java.nio.file.Path
import mdt.core.domain.DisplayState
import mdt.core.domain.SavedDisplay

interface DisplayStateRepository {
    val path: Path

    fun load(): DisplayState
    fun save(state: DisplayState)
    fun remember(display: SavedDisplay)
    fun forget(display: SavedDisplay)
}
