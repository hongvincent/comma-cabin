package app.commacabin.probe

val DRIVER = AreaId("ROW_1_LEFT")
val PASSENGER = AreaId("ROW_1_RIGHT")

class FakeScheduler : Scheduler {
    var now = 0L
    private class Task(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)
    private val tasks = mutableListOf<Task>()

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val entry = Task(now + delayMs, task)
        tasks += entry
        return Cancellable { entry.cancelled = true }
    }

    fun advance(ms: Long) {
        now += ms
        tasks.filter { !it.cancelled && it.at <= now }.forEach {
            it.cancelled = true
            it.run()
        }
    }
}

class FakeStore : RecoveryStore {
    var stored: RecoveryRecord? = null
    var unreadable: String? = null
    /** Return true to make that save fail. */
    var failSaveWhen: (RecoveryRecord) -> Boolean = { false }
    var failDelete = false
    var saves = 0
    var deletes = 0

    override fun load(): LoadResult = when {
        unreadable != null -> LoadResult.Unreadable(unreadable!!)
        stored != null -> LoadResult.Found(stored!!)
        else -> LoadResult.Empty
    }

    override fun save(record: RecoveryRecord): Boolean {
        if (failSaveWhen(record)) return false
        saves++
        stored = record
        return true
    }

    override fun delete(): Boolean {
        if (failDelete) return false
        deletes++
        stored = null
        unreadable = null
        return true
    }
}

/** Simulated vehicle light. [value] is the real vehicle value that reads return. */
class FakeLight : LightPort {
    var capability = BrightnessCapability(Access.READ_WRITE, mapOf(PASSENGER to 0..100, DRIVER to 0..100))
    var capabilityError: ProbeError? = null
    var switch: SwitchState? = SwitchState.ON
    var value = 80
    var readError: ProbeError? = null

    /** When false, setBrightness keeps its callbacks in [heldResponses] (no answer yet). */
    var respond = true
    /** When false, a set does not change [value]. */
    var applyOnSet = true
    var setError: ProbeError? = null
    var beforeSet: (Int) -> Unit = {}

    /** When true, every read (capability, switch, brightness) waits in [heldReads] until released. */
    var holdReads = false
    val heldReads = mutableListOf<() -> Unit>()

    val sets = mutableListOf<Int>()
    val heldResponses = mutableListOf<Pair<(Int?) -> Unit, (ProbeError) -> Unit>>()
    var observer: ((Int?) -> Unit)? = null
    var capabilityCalls = 0

    private fun answer(read: () -> Unit) {
        if (holdReads) heldReads += read else read()
    }

    override fun checkBrightnessCapabilities(onResult: (BrightnessCapability) -> Unit, onError: (ProbeError) -> Unit) {
        capabilityCalls++
        val error = capabilityError
        val cap = capability
        answer { if (error != null) onError(error) else onResult(cap) }
    }

    override fun getSwitch(area: AreaId, onResult: (SwitchState?) -> Unit, onError: (ProbeError) -> Unit) {
        val sw = switch
        answer { onResult(sw) }
    }

    /** Answers with the value at request time, even when the answer is delivered later. */
    override fun getBrightness(area: AreaId, onResult: (Int?) -> Unit, onError: (ProbeError) -> Unit) {
        val error = readError
        val snapshot = value
        answer { if (error != null) onError(error) else onResult(snapshot) }
    }

    /** When true, a set waits in [queued] (the SDK lane) until [runQueued]; its gate runs then. */
    var holdDispatch = false
    val queued = mutableListOf<() -> Unit>()

    fun runQueued() = queued.toList().also { queued.clear() }.forEach { it() }

    override fun setBrightness(area: AreaId, value: Int, dispatch: Dispatch, onComplete: (Int?) -> Unit, onError: (ProbeError) -> Unit) {
        val run = { if (dispatch.now()) set(value, onComplete, onError) }
        if (holdDispatch) queued += run else run()
    }

    private fun set(value: Int, onComplete: (Int?) -> Unit, onError: (ProbeError) -> Unit) {
        beforeSet(value)
        sets += value
        if (!respond) {
            heldResponses += onComplete to onError
            return
        }
        val error = setError
        if (error != null) {
            onError(error)
            return
        }
        if (applyOnSet) this.value = value
        onComplete(value)
    }

    override fun observeBrightness(area: AreaId, onListening: (ProbeError?) -> Unit, onUpdate: (Int?) -> Unit): AutoCloseable {
        observer = onUpdate
        onListening(null)
        return AutoCloseable { observer = null }
    }

    fun releaseReads() {
        while (heldReads.isNotEmpty()) heldReads.removeAt(0).invoke()
    }
}

val SDK_ERROR = ProbeError("ai.pleos.playground.vehicle.callback.exception.a", "ERROR_INTERNAL_SERVICE", "ERROR_INTERNAL_SERVICE")
