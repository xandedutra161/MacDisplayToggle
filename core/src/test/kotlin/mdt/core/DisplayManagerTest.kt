package mdt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import mdt.core.domain.DisplayError
import mdt.core.domain.DisplayState
import mdt.core.jna.kCGConfigurePermanently
import mdt.core.testing.FakeTransactionRunner
import mdt.core.testing.InMemoryDisplayStateRepository
import mdt.core.testing.MutableDisplayGateway
import mdt.core.testing.TransactionCall
import mdt.core.testing.display
import mdt.core.testing.saved

class DisplayManagerTest {

    @Test
    fun `disable persists display before transaction and keeps state after success`() {
        val repository = InMemoryDisplayStateRepository()
        val gateway = MutableDisplayGateway(
            mutableListOf(
                display(id = 1, uuid = "BUILTIN", builtin = true, active = true),
                display(id = 3, uuid = "EXT", active = true),
            ),
        )
        var sawPersistedStateDuringTransaction = false
        val runner = FakeTransactionRunner { id, enabled, _ ->
            sawPersistedStateDuringTransaction = repository.load().disabledByUs.any { it.id == 3 && it.uuid == "EXT" }
            if (id == 3 && !enabled) gateway.setOnline(id, online = false, active = false)
            0
        }
        val manager = manager(repository, gateway, runner)

        val saved = manager.disable(3)

        assertEquals(3, saved.id)
        assertTrue(sawPersistedStateDuringTransaction)
        assertEquals(listOf(3), repository.load().disabledByUs.map { it.id })
        assertEquals(listOf(TransactionCall(3, false, kCGConfigurePermanently)), runner.calls)
    }

    @Test
    fun `disable rolls back persisted state when transaction fails`() {
        val repository = InMemoryDisplayStateRepository()
        val gateway = MutableDisplayGateway(
            mutableListOf(
                display(id = 1, uuid = "BUILTIN", builtin = true, active = true),
                display(id = 3, uuid = "EXT", active = true),
            ),
        )
        val runner = FakeTransactionRunner { _, _, _ -> 1000 }
        val manager = manager(repository, gateway, runner)

        assertFailsWith<DisplayError> {
            manager.disable(3)
        }

        assertTrue(repository.load().disabledByUs.isEmpty())
        assertEquals(listOf(TransactionCall(3, false, kCGConfigurePermanently)), runner.calls)
    }

    @Test
    fun `disable rejects builtin before transaction and persistence`() {
        val repository = InMemoryDisplayStateRepository()
        val gateway = MutableDisplayGateway(
            mutableListOf(
                display(id = 1, uuid = "BUILTIN", builtin = true, active = true),
                display(id = 3, uuid = "EXT", active = true),
            ),
        )
        val runner = FakeTransactionRunner()
        val manager = manager(repository, gateway, runner)

        assertFailsWith<DisplayError> {
            manager.disable(1)
        }

        assertTrue(repository.load().disabledByUs.isEmpty())
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `disable rejects last active real display before transaction and persistence`() {
        val repository = InMemoryDisplayStateRepository()
        val gateway = MutableDisplayGateway(
            mutableListOf(display(id = 3, uuid = "EXT", active = true)),
        )
        val runner = FakeTransactionRunner()
        val manager = manager(repository, gateway, runner)

        assertFailsWith<DisplayError> {
            manager.disable(3)
        }

        assertTrue(repository.load().disabledByUs.isEmpty())
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `enable forgets stale managed state when display is already online`() {
        val saved = saved(id = 3, uuid = "EXT")
        val repository = InMemoryDisplayStateRepository(DisplayState(listOf(saved)))
        val gateway = MutableDisplayGateway(
            mutableListOf(display(id = 3, uuid = "EXT", active = true, online = true)),
        )
        val runner = FakeTransactionRunner()
        val manager = manager(repository, gateway, runner)

        val enabledId = manager.enable(saved)

        assertEquals(3, enabledId)
        assertTrue(repository.load().disabledByUs.isEmpty())
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `reconcile at launch forgets saved display that is already online`() {
        val saved = saved(id = 3, uuid = "EXT")
        val repository = InMemoryDisplayStateRepository(DisplayState(listOf(saved)))
        val gateway = MutableDisplayGateway(
            mutableListOf(display(id = 3, uuid = "EXT", active = true, online = true)),
        )
        val manager = manager(repository, gateway, FakeTransactionRunner())

        val report = manager.reconcileAtLaunch(autoEnableOrphans = false)

        assertEquals(listOf(saved), report.alreadyOnline)
        assertTrue(repository.load().disabledByUs.isEmpty())
    }

    @Test
    fun `reconcile at launch reports orphan without reapplying disconnect`() {
        val saved = saved(id = 3, uuid = "EXT")
        val repository = InMemoryDisplayStateRepository(DisplayState(listOf(saved)))
        val gateway = MutableDisplayGateway(
            mutableListOf(display(id = 3, uuid = "EXT", active = false, online = false, inSls = true)),
        )
        val runner = FakeTransactionRunner()
        val manager = manager(repository, gateway, runner)

        val report = manager.reconcileAtLaunch(autoEnableOrphans = false)

        assertEquals(listOf(saved), report.orphansDetected)
        assertEquals(listOf(saved), repository.load().disabledByUs)
        assertTrue(runner.calls.isEmpty())
    }

    private fun manager(
        repository: InMemoryDisplayStateRepository,
        gateway: MutableDisplayGateway,
        runner: FakeTransactionRunner,
    ): DisplayManager =
        DisplayManager(
            stateRepository = repository,
            displayGateway = gateway,
            transactionRunner = runner,
            notebookDetector = { true },
            onLog = {},
        )
}
