package mdt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import mdt.core.domain.PLACEHOLDER_MODEL
import mdt.core.domain.PLACEHOLDER_VENDOR
import mdt.core.testing.display

class DisplayPolicyTest {

    @Test
    fun `external online display is visible and operable`() {
        val external = display(id = 3, builtin = false, active = true, online = true)

        assertTrue(DisplayPolicy.isProductVisible(external))
        assertTrue(DisplayPolicy.isOperableExternal(external))
        assertEquals(null, DisplayPolicy.disableBlock(listOf(display(id = 1, builtin = true), external), external))
    }

    @Test
    fun `builtin display is visible for diagnostics but not operable`() {
        val builtin = display(id = 1, builtin = true)

        assertTrue(DisplayPolicy.isProductVisible(builtin))
        assertFalse(DisplayPolicy.isOperableExternal(builtin))
        assertEquals(DisableBlock.BUILTIN, DisplayPolicy.disableBlock(listOf(builtin, display(id = 3)), builtin))
    }

    @Test
    fun `macOS placeholder is neither visible nor operable`() {
        val placeholder = display(
            id = 9,
            vendor = PLACEHOLDER_VENDOR,
            model = PLACEHOLDER_MODEL,
            active = true,
            online = true,
        )

        assertFalse(DisplayPolicy.isProductVisible(placeholder))
        assertFalse(DisplayPolicy.isOperableExternal(placeholder))
        assertEquals(DisableBlock.PLACEHOLDER, DisplayPolicy.disableBlock(listOf(placeholder), placeholder))
    }

    @Test
    fun `identityless disabled SLS entry is hidden from product surface`() {
        val ghost = display(
            id = 2,
            uuid = null,
            vendor = 0,
            model = 0,
            serial = 0,
            active = false,
            online = false,
            inSls = true,
        )

        assertFalse(DisplayPolicy.isProductVisible(ghost))
        assertFalse(DisplayPolicy.isOperableExternal(ghost))
    }

    @Test
    fun `last active real display is blocked`() {
        val external = display(id = 3, builtin = false, active = true, online = true)
        val inactiveBuiltin = display(id = 1, builtin = true, active = false, online = true)

        assertEquals(1, DisplayPolicy.activeRealCount(listOf(external, inactiveBuiltin)))
        assertEquals(
            DisableBlock.LAST_ACTIVE_REAL,
            DisplayPolicy.disableBlock(listOf(external, inactiveBuiltin), external),
        )
    }

    @Test
    fun `external active display is allowed when another real display remains`() {
        val builtin = display(id = 1, builtin = true, active = true, online = true)
        val external = display(id = 3, builtin = false, active = true, online = true)

        assertEquals(2, DisplayPolicy.activeRealCount(listOf(builtin, external)))
        assertEquals(1, DisplayPolicy.remainingActiveRealAfterDisable(listOf(builtin, external), external))
        assertEquals(null, DisplayPolicy.disableBlock(listOf(builtin, external), external))
    }

    @Test
    fun `already disabled external display is blocked as no-op`() {
        val disabled = display(id = 3, builtin = false, active = false, online = false, inSls = true)

        assertTrue(DisplayPolicy.isProductVisible(disabled))
        assertTrue(DisplayPolicy.isOperableExternal(disabled))
        assertEquals(DisableBlock.ALREADY_DISABLED, DisplayPolicy.disableBlock(listOf(disabled), disabled))
    }

}
