package mdt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import mdt.core.domain.DisplayState
import mdt.core.testing.FakeTransactionRunner
import mdt.core.testing.InMemoryDisplayStateRepository
import mdt.core.testing.MutableDisplayGateway
import mdt.core.testing.display
import mdt.core.testing.saved

/** A rotina de recuperação do shutdown hook, exercitada diretamente com fakes. */
class PanicGuardTest {

    @Test
    fun `recovery enables the armed display and forgets it from state`() {
        val savedDisplay = saved(id = 3, uuid = "EXT")
        val repository = InMemoryDisplayStateRepository(DisplayState(listOf(savedDisplay)))
        val gateway = MutableDisplayGateway(
            mutableListOf(display(id = 3, uuid = "EXT", active = false, online = false, inSls = true)),
        )
        val runner = FakeTransactionRunner { id, enabled, _ ->
            if (enabled) gateway.setOnline(id, online = true, active = true)
            0
        }
        val operations = DisplayOperations(gateway, runner, onLog = {})

        try {
            PanicGuard.arm(savedDisplay, operations, repository)
            PanicGuard.runRecovery()
        } finally {
            PanicGuard.disarm()
        }

        assertTrue(repository.load().disabledByUs.isEmpty(), "religado deveria sair do estado")
        assertEquals(listOf(true), runner.calls.map { it.enabled })
    }

    @Test
    fun `recovery after disarm does nothing`() {
        val runner = FakeTransactionRunner()
        val operations = DisplayOperations(MutableDisplayGateway(), runner, onLog = {})
        val repository = InMemoryDisplayStateRepository(DisplayState(listOf(saved(id = 3, uuid = "EXT"))))

        PanicGuard.arm(saved(id = 3, uuid = "EXT"), operations, repository)
        PanicGuard.disarm()
        PanicGuard.runRecovery()

        assertTrue(runner.calls.isEmpty())
        assertEquals(listOf(3), repository.load().disabledByUs.map { it.id })
    }
}
