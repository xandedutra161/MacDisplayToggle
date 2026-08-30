package mdt.core.persistence

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import mdt.core.ports.EventSink
import mdt.core.testing.saved

class JsonDisplayStateRepositoryTest {

    @Test
    fun `missing state file loads empty state`() {
        val repository = repository()

        assertTrue(repository.load().disabledByUs.isEmpty())
    }

    @Test
    fun `remember persists display and replaces matching display`() {
        val repository = repository()
        val first = saved(id = 3, uuid = "A")
        val replacement = saved(id = 99, uuid = "A")

        repository.remember(first)
        repository.remember(replacement)

        assertEquals(listOf(replacement), repository.load().disabledByUs)
    }

    @Test
    fun `forget removes matching display`() {
        val repository = repository()
        val first = saved(id = 3, uuid = "A")
        val second = saved(id = 4, uuid = "B")
        repository.remember(first)
        repository.remember(second)

        repository.forget(saved(id = 99, uuid = "A"))

        assertEquals(listOf(second), repository.load().disabledByUs)
    }

    @Test
    fun `invalid json is treated as empty state and warns through the sink`() {
        val warnings = mutableListOf<String>()
        val repository = repository(onLog = { warnings.add(it) })
        Files.createDirectories(repository.path.parent)
        Files.writeString(repository.path, "{not-json")

        assertTrue(repository.load().disabledByUs.isEmpty())
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("ilegível"))
    }

    private fun repository(onLog: EventSink = EventSink.Stderr): JsonDisplayStateRepository {
        val dir = Files.createTempDirectory("mdt-state-test")
        return JsonDisplayStateRepository(dir.resolve("state.json"), onLog = onLog)
    }
}
