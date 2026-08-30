package mdt.core.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import mdt.core.domain.DisplayInfo
import mdt.core.DisplayManager
import mdt.core.domain.DisableBlock
import mdt.core.testing.FakeTransactionRunner
import mdt.core.testing.InMemoryDisplayStateRepository
import mdt.core.testing.MutableDisplayGateway
import mdt.core.testing.display

class DefaultExternalDisplayToggleFacadeTest {

    @Test
    fun `snapshot exposes only external operable displays`() {
        val facade = facade(
            displays = listOf(
                display(id = 1, builtin = true, active = true),
                display(id = 2, uuid = null, vendor = 0, serial = 0, active = false, online = false),
                display(id = 3, builtin = false, active = true),
            ),
        )

        val snapshot = facade.snapshot()

        assertEquals(listOf(3), snapshot.externalDisplays.map { it.id })
        assertFalse(snapshot.clamshellRisk)
    }

    @Test
    fun `snapshot reports clamshell risk when notebook has no active builtin display`() {
        val facade = facade(
            isNotebook = true,
            displays = listOf(display(id = 3, builtin = false, active = true)),
        )

        val snapshot = facade.snapshot()

        assertTrue(snapshot.isNotebook)
        assertTrue(snapshot.clamshellRisk)
    }

    @Test
    fun `snapshot does not report clamshell risk on desktop`() {
        val facade = facade(
            isNotebook = false,
            displays = listOf(display(id = 3, builtin = false, active = true)),
        )

        val snapshot = facade.snapshot()

        assertFalse(snapshot.isNotebook)
        assertFalse(snapshot.clamshellRisk)
    }

    @Test
    fun `disable of builtin returns Blocked without touching transaction or state`() {
        val h = Harness(
            display(id = 1, uuid = "BUILTIN", builtin = true, active = true),
            display(id = 3, uuid = "EXT", active = true),
        )

        val result = h.facade.disableExternal(1)

        assertEquals(DisableResult.Blocked(DisableBlock.BUILTIN), result)
        assertTrue(h.runner.calls.isEmpty())
        assertTrue(h.repository.load().disabledByUs.isEmpty())
    }

    @Test
    fun `disable of last active real display returns Blocked`() {
        val h = Harness(display(id = 3, uuid = "EXT", active = true))

        val result = h.facade.disableExternal(3)

        assertEquals(DisableResult.Blocked(DisableBlock.LAST_ACTIVE_REAL), result)
        assertTrue(h.runner.calls.isEmpty())
    }

    @Test
    fun `disable of unknown id returns NotFound`() {
        val h = Harness(display(id = 3, uuid = "EXT", active = true))

        assertEquals(DisableResult.NotFound(99), h.facade.disableExternal(99))
    }

    @Test
    fun `disable success returns Disabled and persists managed state`() {
        val h = Harness(
            display(id = 1, uuid = "BUILTIN", builtin = true, active = true),
            display(id = 3, uuid = "EXT", active = true),
        ) { id, enabled, gw ->
            if (id == 3 && !enabled) gw.setOnline(3, online = false, active = false)
            0
        }

        val result = h.facade.disableExternal(3)

        val disabled = assertIs<DisableResult.Disabled>(result)
        assertEquals(3, disabled.handle.id)
        assertEquals(null, disabled.pendingRevert)
        assertEquals(listOf(3), h.repository.load().disabledByUs.map { it.id })
    }

    @Test
    fun `disable transaction failure returns Failed and rolls back state`() {
        val h = Harness(
            display(id = 1, uuid = "BUILTIN", builtin = true, active = true),
            display(id = 3, uuid = "EXT", active = true),
        ) { _, _, _ -> 1000 }

        val result = h.facade.disableExternal(3)

        assertIs<DisableResult.Failed>(result)
        assertTrue(h.repository.load().disabledByUs.isEmpty())
    }

    @Test
    fun `disable with auto revert returns pending revert handle`() {
        val h = Harness(
            display(id = 1, uuid = "BUILTIN", builtin = true, active = true),
            display(id = 3, uuid = "EXT", active = true),
        ) { id, enabled, gw ->
            if (id == 3 && !enabled) gw.setOnline(3, online = false, active = false)
            0
        }

        // revert longo: o teste só verifica o agendamento, e cancela antes de disparar
        val result = h.facade.disableExternalWithAutoRevert(3, autoRevertSeconds = 300)

        val disabled = assertIs<DisableResult.Disabled>(result)
        val pending = assertNotNull(disabled.pendingRevert)
        assertTrue(pending.deadlineMs > System.currentTimeMillis())
        assertTrue(h.facade.confirmDisable(disabled.handle), "auto-revert pendente deveria ser cancelável")
    }

    @Test
    fun `enable of managed display returns Enabled with proven online id`() {
        val h = Harness(
            display(id = 1, uuid = "BUILTIN", builtin = true, active = true),
            display(id = 3, uuid = "EXT", active = true),
        ) { id, enabled, gw ->
            gw.setOnline(id, online = enabled, active = enabled)
            0
        }
        assertIs<DisableResult.Disabled>(h.facade.disableExternal(3))
        val handle = h.facade.managedExternalDisplays().single()

        val result = h.facade.enableExternal(handle)

        val enabled = assertIs<EnableResult.Enabled>(result)
        assertEquals(3, enabled.onlineId)
        assertTrue(h.repository.load().disabledByUs.isEmpty(), "religado sai do estado desejado")
    }

    /** Gateway criado antes do runner para a ação da transação poder mutá-lo. */
    private class Harness(
        vararg displays: DisplayInfo,
        isNotebook: Boolean = true,
        action: (Int, Boolean, MutableDisplayGateway) -> Int = { _, _, _ -> 0 },
    ) {
        val gateway = MutableDisplayGateway(displays.toMutableList())
        val repository = InMemoryDisplayStateRepository()
        val runner = FakeTransactionRunner { id, enabled, _ -> action(id, enabled, gateway) }
        val facade = DefaultExternalDisplayToggleFacade(
            DisplayManager(
                stateRepository = repository,
                displayGateway = gateway,
                transactionRunner = runner,
                notebookDetector = { isNotebook },
                onLog = {},
            ),
        )
    }

    private fun facade(
        displays: List<DisplayInfo>,
        isNotebook: Boolean = true,
    ): DefaultExternalDisplayToggleFacade =
        Harness(*displays.toTypedArray(), isNotebook = isNotebook).facade
}
