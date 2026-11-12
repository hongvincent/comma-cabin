package app.commacabin.climate

import app.commacabin.probe.Access
import app.commacabin.probe.Cancellable
import app.commacabin.probe.ParkingState
import app.commacabin.probe.ProbeError
import app.commacabin.probe.RecordLease
import app.commacabin.probe.Scheduler
import java.util.UUID
import kotlin.math.max

const val CLIMATE_READ_TIMEOUT_MS = 5_000L
const val CLIMATE_WRITE_TIMEOUT_MS = 5_000L
const val CLIMATE_SETTLE_MS = 500L

enum class ClimateResult { WORKING, APPLIED, SKIPPED, UNKNOWN, RESTORED, HELD, EXTERNAL, FAILED, LOCKED }

/**
 * A line of the product screens. [prop] null = the group's auto mode. No ids or SDK names.
 * [checked]: what a read-only check of an open record found on the vehicle, in words (display only).
 */
data class ClimateItemUi(val prop: ClimateProp?, val area: String, val original: String?, val result: ClimateResult, val text: String, val checked: String? = null)

data class TempRange(val min: Float, val max: Float, val increment: Float)

/** The choices of one rest start: [바람 줄이기], [운전석 온도 맞추기] and the chosen °C. */
data class ClimateChoice(val fan: Boolean, val temp: Boolean, val tempC: Float)

data class ClimateUi(
    val restLine: String? = null,
    val items: List<ClimateItemUi> = emptyList(),
    val open: List<ClimateItemUi> = emptyList(),
    val earlier: List<ClimateItemUi> = emptyList(),
    val restoreBlock: String? = null,
    val discardBlock: String? = null,
    val deferred: Boolean = false,
    val problem: String? = null,
    val working: Boolean = false,
    val notice: String? = null,
    /** The driver-only temperature range and step read from the vehicle (read-only), for the ready screen. */
    val tempRange: TempRange? = null,
    val tempRangeLoading: Boolean = false,
    val tempRangeProblem: String? = null,
    /** A read-only check of the open records is under way. */
    val checking: Boolean = false,
)

/**
 * Product climate of a rest: the fan one step down on every supported fan area and the driver target
 * temperature (driver-only area) set to the chosen value, then back when the rest ends.
 *
 * Fan and temperature that share one auto (and power) area form a group. The original auto state is saved once,
 * before the group's first change. Requests never overlap on a shared resource: a group whose auto or power area
 * overlaps another group with an open or unknown request waits. The shared states (power, auto, Sync/Dual) are
 * read around every command; one that moves across the app's own command, or cannot be read, has no knowable
 * source, so the group becomes RESULT_UNKNOWN; one that moves between the app's commands is a change from the
 * vehicle side (EXTERNAL_CHANGE). Either stops the group's further changes and its automatic restore. The app
 * never turns power or Sync on or off; it writes auto only on [원래대로], to the saved original, at most once.
 * A record goes only when, after the last write, every member and the auto state read back as the originals.
 * Lives for the process like the lighting. Main thread only.
 */
