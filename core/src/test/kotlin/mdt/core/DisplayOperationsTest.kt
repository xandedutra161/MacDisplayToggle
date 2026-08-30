package mdt.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import mdt.core.jna.kCGConfigurePermanently
import mdt.core.testing.FakeTransactionRunner
import mdt.core.testing.MutableDisplayGateway
import mdt.core.testing.TransactionCall
import mdt.core.testing.display
import mdt.core.testing.saved

class DisplayOperationsTest {

    @Test
    fun `matchOnline resolves by uuid even when display id changed`() {
        val gateway = MutableDisplayGateway(
            mutableListOf(display(id = 44, uuid = "SAVED-UUID")),
        )
        val operations = DisplayOperations(gateway, FakeTransactionRunner())

        val match = operations.matchOnline(saved(id = 3, uuid = "SAVED-UUID"))

        assertEquals(44, match)
    }

    @Test
    fun `matchOnline does not trust stale id when uuid proves mismatch`() {
        val gateway = MutableDisplayGateway(
            mutableListOf(display(id = 3, uuid = "OTHER-UUID")),
        )
        val operations = DisplayOperations(gateway, FakeTransactionRunner())

        val match = operations.matchOnline(saved(id = 3, uuid = "SAVED-UUID"))

        assertNull(match)
    }

    @Test
    fun `matchOnline accepts saved id when current uuid is unavailable`() {
        val gateway = MutableDisplayGateway(
            mutableListOf(display(id = 3, uuid = null)),
        )
        val operations = DisplayOperations(gateway, FakeTransactionRunner())

        val match = operations.matchOnline(saved(id = 3, uuid = "SAVED-UUID"))

        assertEquals(3, match)
    }

    @Test
    fun `enableVerified resolves disabled display by serial fallback`() {
        val saved = saved(id = 3, uuid = "SAVED-UUID", vendor = 0x1E6D, model = 0x5C0C, serial = 777)
        val gateway = MutableDisplayGateway(
            mutableListOf(display(id = 44, uuid = null, vendor = 0x1E6D, model = 0x5C0C, serial = 777, active = false, online = false)),
        )
        val runner = FakeTransactionRunner { id, enabled, _ ->
            if (id == 44 && enabled) {
                gateway.setOnline(id, online = true, active = true)
                gateway.setUuid(id, "SAVED-UUID")
            }
            0
        }
        val operations = DisplayOperations(gateway, runner) {}

        val match = operations.enableVerified(saved, attempts = 1, windowMs = 10)

        assertEquals(44, match)
        assertEquals(listOf(TransactionCall(44, true, kCGConfigurePermanently)), runner.calls)
    }
}
