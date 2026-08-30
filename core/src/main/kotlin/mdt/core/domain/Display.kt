package mdt.core.domain

import java.time.OffsetDateTime
import java.util.Locale

class DisplayError(message: String) : RuntimeException(message)

// Display placeholder criado pelo macOS quando o último display real some.
// Identidade fourcc observada na máquina real — conhecimento de domínio, não binding nativo.
const val PLACEHOLDER_VENDOR = 0x756E6B6E // 'unkn'
const val PLACEHOLDER_MODEL = 0x76697274 // 'virt'

/** Modelo de domínio: id, uuid, nome, builtin, ativo/desabilitado. */
data class DisplayInfo(
    val id: Int,
    val uuid: String?,
    val vendor: Int,
    val model: Int,
    val serial: Int,
    val builtin: Boolean,
    val active: Boolean,
    val online: Boolean,
    val inSls: Boolean,
) {
    val isPlaceholder: Boolean get() = vendor == PLACEHOLDER_VENDOR && model == PLACEHOLDER_MODEL
    val isDisabled: Boolean get() = inSls && !online
    val isActiveReal: Boolean get() = active && !isPlaceholder

    // Nome via decode PNP do vendor EDID
    val name: String
        get() = when {
            isPlaceholder -> "Placeholder do macOS"
            builtin -> "Tela embutida"
            else -> {
                val pnp = decodePnpVendor(vendor)
                val brand = PNP_BRANDS[pnp] ?: pnp
                if (brand != null) "$brand (%04X)".format(Locale.ROOT, model)
                else "Display %04X:%04X".format(Locale.ROOT, vendor, model)
            }
        }

    fun toSaved(): SavedDisplay =
        SavedDisplay(id, uuid, vendor, model, serial, builtin, savedAt = OffsetDateTime.now().withNano(0).toString())
}

/** Decodifica o vendor EDID (3 letras empacotadas em 5 bits cada, A=1). Ex.: 0x1E6D → "GSM". */
fun decodePnpVendor(v: Int): String? {
    if (v <= 0 || v > 0x7FFF) return null
    val c1 = (v shr 10) and 0x1F
    val c2 = (v shr 5) and 0x1F
    val c3 = v and 0x1F
    if (c1 !in 1..26 || c2 !in 1..26 || c3 !in 1..26) return null
    return "${'A' + (c1 - 1)}${'A' + (c2 - 1)}${'A' + (c3 - 1)}"
}

val PNP_BRANDS: Map<String, String> = mapOf(
    "GSM" to "LG", "APP" to "Apple", "SAM" to "Samsung", "DEL" to "Dell",
    "ACR" to "Acer", "AOC" to "AOC", "PHL" to "Philips", "BNQ" to "BenQ",
    "HPN" to "HP", "HWP" to "HP", "LEN" to "Lenovo", "VSC" to "ViewSonic",
    "AUS" to "ASUS", "GBT" to "Gigabyte", "MSI" to "MSI", "SNY" to "Sony",
    "XMI" to "Xiaomi", "HEC" to "Huawei",
)
