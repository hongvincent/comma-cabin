package app.commacabin.lighting

import app.commacabin.probe.Access
import app.commacabin.probe.Cancellable
import app.commacabin.probe.ParkingState
import app.commacabin.probe.ProbeError
import app.commacabin.probe.RecordLease
import app.commacabin.probe.Scheduler
import app.commacabin.probe.SwitchState
import java.util.UUID

const val LIGHT_READ_TIMEOUT_MS = 5_000L
const val LIGHT_WRITE_TIMEOUT_MS = 5_000L
const val LIGHT_SETTLE_MS = 500L

enum class LightResult { WORKING, APPLIED, SKIPPED, UNKNOWN, RESTORED, HELD, USER_CHANGED, FAILED, LOCKED }

/** One line of the product screens. No raw SDK value or id: brightness original as a number, color as a swatch. */
data class LightItemUi(val prop: LightProp, val area: String, val original: Int?, val result: LightResult, val text: String)

data class LightingUi(
    /** Resting line of the latest rest. */
    val restLine: String? = null,
    /** What happened to each item of the latest rest (applied, skipped, restored…). */
    val items: List<LightItemUi> = emptyList(),
    /** Records not cleaned up yet: shown with [원래대로] [기록 지우기] [나중에]. */
    val open: List<LightItemUi> = emptyList(),
    /** Results for records of earlier rests (restored, held…); never mixed into [items] or [restLine]. */
    val earlier: List<LightItemUi> = emptyList(),
    val restoreBlock: String? = null,
    val discardBlock: String? = null,
    val deferred: Boolean = false,
    val problem: String? = null,
    val working: Boolean = false,
    val notice: String? = null,
)

/**
 * Product mood lighting of a rest: brightness down to [LightValues.TARGET_BRIGHTNESS] and the warm color
 * on every supported area whose switch reads ON right before the command, and back to the original when
 * the rest ends. Only what this app changed is restored. Lives for the process (not the Activity), so an
 * answer or a restore already under way finishes after the screen is gone. Main thread only.
 *
 * Contract (product spec 6-1, 6-3): a durable record before every command (failure = no command), one
 * request at a time per property and area, a separate read after the terminal callback (the echo proves
 * nothing), a timeout leaves the request open and the record locked, a record that never answered in an
 * earlier run stays locked, no record is added or deleted after a storage fault, a damaged file is kept and
 * blocks lighting, user changes are never overwritten automatically, and nothing is written unless parked.
 */
