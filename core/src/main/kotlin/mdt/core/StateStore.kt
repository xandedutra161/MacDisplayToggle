package mdt.core

import java.nio.file.Path
import mdt.core.persistence.JsonDisplayStateRepository
import mdt.core.ports.DisplayStateRepository

private val defaultStatePath: Path =
    Path.of(System.getProperty("user.home"), "Library", "Application Support", "MacDisplayToggle", "state.json")

object StateStore : DisplayStateRepository by JsonDisplayStateRepository(defaultStatePath)
