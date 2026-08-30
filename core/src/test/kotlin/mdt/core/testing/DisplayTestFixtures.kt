package mdt.core.testing

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import mdt.core.domain.DisplayInfo
import mdt.core.domain.DisplayState
import mdt.core.domain.SavedDisplay
import mdt.core.ports.DisplayEventListener
import mdt.core.ports.DisplayEventSource
import mdt.core.ports.DisplayGateway
import mdt.core.ports.DisplayStateRepository
import mdt.core.ports.TransactionRunner

data class TransactionCall(
    val displayId: Int,
    val enabled: Boolean,
    val flag: Int,
)

class FakeTransactionRunner(
    private val action: (displayId: Int, enabled: Boolean, flag: Int) -> Int = { _, _, _ -> 0 },
) : TransactionRunner {
    val calls = mutableListOf<TransactionCall>()

    override val inFlight: Boolean = false

    override fun fireIfIdle(displayId: Int, enabled: Boolean, flag: Int): CompletableFuture<Int>? {
        calls += TransactionCall(displayId, enabled, flag)
        return CompletableFuture.completedFuture(action(displayId, enabled, flag))
    }
}

class InMemoryDisplayStateRepository(
    private var state: DisplayState = DisplayState(),
) : DisplayStateRepository {
    override val path: Path = Path.of("memory")

    override fun load(): DisplayState = state

    override fun save(state: DisplayState) {
        this.state = state
    }

    override fun remember(display: SavedDisplay) {
        save(state.copy(disabledByUs = state.disabledByUs.filterNot { it.matches(display) } + display))
    }

    override fun forget(display: SavedDisplay) {
        save(state.copy(disabledByUs = state.disabledByUs.filterNot { it.matches(display) }))
    }
}

class MutableDisplayGateway(
    val displays: MutableList<DisplayInfo> = mutableListOf(),
    private val serialMatches: MutableMap<Triple<Int, Int, Int>, Int> = mutableMapOf(),
) : DisplayGateway {
    @Volatile
    var snapshotCalls: Int = 0
        private set

    override fun onlineIds(): List<Int> = displays.filter { it.online }.map { it.id }

    override fun activeIds(): List<Int> = displays.filter { it.active }.map { it.id }

    override fun slsIds(): List<Int> = displays.filter { it.inSls }.map { it.id }

    override fun uuidOf(id: Int): String? = displays.firstOrNull { it.id == id }?.uuid

    override fun findByUuidInSls(uuid: String): Int? =
        displays.firstOrNull { it.inSls && it.uuid == uuid }?.id

    override fun findBySerialInSls(vendor: Int, model: Int, serial: Int): Int? =
        serialMatches[Triple(vendor, model, serial)]
            ?: displays.firstOrNull {
                it.inSls && it.vendor == vendor && it.model == model && it.serial == serial
            }?.id

    override fun snapshot(): List<DisplayInfo> {
        snapshotCalls++
        return displays
    }

    fun setOnline(id: Int, online: Boolean, active: Boolean) {
        val index = displays.indexOfFirst { it.id == id }
        displays[index] = displays[index].copy(online = online, active = active)
    }

    fun setUuid(id: Int, uuid: String?) {
        val index = displays.indexOfFirst { it.id == id }
        displays[index] = displays[index].copy(uuid = uuid)
    }

    fun addSerialMatch(vendor: Int, model: Int, serial: Int, displayId: Int) {
        serialMatches[Triple(vendor, model, serial)] = displayId
    }
}

class FakeDisplayEventSource(
    var registerResult: Boolean = true,
) : DisplayEventSource {
    @Volatile
    var listener: DisplayEventListener? = null

    @Volatile
    var unregistered = false

    @Volatile
    var deliveryStopped = false

    override fun register(listener: DisplayEventListener): Boolean {
        if (!registerResult) return false
        this.listener = listener
        return true
    }

    override fun unregister() {
        unregistered = true
        listener = null
    }

    override fun deliverBlocking(): Long = 0 // simula JVM sem fontes de runloop

    override fun stopDelivering() {
        deliveryStopped = true
    }

    fun emit(displayId: Int, flags: Int = 0) {
        listener?.onDisplayEvent(displayId, flags)
    }
}

fun display(
    id: Int,
    uuid: String? = "UUID-$id",
    vendor: Int = 0x1E6D,
    model: Int = 0x5C0C,
    serial: Int = id,
    builtin: Boolean = false,
    active: Boolean = true,
    online: Boolean = true,
    inSls: Boolean = true,
): DisplayInfo =
    DisplayInfo(
        id = id,
        uuid = uuid,
        vendor = vendor,
        model = model,
        serial = serial,
        builtin = builtin,
        active = active,
        online = online,
        inSls = inSls,
    )

fun saved(
    id: Int,
    uuid: String?,
    vendor: Int = 0x1E6D,
    model: Int = 0x5C0C,
    serial: Int = id,
    builtin: Boolean = false,
): SavedDisplay =
    SavedDisplay(
        id = id,
        uuid = uuid,
        vendor = vendor,
        model = model,
        serial = serial,
        builtin = builtin,
        savedAt = "2026-08-29T00:00:00-04:00",
    )
