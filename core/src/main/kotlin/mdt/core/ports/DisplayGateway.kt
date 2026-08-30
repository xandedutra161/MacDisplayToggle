package mdt.core.ports

import mdt.core.domain.DisplayInfo

interface DisplayGateway {
    fun onlineIds(): List<Int>
    fun activeIds(): List<Int>
    fun slsIds(): List<Int>
    fun uuidOf(id: Int): String?
    fun findByUuidInSls(uuid: String): Int?
    fun findBySerialInSls(vendor: Int, model: Int, serial: Int): Int?
    fun snapshot(): List<DisplayInfo>
}