class RestClimate(
    private val port: ClimatePort,
    private val store: ClimateStore,
    private val scheduler: Scheduler,
    private val parking: () -> ParkingState,
    /** Debug write tools busy or holding records (also damaged ones). */
    private val debugBlock: () -> String?,
    private val lease: RecordLease,
    private val log: (String) -> Unit = {},
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    var state = ClimateUi()
        private set
    var onState: (ClimateUi) -> Unit = {}

    private class Run(val id: Int, var line: String?, var ending: Boolean = false)
    private class Item(
        val prop: ClimateProp, val area: String, val bounds: ClosedFloatingPointRange<Float>?, val increment: Float?, val wanted: Float?,
        val autoArea: String?, val powerArea: String?, val dualArea: String?, val skip: String?,
    )
    private class Pending(val requestId: String, val timer: Cancellable, var timedOut: Boolean = false)
    /** One restore of one group. [base]: the shared states read at its start; any later move stops further writes. */
    private class Job(val groupId: String, val manual: Boolean) {
        var base: Map<Side, Boolean> = emptyMap()
        var stage: GroupStage = GroupStage.ACTIVE
    }

    private var leaseId = 0
    private var closed = false
    private val alive: Boolean get() = !closed && lease.isHeld(leaseId)

    private var groups: List<ClimateGroup> = emptyList()
    private var loadProblem: String? = null
    private var storageFault: String? = null
    /** Request key ("group|FAN|area", "group|AUTO") → the open request. */
    private val pending = mutableMapOf<String, Pending>()
    private val confirming = mutableSetOf<String>()
    /** Shared states read right before each command. */
    private val preShared = mutableMapOf<String, Map<Side, Boolean?>>()
    private val jobRun = mutableMapOf<String, Int>()
    /** Groups to restore automatically once nothing of theirs (or of an overlapping group) is open. */
    private val restoreAfterAnswer = mutableSetOf<String>()
    private val applyWaiters = mutableMapOf<String, () -> Unit>()
    private val restoreWaiters = mutableMapOf<String, () -> Unit>()
    private val observers = mutableMapOf<String, AutoCloseable>()
    private val checking = mutableMapOf<String, Boolean>()
    private val runItems = linkedMapOf<String, ClimateItemUi>()
    private val earlierItems = linkedMapOf<String, ClimateItemUi>()
    private val heldWhy = mutableMapOf<String, String>()
    private val parkLost = mutableSetOf<String>()
    private val restoreQueue = ArrayDeque<Job>()
    private var restoring: Job? = null
    private var run: Run? = null
    private var runSeq = 0
    private var opSeq = 0
    private var preparing = false
    private var deferred = false
    private var notice: String? = null
    private var tempRange: TempRange? = null
    private var tempRangeLoading = false
    private var tempRangeProblem: String? = null

    /** A read-only check's finding for one line, valid only while the record is still [stamp] (same requests and stages). */
    private class Checked(val stamp: String, val text: String)
    /** Member key or "group|AUTO" → finding. Memory only, never a record and never a command. */
    private val checked = mutableMapOf<String, Checked>()
    private var checkSeq = 0
    private var checkingRecords = false
    /** The screen (Activity) that started the check: only its own hiding stops it, never an older screen's late onStop. */
    private var checkOwner: Any? = null

    fun start() {
        if (closed || leaseId != 0) return
        leaseId = lease.acquire()
        when (val loaded = store.load()) {
            ClimateLoad.Empty -> log("공조 기록 없음")
            is ClimateLoad.Found -> {
                // A request that never answered in an earlier run can never be proven finished: the group stays locked.
                val marked = loaded.groups.map { if (it.locked) it.copy(priorRequestUnresolved = true) else it }
                groups = loaded.groups
                if (marked != loaded.groups) persist(marked)
                groups = marked
                marked.forEach { log("공조 기록 불러옴: ${describe(it)}") }
            }
            is ClimateLoad.Unreadable -> {
                loadProblem = loaded.reason
                log("공조 기록 읽기 실패: ${loaded.reason}. 파일을 그대로 두고 공조를 막아요")
            }
        }
        publish()
    }

    fun close() {
        if (closed) return
        closed = true
        dropCheck()
        pending.values.forEach { it.timer.cancel() }
        observers.values.forEach { runCatching { it.close() } }
        observers.clear()
    }

    /** Read-only: the driver-only temperature range and step for the ready screen. Nothing is written. */
    fun loadTempRange() {
        if (!alive || tempRangeLoading) return
        tempRangeLoading = true
        publish()
        withTimeout({ ok, err -> port.capability(ClimateProp.TEMP, { a, m, inc -> ok(Triple(a, m, inc)) }, err) }) { cap, e ->
            tempRangeLoading = false
            if (!alive) return@withTimeout
            val bounds = cap?.second?.get(DRIVER_AREA)
            val inc = cap?.third
            tempRangeProblem = when {
                cap == null -> userText("운전석 온도 범위를 확인하지 못했어요", e)
                cap.first != Access.READ_WRITE -> "이 차에서는 목표 온도를 바꿀 수 없어요"
                bounds == null -> "운전석만 따로 맞출 수 있는 온도 영역이 없어요"
                inc == null -> "온도 간격을 확인할 수 없어요"
                else -> null
            }
            if (tempRangeProblem == null) tempRange = TempRange(bounds!!.start, bounds.endInclusive, inc!!)
            log("준비 화면 온도 범위: ${tempRange ?: tempRangeProblem}")
            publish()
        }
    }

    /** Losing parking cancels the preparation and remembers every open group: no new restore until [원래대로]. */
    fun onParking(next: ParkingState) {
        if (!next.parked) parkingLost("주차 조건 상실(${next.gear}, 주행 제한 ${next.uxRestricted})")
        publish()
    }

    private fun parkingLost(why: String) {
        if (preparing) {
            preparing = false
            opSeq++
            log("$why: 공조 적용 준비 취소")
        }
        val open = pending.keys.map { it.substringBefore('|') } + confirming.map { it.substringBefore('|') } + restoreAfterAnswer +
            restoreQueue.map { it.groupId } + listOfNotNull(restoring?.groupId) + jobRun.filterValues { it == run?.id }.keys.filter { group(it) != null }
        if (open.isNotEmpty() && parkLost.addAll(open)) log("$why: 공조 그룹 ${open.toSet().size}개는 [원래대로] 전까지 새로 되돌리지 않음")
    }

    /** The debug tools' work, records or errors changed: recompute the shown blocks only. No vehicle read or write, no restore, no file change. */
    fun onDebugChanged() {
        if (alive) publish()
    }

    /**
     * Read-only: every open group's fan and driver temperature and its power, auto and Sync states, shown next to
     * the originals. One read each, no polling. Nothing is sent, no record changes, no lock opens, no restore
     * starts. Skipped without records or while climate work is under way; a late answer after a change is dropped.
     */
    fun checkRecords(owner: Any) {
        if (!alive || groups.isEmpty() || working()) return
        if (checkingRecords && owner === checkOwner) return // the same screen asked again
        dropCheck() // another screen's check under way is replaced: the newer screen owns the check
        val seq = ++checkSeq
        checkOwner = owner
        checkingRecords = true
        val todo = groups.toList()
        log("공조 기록 확인(조회만): 그룹 ${todo.size}개")
        publish()
        fun current() = seq == checkSeq && alive
        /** The group is still exactly as it was when the check began (not deleted, same requests and stages). */
        fun unchanged(g: ClimateGroup) = group(g.groupId)?.let { autoStamp(it) == autoStamp(g) } == true
        fun next(i: Int) {
            if (!current()) return
            if (i == todo.size) {
                checkingRecords = false
                return publish()
            }
            val g = todo[i]
            if (!unchanged(g)) return next(i + 1)
            fun member(j: Int) {
                if (!current()) return
                if (!unchanged(g)) return next(i + 1)
                if (j == g.members.size) {
                    return readShared(g.powerArea, g.autoArea, g.dualArea, guard = ::current) { shared ->
                        if (unchanged(g)) checked["${g.groupId}|AUTO"] = Checked(autoStamp(g), sharedText(g, shared))
                        publish()
                        next(i + 1)
                    }
                }
                val m = g.members[j]
                readOrNull(m.prop, m.area) { v, e ->
                    if (!current()) return@readOrNull
                    if (unchanged(g)) checked[key(g.groupId, m.prop, m.area)] = Checked(memberStamp(g, m), checkedText(m, v, e))
                    publish()
                    member(j + 1)
                }
            }
            member(0)
        }
        next(0)
    }

    /** [owner]'s screen is hidden: answers of its check still under way are dropped. Another screen's check goes on. */
    fun stopCheck(owner: Any) {
        if (owner !== checkOwner) return
        if (checkingRecords) log("화면을 벗어나 공조 기록 확인을 멈춤")
        dropCheck()
        if (alive) publish()
    }

    /** Read timers are left to run out; their answers carry an old [checkSeq] and are dropped. */
    private fun dropCheck() {
        checkSeq++
        checkingRecords = false
        checkOwner = null
        checked.clear()
    }

    private fun memberStamp(g: ClimateGroup, m: ClimateMember) = "${g.stage}|${g.locked}|${m.requestId}|${m.stage}|${m.responseReceived}"

    /** The whole group: its stage and locks, the auto request and every member's request, stage and answer. */
    private fun autoStamp(g: ClimateGroup) =
        "${g.stage}|${g.locked}|${g.priorRequestUnresolved}|${g.autoRequestId}|${g.autoResponseReceived}|${g.members.joinToString { "${it.requestId}|${it.stage}|${it.responseReceived}" }}"

    /** What was read, compared with the record's own values. Never called "now": the vehicle may change after the read. */
    private fun checkedText(m: ClimateMember, v: Float?, error: String?): String {
        if (v == null) return userText("차량 설정을 확인하지 못했어요", error)
        return "확인한 설정 ${ClimateValues.show(m.prop, v)}${unit(m.prop)} · " + when {
            ClimateValues.same(m.prop, v, m.original) -> "원래와 같아요"
            ClimateValues.same(m.prop, v, m.target) -> "휴식 목표와 같아요"
            else -> "원래와 달라요"
        }
    }

    private fun sharedText(g: ClimateGroup, shared: Map<Side, Boolean?>): String {
        if (shared.values.all { it == null }) return "공조 상태를 확인하지 못했어요"
        fun state(v: Boolean?) = v?.let { onOff(it) } ?: "확인하지 못함"
        val auto = shared[Side.AUTO]
        return (
            listOf(
                "확인한 자동 모드 ${state(auto)}" + when (auto) {
                    null -> ""
                    g.autoOriginal -> "(휴식 전과 같아요)"
                    else -> "(휴식 전과 달라요)"
                },
                "공조 전원 ${state(shared[Side.POWER])}",
            ) + listOfNotNull(g.dualArea?.let { "연동(Sync) ${state(shared[Side.DUAL])}" })
            ).joinToString(" · ")
    }

    private fun working() = preparing || restoring != null || restoreQueue.isNotEmpty() || pending.isNotEmpty() || confirming.isNotEmpty()

    /** For the debug tools. */
    fun busyReason(): String? = when {
        !alive -> null
        loadProblem != null -> "휴식 공조 기록을 읽을 수 없어요"
        preparing || pending.isNotEmpty() || confirming.isNotEmpty() || restoring != null || restoreQueue.isNotEmpty() -> "휴식 공조를 맞추거나 되돌리는 중이에요"
        groups.isNotEmpty() -> "정리하지 않은 휴식 공조 기록이 있어요"
        else -> null
    }

    /** A rest really started. Returns this owner's run id (process-unique). An open run is ended first. */
    fun onRestStart(choice: ClimateChoice): Int {
        run?.takeIf { !it.ending }?.let { onRestEnd(it.id, "새 휴식 시작", parkingLost = !parking().parked) }
        val id = ++runSeq
        runItems.clear()
        notice = null
        dropCheck()
        val r = Run(id, line = null)
        run = r
        if (!choice.fan) mark(ClimateProp.FAN, "", null, ClimateResult.SKIPPED, "바람 줄이기를 꺼 두었어요")
        if (!choice.temp) mark(ClimateProp.TEMP, "", null, ClimateResult.SKIPPED, "운전석 온도 맞추기를 꺼 두었어요")
        if (!choice.fan && !choice.temp) {
            r.line = "공조 조정을 꺼 두었어요"
            log("run $id: 공조 조정 모두 꺼짐(조회·쓰기 0)")
            return id.also { publish() }
        }
        applyBlock()?.let {
            r.line = it
            log("run $id: $it")
            return id.also { publish() }
        }
        preparing = true
        val token = ++opSeq
        log("run $id: 공조 적용 준비(바람 ${choice.fan}, 온도 ${choice.temp} ${ClimateValues.show(ClimateProp.TEMP, choice.tempC)})")
        publish()
        prepare(token, id, choice)
        return id
    }

    /** The rest of [runId] ended (after its sound stopped): stop preparing, finish what was sent, then restore. */
    fun onRestEnd(runId: Int, reason: String, parkingLost: Boolean = false) {
        val r = run ?: return
        if (r.id != runId || r.ending) return
        r.ending = true
        if (preparing) {
            preparing = false
            opSeq++
        }
        if (parkingLost) parkingLost("휴식 종료 원인 $reason")
        deferred = false
        val mine = jobRun.filterValues { it == runId }.keys.filter { group(it) != null }
        log("run $runId 종료($reason): 이번 휴식의 공조 그룹 ${mine.size}개 복원")
        mine.filter { blockedByOpen(it) }.forEach { restoreAfterAnswer += it }
        enqueue(mine.filter { !blockedByOpen(it) }, manual = false)
        publish()
    }

    /** [원래대로]: every unlocked group back to the original fan, temperature and auto, with the checks before each command. */
    fun restoreRecords() {
        if (!alive) return
        restoreBlock()?.let { return say(it) }
        deferred = false
        earlierItems.clear()
        val jobs = groups.filter { !it.locked && !blockedByOpen(it.groupId) }.map { it.groupId }
        parkLost -= jobs.toSet()
        log("[원래대로] 공조 그룹 ${jobs.size}개")
        enqueue(jobs, manual = true)
        publish()
    }

    /** [기록 지우기] after the screen's confirmation. The vehicle is not touched. Locked groups stay. */
    fun discardRecords() {
        if (!alive) return
        discardBlock()?.let { return say(it) }
        val keep = groups.filter { it.locked }
        val gone = groups - keep.toSet()
        if (!persist(keep)) return say("기록을 지우지 못했어요")
        earlierItems.clear()
        dropCheck()
        gone.forEach { g -> g.members.forEach { closeObserver(key(g.groupId, it.prop, it.area)) } }
        log("사용자가 공조 기록 ${gone.size}개를 지움(차량은 그대로)")
        say(if (keep.isEmpty()) "기록을 지웠어요. 공조는 지금 상태 그대로예요" else "지울 수 있는 기록만 지웠어요")
    }

    fun defer() {
        deferred = true
        publish()
    }

    /** Display only, no read or write. */
    fun showRecords() {
        deferred = false
        publish()
    }

    // --- apply ---

    private fun applyBlock(): String? = when {
        !alive -> "공조를 조정할 수 없어요"
        loadProblem != null -> "공조 기록을 읽을 수 없어 이번에는 공조를 바꾸지 않아요"
        storageFault != null -> "공조 기록을 저장할 수 없어 이번에는 공조를 바꾸지 않아요"
        else -> debugBlock()?.let { "개발 진단 작업이나 기록이 남아 있어 공조를 바꾸지 않아요" }
    }

    private fun current(token: Int, runId: Int) = alive && token == opSeq && run?.id == runId && run?.ending == false

    private fun prepare(token: Int, runId: Int, choice: ClimateChoice) {
        val caps = mutableMapOf<ClimateProp, Triple<Access, Map<String, ClosedFloatingPointRange<Float>>, Float?>?>()
        val capErr = mutableMapOf<ClimateProp, String?>()
        val sides = mutableMapOf<Side, Pair<Access, List<String>>?>()
        fun sidesStep(i: Int) {
            if (i == Side.entries.size) return next(token, runId, items(choice, caps, capErr, sides), 0)
            val side = Side.entries[i]
            askOrNull(token, runId, "${side.name} 영역", { ok, err -> port.sideAreas(side, { a, l -> ok(a to l) }, err) }) { v, _ ->
                sides[side] = v
                sidesStep(i + 1)
            }
        }
        fun capStep(props: List<ClimateProp>, i: Int) {
            if (i == props.size) return sidesStep(0)
            val prop = props[i]
            askOrNull(token, runId, "${prop.label} 지원", { ok, err -> port.capability(prop, { a, m, inc -> ok(Triple(a, m, inc)) }, err) }) { v, e ->
                caps[prop] = v
                capErr[prop] = e
                capStep(props, i + 1)
            }
        }
        capStep(listOfNotNull(ClimateProp.FAN.takeIf { choice.fan }, ClimateProp.TEMP.takeIf { choice.temp }), 0)
    }

    private fun items(
        choice: ClimateChoice,
        caps: Map<ClimateProp, Triple<Access, Map<String, ClosedFloatingPointRange<Float>>, Float?>?>,
        capErr: Map<ClimateProp, String?>,
        sides: Map<Side, Pair<Access, List<String>>?>,
    ): List<Item> {
        fun areas(side: Side) = sides[side]?.takeIf { it.first == Access.READ || it.first == Access.READ_WRITE }?.second
        fun containing(area: String, list: List<String>?): String? = list?.filter { seats(it).containsAll(seats(area)) }?.singleOrNull()
        val power = areas(Side.POWER)
        val auto = areas(Side.AUTO)
        val dual = areas(Side.DUAL)
        val skips = mutableListOf<Item>()
        val members = mutableListOf<Item>()
        fun skipped(prop: ClimateProp, why: String) = Item(prop, "", null, null, null, null, null, null, why)
        fun mapped(prop: ClimateProp, area: String, bounds: ClosedFloatingPointRange<Float>, inc: Float?, wanted: Float?): Item {
            val autoArea = containing(area, auto)
            val powerArea = containing(area, power)
            val dualArea = containing(area, dual)
            val skip = when {
                power == null || auto == null -> "공조 상태(전원·자동 모드)를 확인할 수 없어 건너뛰었어요"
                autoArea == null || powerArea == null -> "공조 영역을 정확히 알 수 없어 건너뛰었어요"
                prop == ClimateProp.TEMP && (dual == null || dualArea == null) -> "공조 연동(Sync) 상태를 확인할 수 없어 운전석 온도를 바꾸지 않았어요"
                groups.any { it.autoArea == autoArea } -> "정리하지 않은 이전 공조 기록이 있어 이번에는 바꾸지 않았어요"
                else -> null
            }
            return Item(prop, area, bounds, inc, wanted, autoArea, powerArea, dualArea, skip)
        }
        if (choice.fan) {
            val cap = caps[ClimateProp.FAN]
            when {
                cap == null -> skips += skipped(ClimateProp.FAN, userText("바람 세기 지원을 확인하지 못해 건너뛰었어요", capErr[ClimateProp.FAN]))
                cap.first != Access.READ_WRITE -> skips += skipped(ClimateProp.FAN, "이 차에서는 바람 세기를 바꿀 수 없어 건너뛰었어요")
                cap.second.isEmpty() -> skips += skipped(ClimateProp.FAN, "이 차는 바람 세기 조정을 지원하지 않아 건너뛰었어요")
                else -> cap.second.forEach { (area, bounds) -> members += mapped(ClimateProp.FAN, area, bounds, null, null) }
            }
        }
        if (choice.temp) {
            val cap = caps[ClimateProp.TEMP]
            val bounds = cap?.second?.get(DRIVER_AREA)
            val inc = cap?.third
            if (cap?.first == Access.READ_WRITE && bounds != null && inc != null) tempRange = TempRange(bounds.start, bounds.endInclusive, inc)
            val why = when {
                cap == null -> userText("온도 지원을 확인하지 못해 건너뛰었어요", capErr[ClimateProp.TEMP])
                cap.first != Access.READ_WRITE -> "이 차에서는 목표 온도를 바꿀 수 없어 건너뛰었어요"
                bounds == null -> "운전석만 따로 맞출 수 있는 온도 영역이 없어 건너뛰었어요"
                inc == null -> "온도 간격을 확인할 수 없어 건너뛰었어요"
                !validTemp(choice.tempC, bounds, inc) ->
                    "고른 온도 ${ClimateValues.show(ClimateProp.TEMP, choice.tempC)}°C는 이 차에서 고를 수 없어요(${ClimateValues.show(ClimateProp.TEMP, bounds.start)}~${ClimateValues.show(ClimateProp.TEMP, bounds.endInclusive)}°C, ${ClimateValues.show(ClimateProp.TEMP, inc)}°C 간격). 준비 화면에서 다시 골라 주세요"
                else -> null
            }
            if (why != null) skips += skipped(ClimateProp.TEMP, why) else members += mapped(ClimateProp.TEMP, DRIVER_AREA, bounds!!, inc, choice.tempC)
        }
        // Per group: fan first, then the temperature.
        val order = members.map { it.autoArea }.distinct()
        return skips + members.filter { it.skip != null } +
            order.flatMap { a -> members.filter { it.skip == null && it.autoArea == a }.sortedBy { if (it.prop == ClimateProp.FAN) 0 else 1 } }
    }

    private fun validTemp(v: Float, bounds: ClosedFloatingPointRange<Float>, inc: Float) = v.isFinite() && v in bounds && ClimateValues.onGrid(v, bounds.start, inc)

    /** A value the app can write back exactly later: a whole fan step within the range, or a temperature on the grid. */
    private fun writable(item: Item, v: Float): Boolean = when (item.prop) {
        ClimateProp.FAN -> v.isFinite() && v in item.bounds!! && ClimateValues.payload(ClimateProp.FAN, v) != null
        ClimateProp.TEMP -> item.increment != null && validTemp(v, item.bounds!!, item.increment)
    }

    private fun next(token: Int, runId: Int, items: List<Item>, i: Int) {
        if (!current(token, runId)) return
        if (i == items.size) {
            preparing = false
            log("run $runId: 공조 적용 단계 끝")
            return publish()
        }
        val item = items[i]
        val then = { next(token, runId, items, i + 1) }
        item.skip?.let {
            mark(item.prop, item.area, null, ClimateResult.SKIPPED, it)
            return then()
        }
        applyItem(token, runId, item, then)
    }

    private fun required(dualArea: String?) = listOfNotNull(Side.POWER, Side.AUTO, Side.DUAL.takeIf { dualArea != null })

    /** Current value, original and target both writable, then the shared states as the last reads, then the record, a final check and the command. */
    private fun applyItem(token: Int, runId: Int, item: Item, then: () -> Unit) {
        askOrNull(token, runId, "${item.prop.label} 현재값", { ok, err -> port.read(item.prop, item.area, ok, err) }) { cur, readErr ->
            val target = when (item.prop) {
                ClimateProp.FAN -> cur?.let { c -> (c - 1f).takeIf { it >= max(item.bounds!!.start, 1f) } }
                ClimateProp.TEMP -> item.wanted
            }
            val why = when {
                cur == null -> userText("현재 값을 읽지 못해 바꾸지 않았어요", readErr)
                !writable(item, cur) -> "지금 값을 나중에 그대로 되돌릴 수 없어 바꾸지 않았어요"
                target == null -> "이미 가장 약한 단계라 그대로 두었어요"
                !writable(item, target) -> "목표 값을 이 차에서 쓸 수 없어 바꾸지 않았어요"
                item.prop == ClimateProp.TEMP && ClimateValues.same(ClimateProp.TEMP, cur, target) -> "이미 ${ClimateValues.show(ClimateProp.TEMP, target)}°C라 그대로 두었어요"
                else -> null
            }
            if (why != null || cur == null || target == null) {
                mark(item.prop, item.area, cur, ClimateResult.SKIPPED, why ?: "바꾸지 않았어요")
                return@askOrNull then()
            }
            readShared(item.powerArea!!, item.autoArea!!, item.dualArea, guard = { current(token, runId) }) { shared ->
                val existing = groups.firstOrNull { it.autoArea == item.autoArea }
                val auto = shared[Side.AUTO]
                val block = when {
                    // One group has one power and one Sync area; a member that maps elsewhere is never merged into it (the record must stay readable).
                    existing != null && (existing.powerArea != item.powerArea || (item.dualArea != null && existing.dualArea != null && existing.dualArea != item.dualArea)) ->
                        "같은 자동 모드를 쓰지만 공조 전원·연동 영역이 달라 이 항목은 바꾸지 않았어요"
                    required(item.dualArea).any { shared[it] == null } || auto == null -> "공조 상태를 확인하지 못해 바꾸지 않았어요"
                    shared[Side.POWER] == false -> "공조가 꺼져 있어 건드리지 않았어요"
                    item.prop == ClimateProp.TEMP && shared[Side.DUAL] == true -> "공조 연동(Sync)이 켜져 있어 운전석 온도는 바꾸지 않았어요"
                    else -> commandBlock(item.prop, item.area, item.autoArea, item.powerArea, runId)
                }
                if (block != null || auto == null) {
                    mark(item.prop, item.area, cur, ClimateResult.SKIPPED, block ?: "공조 상태를 확인하지 못해 바꾸지 않았어요")
                    return@readShared then()
                }
                if (existing != null && existing.expected.any { (k, v) -> shared[Side.valueOf(k)] != v }) {
                    // The group's shared state moved since the app's last command: a change from the vehicle side.
                    external(existing, "차량에서 공조 설정이 바뀌어 이번 휴식의 공조 조정을 멈췄어요")
                    mark(item.prop, item.area, cur, ClimateResult.SKIPPED, "차량에서 공조 설정이 바뀌어 바꾸지 않았어요")
                    return@readShared then()
                }
                val member = ClimateMember(item.prop, item.area, cur, target, MemberStage.APPLY_REQUESTED, newId(), target, responseReceived = false)
                val observed = observedOf(shared)
                val group = existing?.copy(
                    members = existing.members + member,
                    dualArea = existing.dualArea ?: item.dualArea,
                    expected = existing.expected + observed.filterKeys { it !in existing.expected },
                ) ?: ClimateGroup(newId(), item.autoArea, item.powerArea, item.dualArea, autoOriginal = auto, expected = observed, stage = GroupStage.ACTIVE, members = listOf(member))
                val before = groups
                if (!persist(if (existing == null) groups + group else replaceGroup(group))) {
                    mark(item.prop, item.area, cur, ClimateResult.SKIPPED, "기록을 저장하지 못해 바꾸지 않았어요")
                    return@readShared then()
                }
                if (!current(token, runId) || commandBlock(item.prop, item.area, item.autoArea, item.powerArea, runId, group.groupId) != null) {
                    // Certainly not sent: the record goes back (if the file can still change) and nothing is written.
                    persist(before)
                    mark(item.prop, item.area, cur, ClimateResult.SKIPPED, "명령 직전 조건이 바뀌어 바꾸지 않았어요")
                    return@readShared if (current(token, runId)) then() else Unit
                }
                if (existing == null) log("공조 그룹 시작: 자동 모드 원래 $auto(처음 변경 전 한 번만 저장)")
                jobRun[group.groupId] = runId
                val k = key(group.groupId, member.prop, member.area)
                preShared[k] = shared
                applyWaiters[k] = { if (current(token, runId)) then() }
                mark(group, member, ClimateResult.WORKING, "맞추는 중이에요")
                send(group.groupId, member)
            }
        }
    }

    /**
     * Right before an apply command: parked, files writable, debug idle, the driver-only temperature contract, the
     * group still under control and nothing open on it or on any group sharing its auto or power area.
     */
    private fun commandBlock(prop: ClimateProp, area: String, autoArea: String, powerArea: String, runId: Int, own: String? = null): String? {
        val g = groups.firstOrNull { it.autoArea == autoArea }
        return when {
            !parking().parked -> "주차(P, 주행 제한 없음)가 확인되지 않아 바꾸지 않았어요"
            storageFault != null -> "기록 저장 오류로 바꾸지 않았어요"
            debugBlock() != null -> "개발 진단 작업이 있어 바꾸지 않았어요"
            prop == ClimateProp.TEMP && area != DRIVER_AREA -> "운전석 단독 온도 영역이 아니라 바꾸지 않았어요"
            g != null && g.groupId != own && jobRun[g.groupId] != runId -> "정리하지 않은 이전 공조 기록이 있어 바꾸지 않았어요"
            g != null && g.stage != GroupStage.ACTIVE -> "공조 상태가 바뀌어 이번 휴식의 공조 조정을 멈췄어요"
            sharedOpen(autoArea, powerArea, except = own) -> "같은 공조 전원·자동 모드를 쓰는 요청의 결과를 기다리고 있어 바꾸지 않았어요"
            else -> null
        }
    }

    // --- shared resources ---

    private fun seats(area: String) = area.split('+').toSet()

    private fun overlaps(g: ClimateGroup, autoArea: String, powerArea: String) =
        seats(g.autoArea).any { it in seats(autoArea) } || seats(g.powerArea).any { it in seats(powerArea) }

    /** Some group sharing the auto or power area (except [except]) has an open or unknown request. */
    private fun sharedOpen(autoArea: String, powerArea: String, except: String?): Boolean =
        groups.any { o -> o.groupId != except && overlaps(o, autoArea, powerArea) && (o.locked || inFlight(o.groupId)) }

    /** The group itself or a group sharing its resources has something open: wait. */
    private fun blockedByOpen(groupId: String): Boolean {
        val g = group(groupId) ?: return false
        return inFlight(groupId) || sharedOpen(g.autoArea, g.powerArea, except = groupId)
    }

    // --- requests and answers ---

    private fun key(groupId: String, prop: ClimateProp, area: String) = "$groupId|${prop.name}|$area"

    private fun autoKey(groupId: String) = "$groupId|AUTO"

    private fun inFlight(groupId: String) = pending.keys.any { it.startsWith("$groupId|") } || confirming.any { it.startsWith("$groupId|") }

    private fun observedOf(shared: Map<Side, Boolean?>) = shared.filterValues { it != null }.mapKeys { it.key.name }.mapValues { it.value!! }

    private fun send(groupId: String, m: ClimateMember) {
        val k = key(groupId, m.prop, m.area)
        val requestId = m.requestId
        pending[k] = Pending(requestId, scheduler.schedule(CLIMATE_WRITE_TIMEOUT_MS) { onTimeout(k, requestId) })
        log("${m.prop.label} ${if (m.stage == MemberStage.RESTORE_REQUESTED) "복원" else "적용"} 요청 #${requestId.take(8)} ${m.area} payload=${ClimateValues.show(m.prop, m.payload)} (원래 ${ClimateValues.show(m.prop, m.original)})")
        publish()
        port.write(m.prop, m.area, m.payload, { echo -> onAnswer(groupId, m.prop, m.area, requestId, "onComplete(${echo?.let { ClimateValues.show(m.prop, it) }})", true, echo) }, { e ->
            onAnswer(groupId, m.prop, m.area, requestId, "onFailed(${e.text()})", false, null)
        })
    }

    /** The request stays open and its group locked. An apply chain goes on to the next item; a group restore stops. */
    private fun onTimeout(k: String, requestId: String) {
        val p = pending[k]?.takeIf { it.requestId == requestId } ?: return
        p.timedOut = true
        val groupId = k.substringBefore('|')
        log("공조 요청 #${requestId.take(8)}: ${CLIMATE_WRITE_TIMEOUT_MS}ms 동안 응답 없음 → 결과 불명, 그룹과 같은 자원을 쓰는 그룹 잠금")
        group(groupId)?.let { g -> markLine(g, k, ClimateResult.UNKNOWN, "응답을 기다리고 있어요(결과를 아직 몰라요)") }
        applyWaiters.remove(k)?.invoke()
        if (restoreWaiters.remove(k) != null && restoring?.groupId == groupId) release()
        publish()
    }

    private fun onAnswer(groupId: String, prop: ClimateProp, area: String, requestId: String, result: String, ok: Boolean, echo: Float?) {
        if (!alive) return log("ignored climate answer after close or lease loss #${requestId.take(8)} $result")
        val k = key(groupId, prop, area)
        val p = pending[k]
        if (p == null || p.requestId != requestId) return log("ignored stale climate answer #${requestId.take(8)} $result")
        pending.remove(k)
        p.timer.cancel()
        val g = group(groupId) ?: return
        val m = g.member(prop, area)?.takeIf { it.requestId == requestId } ?: return
        log("${prop.label} 응답 #${requestId.take(8)} $result${if (p.timedOut) " (시간 초과 뒤)" else ""}")
        persist(replaceGroup(g.copy(members = g.members.map { if (it === m) m.copy(responseReceived = true, lastEcho = echo) else it })))
        confirming += k
        publish()
        scheduler.schedule(CLIMATE_SETTLE_MS) { confirm(groupId, prop, area, requestId, ok) }
    }

    /**
     * The separate reads after the terminal callback decide (the value and the shared states); the echo never does.
     * A shared state that moved across this command, or could not be read, has no knowable source: RESULT_UNKNOWN.
     */
    private fun confirm(groupId: String, prop: ClimateProp, area: String, requestId: String, ok: Boolean) {
        if (!alive) return
        val k = key(groupId, prop, area)
        val g0 = group(groupId) ?: return run { confirming -= k }
        readOrNull(prop, area) { cur, readErr ->
            readShared(g0.powerArea, g0.autoArea, g0.dualArea, guard = { alive }) { shared ->
                confirming -= k
                val g = group(groupId)
                val m = g?.member(prop, area)?.takeIf { it.requestId == requestId }
                if (g == null || m == null) return@readShared afterAnswer()
                val pre = preShared.remove(k)
                val unread = required(g.dualArea).filter { shared[it] == null }
                val moved = required(g.dualArea).filter { s -> pre?.get(s) != null && shared[s] != null && shared[s] != pre[s] }
                val unknown = unread.isNotEmpty() || moved.isNotEmpty() || pre == null
                if (unknown) log("${prop.label} 명령 전후 공유 상태 ${if (moved.isNotEmpty()) "변화 $moved($pre → $shared)" else "확인 불가 $unread"}: 출처를 알 수 없어 이 그룹을 결과 불명으로 멈춤")
                val read = cur?.let { ClimateValues.show(prop, it) } ?: "조회 실패($readErr)"
                val restoring = m.stage == MemberStage.RESTORE_REQUESTED
                val good = ok && ClimateValues.same(prop, cur, if (restoring) m.original else m.target)
                val nm = m.copy(
                    stage = if (restoring) (if (good) MemberStage.RESTORED else MemberStage.RESTORE_FAILED) else (if (good) MemberStage.APPLIED else MemberStage.RESULT_UNKNOWN),
                    responseReceived = true,
                    lastRead = cur,
                )
                val next = g.copy(
                    members = g.members.map { if (it === m) nm else it },
                    expected = if (unknown) g.expected else g.expected + observedOf(shared).filterKeys { it in g.expected },
                    stage = when {
                        unknown -> GroupStage.RESULT_UNKNOWN
                        restoring && !good -> GroupStage.HELD
                        else -> g.stage
                    },
                )
                if (unknown) heldWhy[groupId] = "공조 상태(자동 모드·전원·연동)가 바뀌었거나 확인되지 않아 더 바꾸지 않았어요. 차량에서 확인한 뒤 [원래대로]를 누를 수 있어요"
                else if (restoring && !good) heldWhy[groupId] = "${prop.label}를 되돌렸는지 확인하지 못했어요"
                persist(replaceGroup(next))
                log("${prop.label} ${if (restoring) "복원" else "적용"} ${if (good) "확인" else "실패/불명"}: 재조회 $read(목표 ${ClimateValues.show(prop, if (restoring) m.original else m.target)})")
                when {
                    restoring -> mark(next, nm, if (good) ClimateResult.RESTORED else ClimateResult.FAILED, if (good) "원래대로 돌렸어요" else "되돌렸는지 확인하지 못했어요")
                    good -> {
                        mark(next, nm, ClimateResult.APPLIED, if (prop == ClimateProp.FAN) "한 단계 줄였어요(${ClimateValues.show(prop, m.original)}→${ClimateValues.show(prop, m.target)})" else "${ClimateValues.show(prop, m.target)}°C로 맞췄어요")
                        if (!unknown) observe(groupId, nm)
                    }
                    else -> mark(next, nm, ClimateResult.UNKNOWN, "바뀌었는지 확인하지 못했어요")
                }
                if (unknown) markGroup(next, ClimateResult.UNKNOWN, heldWhy[groupId]!!)
                val w = if (restoring) restoreWaiters.remove(k) else applyWaiters.remove(k)
                if (w == null && runEnded(groupId)) restoreAfterAnswer += groupId
                if (!restoring && runEnded(groupId)) restoreAfterAnswer += groupId
                w?.invoke()
                afterAnswer()
            }
        }
    }

    private fun runEnded(groupId: String): Boolean {
        val runId = jobRun[groupId] ?: return false
        return run?.id != runId || run?.ending == true
    }

    /** After any answer: groups waiting for open requests (their own or a shared resource's) may go now. */
    private fun afterAnswer() {
        val ready = restoreAfterAnswer.filter { !blockedByOpen(it) && restoring?.groupId != it }
        restoreAfterAnswer -= ready.toSet()
        if (ready.isNotEmpty()) enqueue(ready, manual = false) else publish()
    }

    // --- changes from the vehicle side ---

    private fun observe(groupId: String, m: ClimateMember) {
        val k = key(groupId, m.prop, m.area)
        if (observers.containsKey(k)) return
        try {
            observers[k] = port.observe(m.prop, m.area) { v -> onListener(groupId, m.prop, m.area, v) }
        } catch (e: RuntimeException) {
            log("${m.prop.label} 리스너 등록 실패: ${e.javaClass.name}. 종료 직전 조회로만 외부 변경을 확인")
        }
    }

    /** A settled group: under the app's control with nothing of ours open on it. Only then a vehicle-side change is judged. */
    private fun settled(g: ClimateGroup) = g.stage == GroupStage.ACTIVE && !g.locked && !inFlight(g.groupId) &&
        restoring?.groupId != g.groupId && restoreQueue.none { it.groupId == g.groupId }

    /** A listener value is not proof (a cached first value can be old): a differing one only starts separate reads. */
    private fun onListener(groupId: String, prop: ClimateProp, area: String, value: Float?) {
        if (!alive || value == null) return
        val g = group(groupId)?.takeIf { settled(it) } ?: return
        val m = g.member(prop, area)?.takeIf { it.stage == MemberStage.APPLIED } ?: return
        if (ClimateValues.same(prop, value, m.target)) return
        val k = key(groupId, prop, area)
        if (k in checking) {
            checking[k] = true
            return
        }
        checkExternal(g, m, value)
    }

    /** Separate reads; the answer counts only for the same owner, request and settled state. A failed read decides nothing. */
    private fun checkExternal(g: ClimateGroup, m: ClimateMember, heard: Float) {
        val k = key(g.groupId, m.prop, m.area)
        checking[k] = false
        log("${m.prop.label} 리스너 값 ${ClimateValues.show(m.prop, heard)} ≠ 앱 값 ${ClimateValues.show(m.prop, m.target)}: 실제 현재값을 다시 읽어 확인")
        readOrNull(m.prop, m.area) { cur, readErr ->
            readShared(g.powerArea, g.autoArea, g.dualArea, guard = { alive }) { shared ->
                val again = checking.remove(k) == true
                val now = group(g.groupId)?.takeIf { settled(it) }
                val nm = now?.member(m.prop, m.area)?.takeIf { it.requestId == m.requestId && it.stage == MemberStage.APPLIED }
                if (now == null || nm == null) return@readShared log("${m.prop.label} 확인 조회 결과 무시: 그 사이 작업 상태가 바뀜")
                val unread = required(now.dualArea).any { shared[it] == null }
                val sharedMoved = now.expected.any { (s, v) -> shared[Side.valueOf(s)]?.let { it != v } == true }
                when {
                    cur == null || unread -> log("${m.prop.label} 확인 조회 실패($readErr, 공유 $shared): 외부 변경으로 정하지 않음")
                    ClimateValues.same(m.prop, cur, nm.target) && !sharedMoved -> {
                        log("${m.prop.label} 실제 ${ClimateValues.show(m.prop, cur)} = 앱 값: 리스너 값 ${ClimateValues.show(m.prop, heard)}은 외부 변경이 아님")
                        if (again) checkExternal(now, nm, heard)
                    }
                    else -> {
                        log("${m.prop.label} 외부 변경 확인: 실제 ${ClimateValues.show(m.prop, cur)}(앱 값 ${ClimateValues.show(m.prop, nm.target)}), 공유 상태 $shared. 이 그룹은 더 바꾸거나 자동으로 되돌리지 않아요")
                        external(now, "차량에서 바뀐 공조 설정은 유지했어요")
                    }
                }
            }
        }
    }

    // --- restore ---

    private fun restoreBlock(): String? = when {
        loadProblem != null -> "공조 기록을 읽을 수 없어 되돌릴 수 없어요"
        storageFault != null -> "기록 저장 오류가 있어 되돌릴 수 없어요"
        preparing || restoring != null || restoreQueue.isNotEmpty() -> "공조 작업이 진행 중이에요"
        groups.none { !it.locked && !blockedByOpen(it.groupId) } -> if (groups.isEmpty()) "되돌릴 기록이 없어요" else "결과를 모르는 요청이 있어 앱에서 되돌릴 수 없어요. 차량에서 직접 맞춰 주세요"
        !parking().parked -> "P(주차)이고 주행 제한이 없을 때 되돌릴 수 있어요"
        debugBlock() != null -> "개발 진단 작업이나 기록이 남아 있어 되돌릴 수 없어요"
        else -> null
    }

    private fun discardBlock(): String? = when {
        loadProblem != null -> "기록 파일에 문제가 있어 앱에서 지우지 않아요"
        storageFault != null -> "기록 저장 오류가 있어 지울 수 없어요"
        preparing || restoring != null || restoreQueue.isNotEmpty() || pending.isNotEmpty() || confirming.isNotEmpty() -> "진행 중인 공조 작업이 끝난 뒤에 지울 수 있어요"
        groups.none { !it.locked } -> if (groups.isEmpty()) "지울 기록이 없어요" else "결과를 모르는 요청이 있는 기록은 지울 수 없어요"
        else -> null
    }

    /** Before a new restore command. [parkLost] counts even when P is back. */
    private fun restoreHold(groupId: String): String? {
        val g = group(groupId)
        return when {
            !alive -> "공조를 되돌릴 수 없어요"
            !parking().parked || groupId in parkLost -> "주차 상태가 아니어서 되돌리지 않았어요. P에서 [원래대로]를 눌러 주세요"
            storageFault != null -> "기록 저장 오류로 되돌리지 않았어요"
            debugBlock() != null -> "개발 진단 작업이나 기록이 남아 있어 되돌리지 않았어요"
            g != null && sharedOpen(g.autoArea, g.powerArea, except = groupId) -> "같은 공조 전원·자동 모드를 쓰는 요청의 결과를 기다리고 있어 되돌리지 않았어요"
            else -> null
        }
    }

    private fun enqueue(jobs: List<String>, manual: Boolean) {
        dropCheck() // a restore makes an earlier read-only finding stale
        jobs.filter { j -> restoreQueue.none { it.groupId == j } && restoring?.groupId != j }.forEach { restoreQueue += Job(it, manual) }
        restoreNext()
    }

    /** One group at a time: check, members back where needed (temperature first), auto on [원래대로] only, then a full re-read. */
    private fun restoreNext() {
        if (!alive || restoring != null) return publish()
        val job = restoreQueue.removeFirstOrNull() ?: return publish()
        val g = group(job.groupId) ?: return restoreNext()
        if (blockedByOpen(g.groupId)) {
            if (!job.manual) restoreAfterAnswer += g.groupId
            return restoreNext()
        }
        if (g.locked) {
            markGroup(g, ClimateResult.LOCKED, "결과를 모르는 요청이 있어 앱에서 되돌리지 않아요. 차량에서 직접 확인해 주세요")
            return restoreNext()
        }
        if (!job.manual && g.stage != GroupStage.ACTIVE) {
            markGroup(g, resultOf(g), textOf(g))
            markAuto(g, resultOf(g), autoTextOf(g))
            return restoreNext()
        }
        restoring = job
        job.stage = g.stage
        restoreHold(g.groupId)?.let { return hold(g, it) }
        publish()
        readGroup(g) { values, shared ->
            if (restoring !== job) return@readGroup
            val now = group(g.groupId) ?: return@readGroup release()
            if (required(now.dualArea).any { shared[it] == null }) return@readGroup hold(now, "공조 상태를 확인하지 못해 되돌리지 않았어요")
            if (shared[Side.POWER] != true) {
                return@readGroup if (job.manual) hold(now, "공조가 꺼져 있어 되돌리지 않았어요. 차량에서 공조를 켠 뒤 [원래대로]를 눌러 주세요")
                else external(now, "차량에서 공조를 꺼서 바뀐 설정은 유지했어요", inRestore = true)
            }
            if (!job.manual) {
                if (values.values.any { it == null }) return@readGroup hold(now, "현재 값을 읽지 못해 되돌리지 않았어요")
                val sharedMoved = now.expected.any { (s, v) -> shared[Side.valueOf(s)] != v }
                val memberMoved = now.members.any { m ->
                    val v = values[m.prop to m.area]
                    when (m.stage) {
                        MemberStage.APPLIED -> !ClimateValues.same(m.prop, v, m.target)
                        MemberStage.RESTORED -> !ClimateValues.same(m.prop, v, m.original)
                        else -> !ClimateValues.same(m.prop, v, m.target) && !ClimateValues.same(m.prop, v, m.original)
                    }
                }
                if (sharedMoved || memberMoved) {
                    log("공조 종료 직전 조회가 앱의 마지막 값과 다름(공유 $shared, 값 $values): 외부 변경으로 보고 되돌리지 않음")
                    return@readGroup external(now, "차량에서 바뀐 공조 설정은 유지했어요", inRestore = true)
                }
            }
            job.base = shared.filterValues { it != null }.mapValues { it.value!! }
            val list = now.members.sortedBy { if (it.prop == ClimateProp.TEMP) 0 else 1 }.map { it.prop to it.area }
            restoreMember(job, list, 0)
        }
    }

    /** Each member: decided by its current value (not by an old stage); the shared states must still be the job's base. */
    private fun restoreMember(job: Job, list: List<Pair<ClimateProp, String>>, i: Int) {
        if (!alive || restoring !== job) return
        val g = group(job.groupId) ?: return release()
        if (g.stage != job.stage) return release() // the group became unknown, held or external meanwhile: no further writes
        if (i == list.size) return finishGroup(job)
        val m = g.member(list[i].first, list[i].second) ?: return restoreMember(job, list, i + 1)
        restoreCapability(m) { problem ->
            if (restoring !== job) return@restoreCapability
            if (problem != null) return@restoreCapability hold(group(job.groupId) ?: return@restoreCapability release(), problem)
            readOrNull(m.prop, m.area) { cur, readErr ->
                val g1 = group(job.groupId) ?: return@readOrNull release()
                readShared(g1.powerArea, g1.autoArea, g1.dualArea, guard = { alive && restoring === job }) { shared ->
                    val now = group(job.groupId) ?: return@readShared release()
                    val nm = now.member(m.prop, m.area)?.takeIf { it.requestId == m.requestId } ?: return@readShared release()
                    if (now.stage != job.stage) return@readShared release()
                    val baseMoved = job.base.any { (s, v) -> shared[s] != v }
                    when {
                        cur == null || required(now.dualArea).any { shared[it] == null } -> return@readShared hold(now, userText(if (cur == null) "현재 값을 읽지 못해 되돌리지 않았어요" else "공조 상태(자동 모드·전원·연동)를 읽지 못해 되돌리지 않았어요", readErr))
                        baseMoved && job.manual -> {
                            persist(replaceGroup(now.copy(stage = GroupStage.RESULT_UNKNOWN)))
                            return@readShared hold(now, "되돌리는 중에 공조 상태(자동 모드·전원·연동)가 바뀌어 멈췄어요. 차량에서 확인한 뒤 다시 [원래대로]를 눌러 주세요")
                        }
                        baseMoved -> return@readShared external(now, "차량에서 바뀐 공조 설정은 유지했어요", inRestore = true)
                        m.prop == ClimateProp.TEMP && shared[Side.DUAL] != false -> return@readShared hold(now, "공조 연동(Sync)이 켜져 있어 운전석 온도를 되돌리지 않았어요. 차량에서 연동을 끈 뒤 [원래대로]를 눌러 주세요")
                        ClimateValues.same(m.prop, cur, nm.original) -> return@readShared restoreMember(job, list, i + 1) // already the original; the final re-read confirms
                        !job.manual && !ClimateValues.same(m.prop, cur, nm.target) -> return@readShared external(now, "차량에서 바뀐 공조 설정은 유지했어요", inRestore = true)
                    }
                    (restoreHold(job.groupId) ?: commandTarget(nm))?.let { return@readShared hold(now, it) }
                    val sent = nm.copy(stage = MemberStage.RESTORE_REQUESTED, requestId = newId(), payload = nm.original, responseReceived = false, lastEcho = null, lastRead = cur)
                    val next = now.copy(members = now.members.map { if (it === nm) sent else it })
                    if (!persist(replaceGroup(next))) return@readShared release()
                    (restoreHold(job.groupId) ?: commandTarget(sent))?.let {
                        // Saved but certainly not sent: back to the record as it was, then held.
                        persist(replaceGroup(now))
                        return@readShared hold(now, it)
                    }
                    val k = key(job.groupId, sent.prop, sent.area)
                    preShared[k] = shared
                    restoreWaiters[k] = { restoreMember(job, list, i + 1) }
                    mark(next, sent, ClimateResult.WORKING, "되돌리는 중이에요")
                    send(job.groupId, sent)
                }
            }
        }
    }

    /** The target contract right before a command: the temperature only on the driver-only area. */
    private fun commandTarget(m: ClimateMember): String? =
        if (m.prop == ClimateProp.TEMP && m.area != DRIVER_AREA) "운전석 단독 온도 영역이 아니라 바꾸지 않았어요" else null

    private fun restoreCapability(m: ClimateMember, then: (String?) -> Unit) {
        withTimeout({ ok, err -> port.capability(m.prop, { a, b, inc -> ok(Triple(a, b, inc)) }, err) }) { cap, e ->
            if (!alive) return@withTimeout
            val bounds = cap?.second?.get(m.area)
            then(
                when {
                    cap == null -> userText("${m.prop.label} 지원을 확인하지 못해 되돌리지 않았어요", e)
                    cap.first != Access.READ_WRITE -> "${m.prop.label}를 바꿀 수 없는 상태라 되돌리지 않았어요"
                    bounds == null -> "기록한 영역을 지금 지원하지 않아 되돌리지 않았어요"
                    m.original !in bounds || ClimateValues.payload(m.prop, m.original) == null -> "원래 값이 지금 지원 범위 밖이라 되돌리지 않았어요"
                    m.prop == ClimateProp.TEMP && (cap.third == null || !ClimateValues.onGrid(m.original, bounds.start, cap.third!!)) -> "원래 온도가 지금 고를 수 있는 값이 아니라 되돌리지 않았어요"
                    else -> null
                },
            )
        }
    }

    /** Members done: an automatic restore never writes auto (its change has no knowable source); [원래대로] writes it once. */
    private fun finishGroup(job: Job) {
        val g = group(job.groupId) ?: return release()
        withTimeout<Boolean?>({ ok, err -> port.readSide(Side.AUTO, g.autoArea, ok, err) }) { auto, _ ->
            if (!alive || restoring !== job) return@withTimeout
            val now = group(job.groupId) ?: return@withTimeout release()
            when {
                auto == null -> verify(job, "자동 모드 상태를 확인하지 못했어요")
                auto == now.autoOriginal -> verify(job, null)
                job.manual -> restoreAuto(job)
                else -> {
                    persist(replaceGroup(now.copy(stage = GroupStage.RESULT_UNKNOWN)))
                    job.stage = GroupStage.RESULT_UNKNOWN
                    log("자동 모드 $auto ≠ 원래 ${now.autoOriginal}: 출처를 알 수 없어 자동으로 되돌리지 않음")
                    verify(job, "자동 모드가 휴식 전과 달라요(원래 ${onOff(now.autoOriginal)}). 앱이 바꾼 것인지 알 수 없어 그대로 두었어요. 확인한 뒤 [원래대로]를 누를 수 있어요")
                }
            }
        }
    }

    /** [원래대로] only: support, area, power, the group's Sync precondition and the job's base are checked again first. */
    private fun restoreAuto(job: Job) {
        withTimeout({ ok, err -> port.sideAreas(Side.AUTO, { a, l -> ok(a to l) }, err) }) { cap, _ ->
            if (!alive || restoring !== job) return@withTimeout
            val g = group(job.groupId) ?: return@withTimeout release()
            when {
                cap == null -> return@withTimeout hold(g, "자동 모드 지원을 확인하지 못해 되돌리지 않았어요")
                cap.first != Access.READ_WRITE || g.autoArea !in cap.second -> return@withTimeout hold(g, "자동 모드를 앱에서 바꿀 수 없어 그대로 두었어요")
            }
            readShared(g.powerArea, g.autoArea, g.dualArea, guard = { alive && restoring === job }) { shared ->
                val n = group(job.groupId) ?: return@readShared release()
                val baseMoved = job.base.filterKeys { it != Side.AUTO }.any { (s, v) -> shared[s] != v } || shared[Side.AUTO] != job.base[Side.AUTO]
                val why = when {
                    required(n.dualArea).any { shared[it] == null } -> "공조 상태를 확인하지 못해 자동 모드를 되돌리지 않았어요"
                    shared[Side.POWER] != true -> "공조가 꺼져 있어 자동 모드를 되돌리지 않았어요"
                    n.members.any { it.prop == ClimateProp.TEMP } && shared[Side.DUAL] != false -> "공조 연동(Sync)이 켜져 있어 자동 모드를 되돌리지 않았어요"
                    baseMoved || n.stage != job.stage -> "되돌리는 중에 공조 상태가 바뀌어 자동 모드는 그대로 두었어요"
                    else -> restoreHold(n.groupId)
                }
                if (why != null) return@readShared hold(n, why)
                val sent = n.copy(stage = GroupStage.AUTO_RESTORE_REQUESTED, autoRequestId = newId(), autoResponseReceived = false)
                if (!persist(replaceGroup(sent))) return@readShared release()
                restoreHold(n.groupId)?.let {
                    persist(replaceGroup(n))
                    return@readShared hold(n, it)
                }
                job.stage = GroupStage.AUTO_RESTORE_REQUESTED
                val k = autoKey(n.groupId)
                val requestId = sent.autoRequestId!!
                pending[k] = Pending(requestId, scheduler.schedule(CLIMATE_WRITE_TIMEOUT_MS) { onTimeout(k, requestId) })
                restoreWaiters[k] = { verify(job, null) }
                log("자동 모드 복원 요청 #${requestId.take(8)} ${n.autoArea} payload=${n.autoOriginal} ([원래대로])")
                markAuto(sent, ClimateResult.WORKING, "자동 모드를 되돌리는 중이에요")
                port.writeAuto(n.autoArea, n.autoOriginal, { echo -> onAutoAnswer(n.groupId, requestId, "onComplete($echo)", true) }, { e -> onAutoAnswer(n.groupId, requestId, "onFailed(${e.text()})", false) })
            }
        }
    }

    private fun onAutoAnswer(groupId: String, requestId: String, result: String, ok: Boolean) {
        if (!alive) return log("ignored auto answer after close or lease loss #${requestId.take(8)} $result")
        val k = autoKey(groupId)
        val p = pending[k]
        if (p == null || p.requestId != requestId) return log("ignored stale auto answer #${requestId.take(8)} $result")
        pending.remove(k)
        p.timer.cancel()
        val g = group(groupId)?.takeIf { it.autoRequestId == requestId } ?: return
        log("자동 모드 응답 #${requestId.take(8)} $result")
        persist(replaceGroup(g.copy(autoResponseReceived = true, stage = if (ok) g.stage else GroupStage.AUTO_RESTORE_FAILED)))
        if (!ok) restoring?.takeIf { it.groupId == groupId }?.stage = GroupStage.AUTO_RESTORE_FAILED
        confirming += k
        scheduler.schedule(CLIMATE_SETTLE_MS) {
            confirming -= k
            if (!alive) return@schedule
            val w = restoreWaiters.remove(k)
            if (w != null) w() else afterAnswer()
        }
    }

    /**
     * After the last write: every member and the shared states are read again. The record goes only when every member
     * is the original and the auto state is the original (power on, required states readable). Otherwise each item's
     * actual result is shown and the record stays; nothing is retried by itself.
     */
    private fun verify(job: Job, autoProblem: String?) {
        if (!alive || restoring !== job) return
        val g = group(job.groupId) ?: return release()
        readGroup(g) { values, shared ->
            if (restoring !== job) return@readGroup
            val now = group(job.groupId) ?: return@readGroup release()
            val auto = shared[Side.AUTO]
            val autoOk = auto == now.autoOriginal
            val membersOk = now.members.all { ClimateValues.same(it.prop, values[it.prop to it.area], it.original) }
            // Power and Sync must still be what the job started from (and Sync off for a temperature group); auto is compared with the original.
            val baseMoved = job.base.filterKeys { it != Side.AUTO }.any { (s, v) -> shared[s] != v }
            val syncOk = now.members.none { it.prop == ClimateProp.TEMP } || shared[Side.DUAL] == false
            val sharedOk = required(now.dualArea).none { shared[it] == null } && shared[Side.POWER] == true && !baseMoved && syncOk
            if (autoOk && membersOk && sharedOk && autoProblem == null) {
                log("공조 그룹 최종 재조회: 값 $values, 공유 $shared = 원래값. 기록 삭제")
                return@readGroup done(now)
            }
            log("공조 그룹 최종 재조회가 원래값과 다름(값 $values, 공유 $shared): 기록 유지, 다시 시도하지 않음")
            now.members.forEach { m ->
                val v = values[m.prop to m.area]
                when {
                    v == null -> mark(now, m, ClimateResult.UNKNOWN, "지금 값을 확인하지 못했어요")
                    ClimateValues.same(m.prop, v, m.original) -> mark(now, m, ClimateResult.RESTORED, "원래대로예요")
                    autoOk && now.autoOriginal -> mark(now, m, ClimateResult.HELD, "자동 모드가 다시 조절했어요(지금 ${ClimateValues.show(m.prop, v)}${unit(m.prop)})")
                    else -> mark(now, m, ClimateResult.HELD, "원래대로 돌리지 못했어요(지금 ${ClimateValues.show(m.prop, v)}${unit(m.prop)})")
                }
            }
            val autoText = when {
                autoProblem != null -> autoProblem
                !sharedOk -> "되돌린 뒤 공조 상태(전원·연동)가 바뀌었거나 확인되지 않아 기록을 남겼어요"
                auto == null -> "자동 모드 상태를 확인하지 못했어요"
                autoOk -> "자동 모드는 원래대로(${onOff(now.autoOriginal)})예요"
                else -> "자동 모드가 원래와 달라요(지금 ${onOff(auto)}, 원래 ${onOff(now.autoOriginal)})"
            }
            markAuto(now, if (autoOk && autoProblem == null && sharedOk) ClimateResult.RESTORED else ClimateResult.UNKNOWN, autoText)
            val stage = when {
                now.stage == GroupStage.AUTO_RESTORE_REQUESTED && !autoOk -> GroupStage.AUTO_RESTORE_FAILED
                now.stage == GroupStage.AUTO_RESTORE_REQUESTED || now.stage == GroupStage.ACTIVE -> GroupStage.HELD
                else -> now.stage
            }
            // The final readings go into the kept record, so a later run never takes an older reading as the latest.
            persist(replaceGroup(now.copy(stage = stage, members = now.members.map { it.copy(lastRead = values[it.prop to it.area]) })))
            heldWhy[now.groupId] = if (autoOk && autoProblem == null && sharedOk) "일부 항목이 원래값과 달라요" else autoText
            release()
        }
    }

    private fun unit(prop: ClimateProp) = if (prop == ClimateProp.TEMP) "°C" else ""

    /** Every member and the auto state read back as the originals: the record goes (not after a storage fault). */
    private fun done(g: ClimateGroup) {
        g.members.forEach { closeObserver(key(g.groupId, it.prop, it.area)) }
        if (storageFault == null && persist(groups.filter { it.groupId != g.groupId })) {
            g.members.forEach { mark(g, it, ClimateResult.RESTORED, "원래대로 돌렸어요") }
            markAuto(g, ClimateResult.RESTORED, "자동 모드는 원래대로(${onOff(g.autoOriginal)})예요")
        } else {
            markAuto(g, ClimateResult.HELD, "원래대로 돌렸지만 기록 저장 오류로 기록을 지우지 못했어요")
        }
        release()
    }

    /** A change from the vehicle side: no further apply and no automatic restore for this group; [원래대로] still can. */
    private fun external(g: ClimateGroup, why: String, inRestore: Boolean = false) {
        val latest = group(g.groupId) ?: g
        if (latest.stage != GroupStage.EXTERNAL_CHANGE) persist(replaceGroup(latest.copy(stage = GroupStage.EXTERNAL_CHANGE)))
        latest.members.forEach { closeObserver(key(latest.groupId, it.prop, it.area)) }
        heldWhy[g.groupId] = why
        markGroup(latest, ClimateResult.EXTERNAL, why)
        if (inRestore) release()
    }

    /** Kept for a later [원래대로]; nothing retries by itself (also not when P comes back). */
    private fun hold(g: ClimateGroup, why: String) {
        val latest = group(g.groupId) ?: g
        if (latest.stage == GroupStage.ACTIVE) persist(replaceGroup(latest.copy(stage = GroupStage.HELD)))
        latest.members.forEach { closeObserver(key(latest.groupId, it.prop, it.area)) }
        heldWhy[g.groupId] = why
        log("공조 복원 보류: $why")
        markGroup(latest, ClimateResult.HELD, why)
        release()
    }

    private fun release() {
        restoring = null
        restoreNext()
    }

    // --- reads with timeouts ---

    private fun readShared(powerArea: String, autoArea: String, dualArea: String?, guard: () -> Boolean, then: (Map<Side, Boolean?>) -> Unit) {
        val out = mutableMapOf<Side, Boolean?>()
        val steps = listOfNotNull(Side.POWER to powerArea, Side.AUTO to autoArea, dualArea?.let { Side.DUAL to it })
        fun step(i: Int) {
            if (!guard()) return
            if (i == steps.size) return then(out)
            withTimeout<Boolean?>({ ok, err -> port.readSide(steps[i].first, steps[i].second, ok, err) }) { v, _ ->
                out[steps[i].first] = v
                step(i + 1)
            }
        }
        step(0)
    }

    /** Every member's current value and the group's shared states. */
    private fun readGroup(g: ClimateGroup, then: (Map<Pair<ClimateProp, String>, Float?>, Map<Side, Boolean?>) -> Unit) {
        val values = mutableMapOf<Pair<ClimateProp, String>, Float?>()
        fun step(i: Int) {
            if (!alive) return
            if (i == g.members.size) return readShared(g.powerArea, g.autoArea, g.dualArea, guard = { alive }) { then(values, it) }
            val m = g.members[i]
            readOrNull(m.prop, m.area) { v, _ ->
                values[m.prop to m.area] = v
                step(i + 1)
            }
        }
        step(0)
    }

    private fun <T> askOrNull(token: Int, runId: Int, label: String, call: (ok: (T) -> Unit, err: (ProbeError) -> Unit) -> Unit, then: (T?, String?) -> Unit) {
        withTimeout(call) { value: T?, error ->
            if (!current(token, runId)) return@withTimeout log("ignored late climate read: $label")
            then(value, error)
        }
    }

    private fun readOrNull(prop: ClimateProp, area: String, then: (Float?, String?) -> Unit) =
        withTimeout<Float?>({ ok, err -> port.read(prop, area, ok, err) }) { v, e -> if (alive) then(v?.takeIf { it.isFinite() }, e) }

    private fun <T> withTimeout(call: (ok: (T) -> Unit, err: (ProbeError) -> Unit) -> Unit, done: (T?, String?) -> Unit) {
        var answered = false
        lateinit var timer: Cancellable
        fun finish(v: T?, e: String?) {
            if (answered) return
            answered = true
            timer.cancel()
            done(v, e)
        }
        timer = scheduler.schedule(CLIMATE_READ_TIMEOUT_MS) { finish(null, "${CLIMATE_READ_TIMEOUT_MS}ms 응답 없음") }
        try {
            call({ finish(it, null) }, { finish(null, it.text()) })
        } catch (e: RuntimeException) {
            finish(null, "${e.javaClass.name}: ${e.message}")
        }
    }

    // --- records ---

    private fun group(groupId: String) = groups.firstOrNull { it.groupId == groupId }

    private fun replaceGroup(next: ClimateGroup) = groups.map { if (it.groupId == next.groupId) next else it }

    /**
     * The only way the file changes. A closed or replaced owner never writes; after a storage fault a group may
     * still take an answer (attempted) but no group or member is added or removed. A failure sets the fault.
     */
    private fun persist(next: List<ClimateGroup>): Boolean {
        if (!alive) {
            log("ignored climate record change after close or lease loss")
            return false
        }
        fun shape(list: List<ClimateGroup>) = list.flatMap { g -> listOf(g.groupId) + g.members.map { "${g.groupId}|${it.prop}|${it.area}" } }.toSet()
        if (storageFault != null && shape(next) != shape(groups)) {
            log("기록 저장 오류 뒤라 공조 기록을 추가하거나 지우지 않아요")
            return false
        }
        if (!store.save(next)) {
            storageFault = "기록 파일 저장 실패(${store.lastError})"
            log("공조 기록 저장 실패(${store.lastError}): 이후 새 쓰기·복원·삭제를 막아요")
            publish()
            return false
        }
        groups = next
        publish()
        return true
    }

    private fun closeObserver(k: String) {
        observers.remove(k)?.let { runCatching { it.close() } }
    }

    // --- screen state ---

    private fun ofThisRun(groupId: String) = run != null && jobRun[groupId] == run?.id

    private fun originalText(prop: ClimateProp, v: Float) = "원래 ${ClimateValues.show(prop, v)}${unit(prop)}"

    private fun onOff(v: Boolean) = if (v) "켜짐" else "꺼짐"

    private fun mark(prop: ClimateProp, area: String, original: Float?, result: ClimateResult, text: String) {
        runItems["${prop.name}|$area"] = ClimateItemUi(prop, area, original?.let { originalText(prop, it) }, result, text)
        publish()
    }

    private fun mark(g: ClimateGroup, m: ClimateMember, result: ClimateResult, text: String) {
        val item = ClimateItemUi(m.prop, m.area, originalText(m.prop, m.original), result, text)
        if (ofThisRun(g.groupId)) runItems["${m.prop.name}|${m.area}"] = item else earlierItems["${m.prop.name}|${m.area}"] = item
        publish()
    }

    private fun markAuto(g: ClimateGroup, result: ClimateResult, text: String) {
        val item = ClimateItemUi(null, g.autoArea, "원래 자동 ${onOff(g.autoOriginal)}", result, text)
        if (ofThisRun(g.groupId)) runItems["AUTO|${g.autoArea}"] = item else earlierItems["AUTO|${g.autoArea}"] = item
        publish()
    }

    private fun markGroup(g: ClimateGroup, result: ClimateResult, text: String) {
        g.members.forEach { mark(g, it, result, text) }
    }

    private fun markLine(g: ClimateGroup, k: String, result: ClimateResult, text: String) {
        if (k.endsWith("|AUTO")) return markAuto(g, result, text)
        g.members.firstOrNull { key(g.groupId, it.prop, it.area) == k }?.let { mark(g, it, result, text) }
    }

    /** The screen shows [text] only. A technical [detail] (an SDK error or a timeout) goes to the log alone. */
    private fun userText(text: String, detail: String?): String {
        detail?.let { log("$text — 원인: $it") }
        return text
    }

    private fun say(text: String) {
        notice = text
        publish()
    }

    private fun publish() {
        state = ClimateUi(
            restLine = run?.let { line(it) },
            items = runItems.values.toList(),
            open = groups.flatMap { g ->
                g.members.map { m ->
                    ClimateItemUi(m.prop, m.area, originalText(m.prop, m.original), resultOf(g), textOf(g, m), checkedLine(key(g.groupId, m.prop, m.area), memberStamp(g, m)))
                } + ClimateItemUi(null, g.autoArea, "원래 자동 ${onOff(g.autoOriginal)}", resultOf(g), autoTextOf(g), checkedLine("${g.groupId}|AUTO", autoStamp(g)))
            },
            earlier = earlierItems.values.toList(),
            restoreBlock = if (groups.isEmpty()) null else restoreBlock(),
            discardBlock = if (groups.isEmpty()) null else discardBlock(),
            deferred = deferred,
            problem = loadProblem?.let { "공조 기록 파일을 읽을 수 없어요. 파일을 그대로 두고 공조 조정을 멈췄어요" }
                ?: storageFault?.let { "공조 기록을 저장할 수 없어요. 공조 조정을 멈췄어요" },
            working = working(),
            notice = notice,
            tempRange = tempRange,
            tempRangeLoading = tempRangeLoading,
            tempRangeProblem = tempRangeProblem,
            checking = checkingRecords,
        )
        onState(state)
    }

    /** A finding is shown only for the line exactly as it was read (same requests and stages); otherwise it is stale. */
    private fun checkedLine(k: String, stamp: String): String? =
        checked[k]?.takeIf { it.stamp == stamp }?.text ?: if (checkingRecords) "차량 설정을 확인하고 있어요" else null

    private fun line(r: Run): String? {
        r.line?.let { return it }
        val items = runItems.values
        val applied = items.filter { it.result == ClimateResult.APPLIED }
        val fan = applied.any { it.prop == ClimateProp.FAN }
        val temp = applied.firstOrNull { it.prop == ClimateProp.TEMP }
        val tempText = temp?.text?.substringBefore("로 맞췄어요")
        val text = when {
            fan && temp != null -> "바람을 한 단계 줄이고 운전석 온도를 ${tempText}로 맞췄어요"
            fan -> "바람을 한 단계 줄였어요"
            temp != null -> "운전석 온도를 ${tempText}로 맞췄어요"
            preparing || items.any { it.result == ClimateResult.WORKING } -> "공조를 맞추는 중이에요"
            items.any { it.result == ClimateResult.EXTERNAL } -> "차량에서 바뀐 공조 설정은 유지했어요"
            else -> items.firstOrNull { it.result == ClimateResult.UNKNOWN }?.let { "공조 조정 결과를 확인하지 못했어요" }
                ?: items.firstOrNull { it.result == ClimateResult.SKIPPED && !it.text.contains("꺼 두었어요") }?.text
                ?: items.firstOrNull()?.text
        }
        val trouble = applied.isNotEmpty() && items.any { it.result == ClimateResult.UNKNOWN || it.result == ClimateResult.FAILED || it.result == ClimateResult.EXTERNAL }
        return text?.plus(if (trouble) " (일부 항목은 확인하지 못했거나 차량에서 바뀌었어요)" else "")
    }

    private fun resultOf(g: ClimateGroup): ClimateResult = when {
        g.locked -> ClimateResult.LOCKED
        g.stage == GroupStage.EXTERNAL_CHANGE -> ClimateResult.EXTERNAL
        g.stage == GroupStage.HELD -> ClimateResult.HELD
        g.stage == GroupStage.RESULT_UNKNOWN || g.stage == GroupStage.AUTO_RESTORE_FAILED -> ClimateResult.UNKNOWN
        g.stage == GroupStage.AUTO_RESTORE_REQUESTED -> ClimateResult.WORKING
        g.members.all { it.stage == MemberStage.APPLIED } -> ClimateResult.APPLIED
        else -> ClimateResult.WORKING
    }

    private fun textOf(g: ClimateGroup): String = when {
        g.priorRequestUnresolved -> "이전 실행의 요청 결과를 몰라 앱에서 바꾸지 않아요. 차량에서 직접 맞춰 주세요"
        g.locked -> "응답을 기다리고 있어요(결과를 아직 몰라요)"
        g.stage == GroupStage.EXTERNAL_CHANGE -> heldWhy[g.groupId] ?: "차량에서 바뀐 공조 설정은 유지했어요"
        g.stage == GroupStage.HELD || g.stage == GroupStage.RESULT_UNKNOWN || g.stage == GroupStage.AUTO_RESTORE_FAILED -> heldWhy[g.groupId] ?: "원래대로 돌리지 않았어요"
        else -> "휴식 중 적용한 기록이 남아 있어요. 지금 설정은 확인이 필요해요"
    }

    private fun textOf(g: ClimateGroup, m: ClimateMember): String = when {
        g.locked || g.stage != GroupStage.ACTIVE -> textOf(g)
        m.stage == MemberStage.RESULT_UNKNOWN -> "바뀌었는지 확인하지 못했어요"
        else -> textOf(g)
    }

    private fun autoTextOf(g: ClimateGroup): String = when {
        g.locked -> textOf(g)
        g.stage == GroupStage.AUTO_RESTORE_FAILED -> "자동 모드를 되돌렸는지 확인하지 못했어요"
        else -> "휴식 전 자동 모드: ${onOff(g.autoOriginal)}"
    }

    private fun describe(g: ClimateGroup) =
        "${g.autoArea} ${g.stage} 자동 원래 ${g.autoOriginal} 잠금 ${g.locked} " +
            g.members.joinToString { "${it.prop} ${it.area} ${it.stage} ${ClimateValues.show(it.prop, it.original)}→${ClimateValues.show(it.prop, it.target)}" }
}