class RestLighting(
    private val port: LightingPort,
    private val store: LightStore,
    private val scheduler: Scheduler,
    private val parking: () -> ParkingState,
    /** Debug write tools busy or holding records (also damaged ones): no product apply while non-null. */
    private val debugBlock: () -> String?,
    private val lease: RecordLease,
    private val log: (String) -> Unit = {},
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    var state = LightingUi()
        private set
    var onState: (LightingUi) -> Unit = {}

    private class Run(val id: Int, var line: String?, var ending: Boolean = false)
    private class Item(val prop: LightProp, val area: String, val bounds: IntRange?, val switchArea: String?, val skip: String?)
    private class Pending(val requestId: String, val timer: Cancellable, var timedOut: Boolean = false)
    private class Restore(val jobId: String, val manual: Boolean)

    private var leaseId = 0
    private var closed = false
    private val alive: Boolean get() = !closed && lease.isHeld(leaseId)

    private var records: List<LightRecord> = emptyList()
    private var loadProblem: String? = null
    private var storageFault: String? = null
    private val pending = mutableMapOf<String, Pending>()
    /** Answered, waiting for the separate read. */
    private val confirming = mutableSetOf<String>()
    /** jobId → run that applied it, in this process. */
    private val jobRun = mutableMapOf<String, Int>()
    /** Applies of an ended run: restore once their answer and read are in. */
    private val restoreAfterAnswer = mutableSetOf<String>()
    /** Apply chain continuation, called once the item's result is known or its request timed out. */
    private val waiters = mutableMapOf<String, () -> Unit>()
    private val observers = mutableMapOf<String, AutoCloseable>()
    /** Jobs whose differing listener value is being checked by a read; true = another differing value came meanwhile. */
    private val checking = mutableMapOf<String, Boolean>()
    private val runItems = linkedMapOf<String, LightItemUi>()
    private val earlierItems = linkedMapOf<String, LightItemUi>()
    /**
     * Jobs that lost parking while open (applying, confirming, waiting to restore, restoring). Answers still
     * come in, but no new restore is sent for them until an explicit [원래대로], also after P comes back.
     */
    private val parkLost = mutableSetOf<String>()
    private val heldWhy = mutableMapOf<String, String>()
    private val restoreQueue = ArrayDeque<Restore>()
    private var restoring: String? = null
    private var run: Run? = null
    private var runSeq = 0
    private var opSeq = 0
    private var preparing = false
    private var deferred = false
    private var notice: String? = null

    fun start() {
        if (closed || leaseId != 0) return
        leaseId = lease.acquire()
        when (val loaded = store.load()) {
            LightLoad.Empty -> log("조명 기록 없음")
            is LightLoad.Found -> {
                // A request that never answered in an earlier run can never be proven finished: lock it for good.
                val marked = loaded.records.map { if (it.responseReceived) it else it.copy(priorRequestUnresolved = true) }
                records = loaded.records
                if (marked != loaded.records) persist(marked)
                records = marked
                marked.forEach { log("조명 기록 불러옴: ${describe(it)}") }
            }
            is LightLoad.Unreadable -> {
                loadProblem = loaded.reason
                log("조명 기록 읽기 실패: ${loaded.reason}. 파일을 그대로 두고 조명을 막아요")
            }
        }
        publish()
    }

    fun close() {
        if (closed) return
        closed = true
        pending.values.forEach { it.timer.cancel() }
        observers.values.forEach { runCatching { it.close() } }
        observers.clear()
    }

    /** Losing parking cancels the preparation and remembers every open job of this process (see [parkLost]). */
    fun onParking(next: ParkingState) {
        if (!next.parked) parkingLost("주차 조건 상실(${next.gear}, 주행 제한 ${next.uxRestricted})")
        publish()
    }

    private fun parkingLost(why: String) {
        if (preparing) {
            preparing = false
            opSeq++
            log("$why: 무드등 적용 준비 취소")
        }
        val open = pending.keys + confirming + restoreAfterAnswer + restoreQueue.map { it.jobId } + listOfNotNull(restoring) +
            jobRun.filterValues { it == run?.id }.keys.filter { record(it) != null }
        if (open.isNotEmpty() && parkLost.addAll(open)) log("$why: ${open.size}개 작업은 [원래대로] 전까지 새로 되돌리지 않음")
    }

    /** For the debug tools: product lighting is preparing, waiting, restoring or holds records. */
    fun busyReason(): String? = when {
        !alive -> null
        loadProblem != null -> "휴식 무드등 기록을 읽을 수 없어요"
        preparing || pending.isNotEmpty() || confirming.isNotEmpty() || restoring != null || restoreQueue.isNotEmpty() -> "휴식 무드등을 맞추거나 되돌리는 중이에요"
        records.isNotEmpty() -> "정리하지 않은 휴식 무드등 기록이 있어요"
        else -> null
    }

    /**
     * A rest really started. Returns this owner's run id, unique for the process, that [onRestEnd] takes: an
     * end from an older Activity or session carries another id and changes nothing. A run still open is ended
     * first. Reads and writes only if [enabled].
     */
    fun onRestStart(enabled: Boolean): Int {
        run?.takeIf { !it.ending }?.let { onRestEnd(it.id, "새 휴식 시작", parkingLost = !parking().parked) }
        val id = ++runSeq
        runItems.clear()
        notice = null
        val r = Run(id, line = null)
        run = r
        if (!enabled) {
            r.line = "무드등 조정을 꺼 두었어요"
            log("run $id: 무드등 조정 꺼짐(쓰기 0)")
            return id.also { publish() }
        }
        applyBlock()?.let {
            r.line = it
            log("run $id: $it")
            return id.also { publish() }
        }
        preparing = true
        val token = ++opSeq
        log("run $id: 무드등 적용 준비")
        publish()
        prepare(token, id)
        return id
    }

    /**
     * The rest of [runId] ended (after its sound stopped). New preparation stops at once; requests already sent
     * finish and their read comes first; then what this run changed goes back, one item at a time.
     */
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
        val jobs = jobRun.filterValues { it == runId }.keys.filter { record(it) != null }
        log("run $runId 종료($reason): 이번 휴식이 바꾼 ${jobs.size}개 항목 복원")
        jobs.filter { pending.containsKey(it) || it in confirming }.forEach { restoreAfterAnswer += it }
        enqueue(jobs.filter { !pending.containsKey(it) && it !in confirming }, manual = false)
        publish()
    }

    /** [원래대로]: every unlocked record, with the checks right before each command. */
    fun restoreRecords() {
        if (!alive) return
        restoreBlock()?.let { return say(it) }
        deferred = false
        earlierItems.clear()
        val jobs = records.filter { !it.locked && !pending.containsKey(it.jobId) }.map { it.jobId }
        parkLost -= jobs.toSet()
        log("[원래대로] ${jobs.size}개 항목")
        enqueue(jobs, manual = true)
        publish()
    }

    /** [기록 지우기] after the screen's confirmation. The vehicle is not touched. Locked records stay. */
    fun discardRecords() {
        if (!alive) return
        discardBlock()?.let { return say(it) }
        val keep = records.filter { it.locked }
        val gone = records - keep.toSet()
        if (!persist(keep)) return say("기록을 지우지 못했어요")
        earlierItems.clear()
        gone.forEach { closeObserver(it.jobId) }
        log("사용자가 조명 기록 ${gone.size}개를 지움(차량은 그대로)")
        say(if (keep.isEmpty()) "기록을 지웠어요. 무드등은 지금 상태 그대로예요" else "지울 수 있는 기록만 지웠어요")
    }

    /**
     * [나중에]: fold the prompt; the records stay and nothing is read or written. The screens keep a small
     * [남은 무드등 기록 보기] that unfolds it again ([showRecords]); the next rest end and a new process
     * (deferred is not stored) show it unfolded.
     */
    fun defer() {
        deferred = true
        publish()
    }

    /** [남은 무드등 기록 보기]: unfold again. Display only, no read or write. */
    fun showRecords() {
        deferred = false
        publish()
    }

    // --- apply ---

    private fun applyBlock(): String? = when {
        !alive -> "무드등을 조정할 수 없어요"
        loadProblem != null -> "무드등 기록을 읽을 수 없어 이번에는 무드등을 조정하지 않아요"
        storageFault != null -> "무드등 기록을 저장할 수 없어 이번에는 무드등을 조정하지 않아요"
        else -> debugBlock()?.let { "개발 진단 작업이나 기록이 남아 있어 무드등을 조정하지 않아요" }
    }

    private fun current(token: Int, runId: Int) = alive && token == opSeq && run?.id == runId && run?.ending == false

    private fun prepare(token: Int, runId: Int) {
        askOrNull(token, runId, "밝기 지원", { ok, err -> port.brightnessCapability({ a, m -> ok(a to m) }, err) }) { bright, brightErr ->
            askOrNull(token, runId, "색 지원", { ok, err -> port.colorCapability({ a, l -> ok(a to l) }, err) }) { color, colorErr ->
                askOrNull(token, runId, "스위치 지원", { ok, err -> port.switchCapability({ a, l -> ok(a to l) }, err) }) { sw, swErr ->
                    val items = items(bright, brightErr, color, colorErr, sw, swErr)
                    next(token, runId, items, 0)
                }
            }
        }
    }

    private fun items(
        bright: Pair<Access, Map<String, IntRange>>?, brightErr: String?,
        color: Pair<Access, List<String>>?, colorErr: String?,
        sw: Pair<Access, List<String>>?, swErr: String?,
    ): List<Item> {
        val switchAreas = sw?.takeIf { it.first == Access.READ || it.first == Access.READ_WRITE }?.second
        val switchProblem = if (switchAreas == null) "무드등 스위치를 확인할 수 없어 조정하지 않았어요${swErr?.let { "($it)" } ?: ""}" else null
        fun item(prop: LightProp, area: String, bounds: IntRange?, access: Access): Item {
            val switchArea = switchAreas?.let { switchFor(area, it) }
            val skip = when {
                access != Access.READ_WRITE -> "이 차에서는 바꿀 수 없어 조정하지 않았어요"
                switchProblem != null -> switchProblem
                switchArea == null -> "무드등 스위치 영역을 정확히 알 수 없어 건너뛰었어요"
                records.any { it.prop == prop && it.area == area } -> "정리하지 않은 이전 기록이 있어 이번에는 조정하지 않았어요"
                else -> null
            }
            return Item(prop, area, bounds, switchArea, skip)
        }
        val list = mutableListOf<Item>()
        if (bright == null) list += Item(LightProp.BRIGHTNESS, "", null, null, "밝기 지원을 확인하지 못해 조정하지 않았어요${brightErr?.let { "($it)" } ?: ""}")
        else if (bright.second.isEmpty()) list += Item(LightProp.BRIGHTNESS, "", null, null, "이 차는 무드등 밝기 조정을 지원하지 않아 생략했어요")
        else bright.second.forEach { (area, bounds) -> list += item(LightProp.BRIGHTNESS, area, bounds, bright.first) }
        if (color == null) list += Item(LightProp.COLOR, "", null, null, "색 지원을 확인하지 못해 조정하지 않았어요${colorErr?.let { "($it)" } ?: ""}")
        else if (color.second.isEmpty()) list += Item(LightProp.COLOR, "", null, null, "이 차는 무드등 색 조정을 지원하지 않아 생략했어요")
        else color.second.forEach { area -> list += item(LightProp.COLOR, area, null, color.first) }
        return list
    }

    /** The one switch area containing every seat of [area]; none or several is ambiguous (null, the item is skipped). */
    private fun switchFor(area: String, switchAreas: List<String>): String? {
        val seats = area.split('+').toSet()
        return switchAreas.filter { it.split('+').toSet().containsAll(seats) }.singleOrNull()
    }

    private fun next(token: Int, runId: Int, items: List<Item>, i: Int) {
        if (!current(token, runId)) return
        if (i == items.size) {
            preparing = false
            log("run $runId: 무드등 적용 단계 끝")
            return publish()
        }
        val item = items[i]
        val then = { next(token, runId, items, i + 1) }
        item.skip?.let {
            mark(item.prop, item.area, null, LightResult.SKIPPED, it)
            return then()
        }
        applyItem(token, runId, item, then)
    }

    /** Current value and target first, then the switch as the very last read, then the record, a final check and the command. */
    private fun applyItem(token: Int, runId: Int, item: Item, then: () -> Unit) {
        askOrNull(token, runId, "${item.prop.label} 현재값", { ok, err -> port.read(item.prop, item.area, ok, err) }) { cur, readErr ->
            val target = cur?.let { targetFor(item, it) }
            val why = when {
                cur == null -> "현재 값을 읽지 못해 조정하지 않았어요${readErr?.let { "($it)" } ?: ""}"
                item.prop == LightProp.BRIGHTNESS && (item.bounds == null || cur !in item.bounds) -> "현재 밝기가 지원 범위 밖이라 조정하지 않았어요"
                item.prop == LightProp.COLOR && !LightValues.alphaAllowed(cur) -> "현재 색 값을 해석할 수 없어 조정하지 않았어요"
                target == null -> if (item.prop == LightProp.BRIGHTNESS) "이미 충분히 어두워 그대로 두었어요" else "이미 따뜻한 색이라 그대로 두었어요"
                else -> null
            }
            if (why != null || cur == null || target == null) {
                mark(item.prop, item.area, cur, LightResult.SKIPPED, why ?: "조정하지 않았어요")
                return@askOrNull then()
            }
            askOrNull(token, runId, "${item.prop.label} 스위치", { ok, err -> port.readSwitch(item.switchArea!!, ok, err) }) { sw, swErr ->
                val block = when {
                    sw == null -> "무드등 상태를 확인하지 못해 조정하지 않았어요${swErr?.let { "($it)" } ?: ""}"
                    sw != SwitchState.ON -> "무드등이 꺼져 있어 조정하지 않았어요"
                    else -> commandBlock(item.prop, item.area)
                }
                if (block != null) {
                    mark(item.prop, item.area, cur, LightResult.SKIPPED, block)
                    return@askOrNull then()
                }
                val record = LightRecord(newId(), item.prop, item.area, cur, target, LightStage.APPLY_REQUESTED, newId(), target, responseReceived = false)
                if (!persist(records + record)) {
                    mark(item.prop, item.area, cur, LightResult.SKIPPED, "기록을 저장하지 못해 조정하지 않았어요")
                    return@askOrNull then()
                }
                if (!current(token, runId) || commandBlock(item.prop, item.area, record.jobId) != null) {
                    // Certainly not sent: the record goes (if the file can still change) and nothing is written.
                    persist(records.filter { it.jobId != record.jobId })
                    mark(item.prop, item.area, cur, LightResult.SKIPPED, "명령 직전 조건이 바뀌어 조정하지 않았어요")
                    return@askOrNull if (current(token, runId)) then() else Unit
                }
                jobRun[record.jobId] = runId
                waiters[record.jobId] = { if (current(token, runId)) then() }
                mark(record, LightResult.WORKING, "맞추는 중이에요")
                send(record)
            }
        }
    }

    private fun targetFor(item: Item, cur: Int): Int? = when (item.prop) {
        LightProp.BRIGHTNESS -> item.bounds?.let { LightValues.TARGET_BRIGHTNESS.coerceIn(it.first, it.last) }?.takeIf { it < cur }
        LightProp.COLOR -> LightValues.WARM_COLOR.takeIf { !LightValues.same(LightProp.COLOR, cur, it) }
    }

    /** Right before a command: parked, files writable, debug idle, and nothing else open on the same property and area. */
    private fun commandBlock(prop: LightProp, area: String, own: String? = null): String? = when {
        !parking().parked -> "주차(P, 주행 제한 없음)가 확인되지 않아 조정하지 않았어요"
        storageFault != null -> "기록 저장 오류로 조정하지 않았어요"
        debugBlock() != null -> "개발 진단 작업이 있어 조정하지 않았어요"
        records.any { it.prop == prop && it.area == area && it.jobId != own } -> "같은 항목의 기록이 남아 있어 조정하지 않았어요"
        else -> null
    }

    // --- requests and answers ---

    private fun send(r: LightRecord) {
        val requestId = r.requestId
        val timer = scheduler.schedule(LIGHT_WRITE_TIMEOUT_MS) { onTimeout(r.jobId, requestId) }
        pending[r.jobId] = Pending(requestId, timer)
        log("${r.prop.label} ${if (r.stage == LightStage.RESTORE_REQUESTED) "복원" else "적용"} 요청 #${requestId.take(8)} ${r.area} payload=${show(r.prop, r.payload)} (원래 ${show(r.prop, r.original)})")
        publish()
        port.write(r.prop, r.area, r.payload, { echo -> onAnswer(r.jobId, requestId, "onComplete(${echo?.let { show(r.prop, it) }})", ok = true, echo = echo) }, { e ->
            onAnswer(r.jobId, requestId, "onFailed(${e.text()})", ok = false, echo = null)
        })
    }

    /** The request stays open and its record locked; the next item may go on. */
    private fun onTimeout(jobId: String, requestId: String) {
        val p = pending[jobId]?.takeIf { it.requestId == requestId } ?: return
        p.timedOut = true
        val r = record(jobId) ?: return
        log("${r.prop.label} #${requestId.take(8)}: ${LIGHT_WRITE_TIMEOUT_MS}ms 동안 응답 없음 → 결과 불명, 기록 잠금 유지")
        mark(r, LightResult.UNKNOWN, "응답을 기다리고 있어요(결과를 아직 몰라요)")
        waiters.remove(jobId)?.invoke()
        if (restoring == jobId) {
            restoring = null
            restoreNext()
        }
        publish()
    }

    private fun onAnswer(jobId: String, requestId: String, result: String, ok: Boolean, echo: Int?) {
        if (!alive) return log("ignored lighting answer after close or lease loss #${requestId.take(8)} $result")
        val p = pending[jobId]
        if (p == null || p.requestId != requestId) return log("ignored stale lighting answer #${requestId.take(8)} $result")
        pending.remove(jobId)
        p.timer.cancel()
        val r = record(jobId)?.takeIf { it.requestId == requestId } ?: return
        log("${r.prop.label} 응답 #${requestId.take(8)} $result${if (p.timedOut) " (시간 초과 뒤)" else ""}")
        persist(replace(r.copy(responseReceived = true, lastEcho = echo)))
        confirming += jobId
        publish()
        scheduler.schedule(LIGHT_SETTLE_MS) { confirm(jobId, requestId, ok) }
    }

    /** The separate read after the terminal callback decides; the echo never does. */
    private fun confirm(jobId: String, requestId: String, ok: Boolean) {
        if (!alive) return
        readOrNull(record(jobId)?.prop ?: return, record(jobId)!!.area) { value, readErr ->
            confirming -= jobId
            val r = record(jobId)?.takeIf { it.requestId == requestId } ?: return@readOrNull publish()
            val read = value?.let { show(r.prop, it) } ?: "조회 실패($readErr)"
            if (r.stage == LightStage.RESTORE_REQUESTED) {
                if (ok && LightValues.same(r.prop, value, r.original)) {
                    if (storageFault == null && persist(records.filter { it.jobId != jobId })) {
                        closeObserver(jobId)
                        log("${r.prop.label} 복원 확인: 재조회 $read = 원래 ${show(r.prop, r.original)}. 기록 삭제")
                        mark(r, LightResult.RESTORED, "원래대로 돌렸어요")
                    } else {
                        persist(replace(r.copy(responseReceived = true, lastRead = value)))
                        mark(r, LightResult.HELD, "원래대로 돌렸지만 기록 저장 오류로 기록을 지우지 못했어요")
                    }
                } else {
                    persist(replace(r.copy(stage = LightStage.RESTORE_FAILED, responseReceived = true, lastRead = value)))
                    log("${r.prop.label} 복원 실패/불명: 재조회 $read(원래 ${show(r.prop, r.original)})")
                    mark(r, LightResult.FAILED, "되돌렸는지 확인하지 못했어요")
                }
                if (restoring == jobId) restoring = null
                return@readOrNull restoreNext()
            }
            val confirmed = ok && LightValues.same(r.prop, value, r.target)
            val next = r.copy(stage = if (confirmed) LightStage.APPLY_CONFIRMED else LightStage.RESULT_UNKNOWN, responseReceived = true, lastRead = value)
            persist(replace(next))
            log("${r.prop.label} ${if (confirmed) "적용 확인" else "결과 불명"}: 요청 ${show(r.prop, r.target)}, 재조회 $read")
            if (confirmed) {
                mark(next, LightResult.APPLIED, if (r.prop == LightProp.BRIGHTNESS) "${r.target}(으)로 낮췄어요" else "따뜻한 색으로 바꿨어요")
                observe(next)
            } else {
                mark(next, LightResult.UNKNOWN, "바뀌었는지 확인하지 못했어요")
            }
            val runId = jobRun[jobId]
            if (restoreAfterAnswer.remove(jobId) || run?.id != runId || run?.ending == true) enqueue(listOf(jobId), manual = false)
            waiters.remove(jobId)?.invoke()
            publish()
        }
    }

    /**
     * User changes: only for a confirmed apply with nothing of ours in flight. A listener value is not proof of
     * the vehicle value: right after registering, the service can replay an old cached value (seen: 70 while the
     * vehicle read 30). So a differing value only starts a separate read, and that read decides.
     */
    private fun observe(r: LightRecord) {
        if (observers.containsKey(r.jobId)) return
        try {
            observers[r.jobId] = port.observe(r.prop, r.area) { v -> onListener(r.jobId, v) }
        } catch (e: RuntimeException) {
            log("${r.prop.label} 리스너 등록 실패: ${e.javaClass.name}. 종료 직전 조회로만 사용자 변경을 확인")
        }
    }

    private fun onListener(jobId: String, value: Int?) {
        if (!alive || value == null) return
        val r = record(jobId)?.takeIf { settled(it) } ?: return
        if (LightValues.same(r.prop, value, r.target)) return
        if (jobId in checking) {
            checking[jobId] = true
            return
        }
        checkUserChange(r, value)
    }

    /** A confirmed apply with nothing of ours open on it: the only state in which a user change is judged. */
    private fun settled(r: LightRecord) = r.stage == LightStage.APPLY_CONFIRMED && !pending.containsKey(r.jobId) &&
        r.jobId !in confirming && restoring != r.jobId && restoreQueue.none { it.jobId == r.jobId }

    /**
     * Reads the current value after a differing listener value. The answer counts only if this owner is still
     * valid and the record is still the same request in the same settled state; otherwise it is dropped so a
     * late read never overwrites an end, a restore or a newer request. A failed read decides nothing (the read
     * right before the restore still protects the user).
     */
    private fun checkUserChange(r: LightRecord, heard: Int) {
        checking[r.jobId] = false
        log("${r.prop.label} 리스너 값 ${show(r.prop, heard)} ≠ 앱 값 ${show(r.prop, r.target)}: 실제 현재값을 다시 읽어 확인")
        readOrNull(r.prop, r.area) { cur, readErr ->
            val again = checking.remove(r.jobId) == true
            val now = record(r.jobId)?.takeIf { it.requestId == r.requestId && settled(it) }
                ?: return@readOrNull log("${r.prop.label} 확인 조회 결과 무시: 그 사이 작업 상태가 바뀜")
            when {
                cur == null -> log("${r.prop.label} 확인 조회 실패($readErr): 사용자 변경으로 정하지 않음")
                LightValues.same(r.prop, cur, r.target) -> {
                    log("${r.prop.label} 실제 ${show(r.prop, cur)} = 앱 값: 리스너 값 ${show(r.prop, heard)}은 사용자 변경이 아님")
                    if (again) checkUserChange(now, heard)
                }
                else -> {
                    persist(replace(now.copy(stage = LightStage.USER_CHANGED, lastRead = cur)))
                    closeObserver(r.jobId)
                    log("${r.prop.label} 사용자 변경 확인: 실제 ${show(r.prop, cur)}(앱 값 ${show(r.prop, r.target)}). 더 쓰거나 자동으로 되돌리지 않아요")
                    mark(now, LightResult.USER_CHANGED, "직접 바꾼 값이라 그대로 두었어요")
                }
            }
        }
    }

    // --- restore ---

    private fun restoreBlock(): String? = when {
        loadProblem != null -> "무드등 기록을 읽을 수 없어 되돌릴 수 없어요"
        storageFault != null -> "기록 저장 오류가 있어 되돌릴 수 없어요"
        preparing || restoring != null || restoreQueue.isNotEmpty() -> "무드등 작업이 진행 중이에요"
        records.none { !it.locked && !pending.containsKey(it.jobId) } -> if (records.isEmpty()) "되돌릴 기록이 없어요" else "결과를 모르는 요청이 있어 앱에서 되돌릴 수 없어요. 차량 설정에서 직접 맞춰 주세요"
        !parking().parked -> "P(주차)이고 주행 제한이 없을 때 되돌릴 수 있어요"
        debugBlock() != null -> "개발 진단 작업이나 기록이 남아 있어 되돌릴 수 없어요"
        else -> null
    }

    private fun discardBlock(): String? = when {
        loadProblem != null -> "기록 파일에 문제가 있어 앱에서 지우지 않아요"
        storageFault != null -> "기록 저장 오류가 있어 지울 수 없어요"
        preparing || restoring != null || restoreQueue.isNotEmpty() || pending.isNotEmpty() || confirming.isNotEmpty() -> "진행 중인 무드등 작업이 끝난 뒤에 지울 수 있어요"
        records.none { !it.locked } -> if (records.isEmpty()) "지울 기록이 없어요" else "결과를 모르는 요청이 있는 기록은 지울 수 없어요"
        else -> null
    }

    private fun enqueue(jobs: List<String>, manual: Boolean) {
        jobs.filter { j -> restoreQueue.none { it.jobId == j } && restoring != j }.forEach { restoreQueue += Restore(it, manual) }
        restoreNext()
    }

    /**
     * One record at a time. Each restore checks again: the property's capability (read-write, the exact area,
     * the original brightness in range or a valid color), the current value (a value that is neither the
     * app's nor the original is the user's), then the switch as the last read, then the record, a final check
     * and the command. Any failure sends nothing and keeps the record.
     */
    private fun restoreNext() {
        if (!alive || restoring != null) return publish()
        val job = restoreQueue.removeFirstOrNull() ?: return publish()
        val r = record(job.jobId) ?: return restoreNext()
        if (pending.containsKey(r.jobId) || r.jobId in confirming) {
            restoreAfterAnswer += r.jobId
            return restoreNext()
        }
        if (r.locked) {
            mark(r, LightResult.LOCKED, "결과를 모르는 요청이 있어 앱에서 되돌리지 않아요. 차량 설정에서 직접 맞춰 주세요")
            return restoreNext()
        }
        if (r.stage == LightStage.USER_CHANGED && !job.manual) {
            mark(r, LightResult.USER_CHANGED, "직접 바꾼 값이라 그대로 두었어요")
            return restoreNext()
        }
        restoreHold(r.jobId)?.let { return hold(r, it) }
        restoring = r.jobId
        publish()
        restoreCapability(r) { capProblem ->
            if (capProblem != null) return@restoreCapability hold(r, capProblem)
            readOrNull(r.prop, r.area) { cur, readErr ->
                val latest = record(r.jobId)?.takeIf { it.requestId == r.requestId } ?: return@readOrNull release()
                if (cur == null) return@readOrNull hold(latest, "현재 값을 읽지 못해 되돌리지 않았어요($readErr)")
                val ours = LightValues.same(r.prop, cur, r.target) || LightValues.same(r.prop, cur, r.original)
                if (!job.manual && !ours) {
                    // Checked again right before the restore: a change the listener missed is still the user's.
                    persist(replace(latest.copy(stage = LightStage.USER_CHANGED, lastRead = cur)))
                    closeObserver(r.jobId)
                    log("${r.prop.label} 종료 직전 조회 ${show(r.prop, cur)} ≠ 앱 값 ${show(r.prop, r.target)}: 사용자 변경으로 보고 되돌리지 않음")
                    mark(latest, LightResult.USER_CHANGED, "직접 바꾼 값이라 그대로 두었어요")
                    return@readOrNull release()
                }
                lastSwitch(r.area) { switchProblem ->
                    val now = record(r.jobId)?.takeIf { it.requestId == r.requestId } ?: return@lastSwitch release()
                    (switchProblem ?: restoreHold(r.jobId))?.let { return@lastSwitch hold(now, it) }
                    val next = now.copy(
                        stage = LightStage.RESTORE_REQUESTED, requestId = newId(), payload = LightValues.restorePayload(r.prop, r.original),
                        responseReceived = false, lastEcho = null, lastRead = cur,
                    )
                    if (!persist(replace(next))) return@lastSwitch release()
                    restoreHold(r.jobId)?.let {
                        // Saved but certainly not sent: back to the record as it was, then held.
                        persist(replace(now))
                        return@lastSwitch hold(now, it)
                    }
                    mark(next, LightResult.WORKING, "되돌리는 중이에요")
                    send(next)
                }
            }
        }
    }

    /** Before a new restore command. [parkLost] counts even when P is back. */
    private fun restoreHold(jobId: String): String? = when {
        !alive -> "무드등을 되돌릴 수 없어요"
        !parking().parked || jobId in parkLost -> "주차 상태가 아니어서 되돌리지 않았어요. P에서 [원래대로]를 눌러 주세요"
        storageFault != null -> "기록 저장 오류로 되돌리지 않았어요"
        // The debug tools only: the product's own busy state is not asked here (no self-block).
        debugBlock() != null -> "개발 진단 작업이나 기록이 남아 있어 되돌리지 않았어요"
        else -> null
    }

    private fun restoreCapability(r: LightRecord, then: (String?) -> Unit) = when (r.prop) {
        LightProp.BRIGHTNESS -> withTimeout({ ok, err -> port.brightnessCapability({ a, m -> ok(a to m) }, err) }) { cap, e ->
            if (!alive) return@withTimeout
            val bounds = cap?.second?.get(r.area)
            then(
                when {
                    cap == null -> "밝기 지원을 확인하지 못해 되돌리지 않았어요($e)"
                    cap.first != Access.READ_WRITE -> "밝기를 바꿀 수 없는 상태라 되돌리지 않았어요"
                    bounds == null -> "기록한 영역을 지금 지원하지 않아 되돌리지 않았어요"
                    r.original !in bounds -> "원래 밝기가 지금 지원 범위 밖이라 되돌리지 않았어요"
                    else -> null
                },
            )
        }
        LightProp.COLOR -> withTimeout({ ok, err -> port.colorCapability({ a, l -> ok(a to l) }, err) }) { cap, e ->
            if (!alive) return@withTimeout
            then(
                when {
                    cap == null -> "색 지원을 확인하지 못해 되돌리지 않았어요($e)"
                    cap.first != Access.READ_WRITE -> "색을 바꿀 수 없는 상태라 되돌리지 않았어요"
                    r.area !in cap.second -> "기록한 영역을 지금 지원하지 않아 되돌리지 않았어요"
                    !LightValues.alphaAllowed(r.original) -> "원래 색 값을 해석할 수 없어 되돌리지 않았어요"
                    else -> null
                },
            )
        }
    }

    /** The switch of [area], read as the last step before a command. null = ON; otherwise why not. */
    private fun lastSwitch(area: String, then: (String?) -> Unit) {
        withTimeout({ ok, err -> port.switchCapability({ a, l -> ok(a to l) }, err) }) { sw, swErr ->
            if (!alive) return@withTimeout
            val switchArea = sw?.takeIf { it.first == Access.READ || it.first == Access.READ_WRITE }?.second?.let { switchFor(area, it) }
                ?: return@withTimeout then("무드등 스위치를 확인하지 못해 되돌리지 않았어요${swErr?.let { "($it)" } ?: ""}")
            withTimeout({ ok, err -> port.readSwitch(switchArea, ok, err) }) { state, _ ->
                if (!alive) return@withTimeout
                then(
                    when (state) {
                        SwitchState.ON -> null
                        null -> "무드등 상태를 확인하지 못해 되돌리지 않았어요"
                        else -> "무드등이 꺼져 있어 되돌리지 않았어요"
                    },
                )
            }
        }
    }

    /** Kept for a later [원래대로]; nothing retries by itself (also not when P comes back). */
    private fun hold(r: LightRecord, why: String) {
        val latest = record(r.jobId) ?: r
        if (latest.stage != LightStage.RESTORE_HELD) persist(replace(latest.copy(stage = LightStage.RESTORE_HELD)))
        closeObserver(r.jobId)
        heldWhy[r.jobId] = why
        log("${r.prop.label} 복원 보류: $why")
        mark(latest, LightResult.HELD, why)
        release()
    }

    private fun release() {
        restoring = null
        restoreNext()
    }

    // --- reads with timeouts ---

    /** A preparation step: late or stale answers are dropped; a failure passes null and the reason on. */
    private fun <T> askOrNull(token: Int, runId: Int, label: String, call: (ok: (T) -> Unit, err: (ProbeError) -> Unit) -> Unit, then: (T?, String?) -> Unit) {
        withTimeout(call) { value: T?, error ->
            if (!current(token, runId)) return@withTimeout log("ignored late lighting read: $label")
            then(value, error)
        }
    }

    private fun readOrNull(prop: LightProp, area: String, then: (Int?, String?) -> Unit) =
        withTimeout<Int?>({ ok, err -> port.read(prop, area, ok, err) }) { v, e -> if (alive) then(v, e) }

    private fun <T> withTimeout(call: (ok: (T) -> Unit, err: (ProbeError) -> Unit) -> Unit, done: (T?, String?) -> Unit) {
        var answered = false
        lateinit var timer: Cancellable
        fun finish(v: T?, e: String?) {
            if (answered) return
            answered = true
            timer.cancel()
            done(v, e)
        }
        timer = scheduler.schedule(LIGHT_READ_TIMEOUT_MS) { finish(null, "${LIGHT_READ_TIMEOUT_MS}ms 응답 없음") }
        try {
            call({ finish(it, null) }, { finish(null, it.text()) })
        } catch (e: RuntimeException) {
            finish(null, "${e.javaClass.name}: ${e.message}")
        }
    }

    // --- records ---

    private fun record(jobId: String) = records.firstOrNull { it.jobId == jobId }

    private fun replace(next: LightRecord) = records.map { if (it.jobId == next.jobId) next else it }

    /**
     * The only way the file changes. A closed or replaced owner never writes; after a storage fault a record
     * may still take an answer (attempted) but none is added or removed. A failure sets the storage fault.
     */
    private fun persist(next: List<LightRecord>): Boolean {
        if (!alive) {
            log("ignored lighting record change after close or lease loss")
            return false
        }
        if (storageFault != null && next.map { it.jobId }.toSet() != records.map { it.jobId }.toSet()) {
            log("기록 저장 오류 뒤라 조명 기록을 추가하거나 지우지 않아요")
            return false
        }
        if (!store.save(next)) {
            storageFault = "기록 파일 저장 실패(${store.lastError})"
            log("조명 기록 저장 실패(${store.lastError}): 이후 새 쓰기·복원·삭제를 막아요")
            publish()
            return false
        }
        records = next
        publish()
        return true
    }

    private fun closeObserver(jobId: String) {
        observers.remove(jobId)?.let { runCatching { it.close() } }
    }

    // --- screen state ---

    /** An item of the latest rest (its line and end screen). */
    private fun mark(prop: LightProp, area: String, original: Int?, result: LightResult, text: String) {
        runItems["${prop.name}|$area"] = LightItemUi(prop, area, original, result, text)
        publish()
    }

    /** A job of the latest rest updates that rest's items; any other job only the earlier-records results. */
    private fun mark(r: LightRecord, result: LightResult, text: String) {
        val item = LightItemUi(r.prop, r.area, r.original, result, text)
        if (run != null && jobRun[r.jobId] == run?.id) runItems["${r.prop.name}|${r.area}"] = item else earlierItems["${r.prop.name}|${r.area}"] = item
        publish()
    }

    private fun say(text: String) {
        notice = text
        publish()
    }

    private fun publish() {
        val r = run
        state = LightingUi(
            restLine = r?.let { line(it) },
            items = runItems.values.toList(),
            open = records.map { rec -> LightItemUi(rec.prop, rec.area, rec.original, resultOf(rec), textOf(rec)) },
            earlier = earlierItems.values.toList(),
            restoreBlock = if (records.isEmpty()) null else restoreBlock(),
            discardBlock = if (records.isEmpty()) null else discardBlock(),
            deferred = deferred,
            problem = loadProblem?.let { "무드등 기록 파일을 읽을 수 없어요. 파일을 그대로 두고 무드등 조정을 멈췄어요" }
                ?: storageFault?.let { "무드등 기록을 저장할 수 없어요. 무드등 조정을 멈췄어요" },
            working = preparing || restoring != null || restoreQueue.isNotEmpty() || pending.isNotEmpty() || confirming.isNotEmpty(),
            notice = notice,
        )
        onState(state)
    }

    private fun line(r: Run): String? {
        r.line?.let { return it }
        val items = runItems.values
        val applied = items.filter { it.result == LightResult.APPLIED }.map { it.prop }.toSet()
        val text = when {
            LightProp.BRIGHTNESS in applied && LightProp.COLOR in applied -> "무드등을 낮추고 따뜻한 색으로 맞췄어요"
            LightProp.BRIGHTNESS in applied -> "무드등 밝기를 낮췄어요"
            LightProp.COLOR in applied -> "무드등을 따뜻한 색으로 바꿨어요"
            preparing || items.any { it.result == LightResult.WORKING } -> "무드등을 맞추는 중이에요"
            else -> items.firstOrNull { it.result == LightResult.UNKNOWN }?.let { "무드등 조정 결과를 확인하지 못했어요" }
                ?: items.firstOrNull()?.text
        }
        val trouble = applied.isNotEmpty() && items.any { it.result == LightResult.UNKNOWN || it.result == LightResult.FAILED }
        return text?.plus(if (trouble) " (일부 항목은 확인하지 못했어요)" else "")
    }

    private fun resultOf(r: LightRecord): LightResult = when {
        r.locked -> LightResult.LOCKED
        r.stage == LightStage.APPLY_CONFIRMED -> LightResult.APPLIED
        r.stage == LightStage.USER_CHANGED -> LightResult.USER_CHANGED
        r.stage == LightStage.RESTORE_HELD -> LightResult.HELD
        r.stage == LightStage.RESTORE_FAILED -> LightResult.FAILED
        r.stage == LightStage.RESTORE_REQUESTED || r.stage == LightStage.APPLY_REQUESTED -> LightResult.WORKING
        else -> LightResult.UNKNOWN
    }

    private fun textOf(r: LightRecord): String = when {
        r.priorRequestUnresolved -> "이전 실행의 요청 결과를 몰라 앱에서 바꾸지 않아요. 차량 설정에서 직접 맞춰 주세요"
        !r.responseReceived -> "응답을 기다리고 있어요(결과를 아직 몰라요)"
        r.stage == LightStage.APPLY_CONFIRMED -> "아직 휴식 값으로 바뀌어 있어요"
        r.stage == LightStage.USER_CHANGED -> "직접 바꾼 값이라 그대로 두었어요"
        r.stage == LightStage.RESTORE_HELD -> heldWhy[r.jobId] ?: "되돌리지 않았어요"
        r.stage == LightStage.RESTORE_FAILED -> "되돌렸는지 확인하지 못했어요"
        r.stage == LightStage.RESULT_UNKNOWN -> "바뀌었는지 확인하지 못했어요"
        else -> "처리 중이에요"
    }

    private fun describe(r: LightRecord) =
        "${r.prop.name} ${r.area} ${r.stage} 원래 ${show(r.prop, r.original)} 목표 ${show(r.prop, r.target)} 응답 ${if (r.responseReceived) "받음" else "없음"}${if (r.priorRequestUnresolved) " 이전 미확인" else ""}"

    private fun show(prop: LightProp, v: Int) = if (prop == LightProp.COLOR) LightValues.hex(v) else v.toString()
}
