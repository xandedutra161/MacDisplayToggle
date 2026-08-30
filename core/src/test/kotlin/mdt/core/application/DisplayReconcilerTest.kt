package mdt.core.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import mdt.core.DisplayOperations
import mdt.core.domain.DisplayState
import mdt.core.domain.SavedDisplay
import mdt.core.domain.PLACEHOLDER_MODEL
import mdt.core.domain.PLACEHOLDER_VENDOR
import mdt.core.jna.kCGConfigurePermanently
import mdt.core.testing.FakeTransactionRunner
import mdt.core.testing.InMemoryDisplayStateRepository
import mdt.core.testing.MutableDisplayGateway
import mdt.core.testing.TransactionCall
import mdt.core.testing.display
import mdt.core.testing.saved

class DisplayReconcilerTest {

    @Test
    fun `reapplies disconnect when managed display comes back online and another real display remains`() {
        val managed = saved(id = 3, uuid = "EXT")
        val gateway = MutableDisplayGateway(
            displays = mutableListOf(
                display(id = 1, uuid = "BUILTIN", builtin = true, active = true, online = true),
                display(id = 3, uuid = "EXT", active = true, online = true),
            ),
        )
        val repository = InMemoryDisplayStateRepository(DisplayState(listOf(managed)))
        val runner = FakeTransactionRunner { id, enabled, _ ->
            if (id == 3 && !enabled) gateway.setOnline(id, online = false, active = false)
            0
        }
        val reconciler = reconciler(gateway, repository, runner)

        reconciler.reconcile("teste")

        assertEquals(listOf(TransactionCall(3, false, kCGConfigurePermanently)), runner.calls)
        assertEquals(listOf(managed), repository.load().disabledByUs)
    }

    @Test
    fun `does not reapply disconnect when managed display is the last active real display`() {
        val managed = saved(id = 3, uuid = "EXT")
        val gateway = MutableDisplayGateway(
            displays = mutableListOf(display(id = 3, uuid = "EXT", active = true, online = true)),
        )
        val repository = InMemoryDisplayStateRepository(DisplayState(listOf(managed)))
        val runner = FakeTransactionRunner()
        val reconciler = reconciler(gateway, repository, runner)

        reconciler.reconcile("teste")

        assertTrue(runner.calls.isEmpty())
        assertTrue(repository.load().disabledByUs.isEmpty())
    }

    @Test
    fun `forgets managed display when it is gone from SLS and there is no active placeholder`() {
        val managed = saved(id = 3, uuid = "EXT")
        val gateway = MutableDisplayGateway(
            displays = mutableListOf(display(id = 1, uuid = "BUILTIN", builtin = true, active = true, online = true)),
        )
        val repository = InMemoryDisplayStateRepository(DisplayState(listOf(managed)))
        val runner = FakeTransactionRunner()
        val reconciler = reconciler(gateway, repository, runner)

        reconciler.reconcile("teste")

        assertTrue(repository.load().disabledByUs.isEmpty())
    }

    @Test
    fun `keeps managed display when it is gone from SLS but placeholder is active`() {
        val managed = saved(id = 3, uuid = "EXT")
        val gateway = MutableDisplayGateway(
            displays = mutableListOf(
                display(id = 1, uuid = "BUILTIN", builtin = true, active = true, online = true),
                display(id = 9, uuid = null, vendor = PLACEHOLDER_VENDOR, model = PLACEHOLDER_MODEL, active = true, online = true),
            ),
        )
        val repository = InMemoryDisplayStateRepository(DisplayState(listOf(managed)))
        val runner = FakeTransactionRunner()
        val reconciler = reconciler(gateway, repository, runner)

        reconciler.reconcile("teste")

        assertEquals(listOf(managed), repository.load().disabledByUs)
    }

    @Test
    fun `restores managed display when no real display is active`() {
        val managed = saved(id = 3, uuid = "EXT")
        val gateway = MutableDisplayGateway(
            displays = mutableListOf(display(id = 3, uuid = "EXT", active = false, online = false, inSls = true)),
        )
        val repository = InMemoryDisplayStateRepository(DisplayState(listOf(managed)))
        val restored = mutableListOf<SavedDisplay>()
        val reconciler = reconciler(
            gateway = gateway,
            repository = repository,
            runner = FakeTransactionRunner(),
            enableManagedDisplay = {
                restored += it
                3
            },
        )

        reconciler.reconcile("teste")

        assertEquals(listOf(managed), restored)
    }

    private fun reconciler(
        gateway: MutableDisplayGateway,
        repository: InMemoryDisplayStateRepository,
        runner: FakeTransactionRunner,
        enableManagedDisplay: (SavedDisplay) -> Int? = { null },
    ): DisplayReconciler =
        DisplayReconciler(
            displayGateway = gateway,
            stateRepository = repository,
            displayOperations = DisplayOperations(gateway, runner) {},
            enableManagedDisplay = enableManagedDisplay,
            onLog = {},
        )
}
