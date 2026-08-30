package mdt.core.adapters

import mdt.core.domain.DisplayInfo
import mdt.core.Displays
import mdt.core.ports.DisplayGateway

object NativeDisplayGateway : DisplayGateway {
    override fun onlineIds(): List<Int> = Displays.onlineIds()

    override fun activeIds(): List<Int> = Displays.activeIds()

    override fun slsIds(): List<Int> = Displays.slsIds()

    override fun uuidOf(id: Int): String? = Displays.uuidOf(id)

    override fun findByUuidInSls(uuid: String): Int? = Displays.findByUuidInSls(uuid)

    override fun findBySerialInSls(vendor: Int, model: Int, serial: Int): Int? =
        Displays.findBySerialInSls(vendor, model, serial)

    override fun snapshot(): List<DisplayInfo> = Displays.snapshot()
}
