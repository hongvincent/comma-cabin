package app.commacabin.control

import app.commacabin.probe.Access
import app.commacabin.probe.ParkingState
import app.commacabin.probe.ProbeError
import app.commacabin.rest.DoorStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Driver seat axes the public SDK reads and can STOP. */
enum class SeatAxis(val label: String) { FORE_AFT("앞뒤"), BACKREST("등받이"), HEIGHT("높이"), TILT("기울기"), LUMBAR("요추") }

/** Debug-only seat preset access through the public SDK. */
interface SeatPresetPort {
    /** checkSeatMemoryCapabilities: access and the preset numbers per area. */
    fun capability(ok: (Access, Map<String, IntRange>) -> Unit, err: (ProbeError) -> Unit)

    /** Read-only getCurrentSeat…Position. */
    fun position(axis: SeatAxis, area: String, ok: (Int?) -> Unit, err: (ProbeError) -> Unit)

    /** selectSeatMemory. The SDK has no completion callback: returning only means the request was handed over. */
    fun select(area: String, number: Int, onFailed: (ProbeError) -> Unit)

    /** moveSeat…(STOP), for an emergency only. */
    fun stop(axis: SeatAxis, area: String, ok: () -> Unit, err: (ProbeError) -> Unit)
}

/**
 * No stage means "done" or "moved": the SDK never reports a finished preset move. Only [CONFIRMED] releases the
 * request lock, and only after the operator checked the request's handling in outside logs and every seat area
 * standing still; closing the observation, a timeout or the call returning never does.
 */
enum class PresetStage(val label: String) {
    REQUESTED("요청함 · 결과 미확인"),
    CLOSED("관측 종료 · 결과 미확인 · 잠금 유지"),
    CONFIRMED("운영자 확인(외부 처리 로그·전 영역 정지) · 자세 결과 미확인"),
}

/**
 * [before]: the SDK seat values read before the request (axis name → value or read error). [error]: an onFailed
 * or a throw of this request; it does not release the lock. [basis]: what the operator checked for [CONFIRMED].
 */
data class PresetJob(
    val jobId: String,
    val area: String,
    val number: Int,
    val before: Map<String, String>,
    val stage: PresetStage,
    val requestedAt: Long,
    val error: String? = null,
    val basis: String? = null,
    val confirmedAt: Long? = null,
)

interface PresetStore {
    /** null = the file exists but cannot be read. */
    fun load(): List<PresetJob>?
    fun save(jobs: List<PresetJob>): Boolean
}

/** Diagnostic-only record, separate from every product record. Never deleted automatically. */
class FilePresetStore(private val file: File, private val syncDirectory: (File) -> Unit = SYNC_DIRECTORY) : PresetStore {
    override fun load(): List<PresetJob>? {
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            require(root.getInt("formatVersion") == 1)
            val list = root.getJSONArray("jobs")
            (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                val before = o.getJSONObject("before")
                PresetJob(
                    jobId = o.getString("jobId"),
                    area = o.getString("area"),
                    number = o.getInt("number"),
                    before = before.keys().asSequence().associateWith { before.getString(it) },
                    stage = PresetStage.valueOf(o.getString("stage")),
                    requestedAt = o.getLong("requestedAt"),
                    error = o.text("error"),
                    basis = o.text("basis"),
                    confirmedAt = if (o.isNull("confirmedAt")) null else o.getLong("confirmedAt"),
                )
            }
        }.getOrNull()
    }

    override fun save(jobs: List<PresetJob>): Boolean = runCatching {
        val list = JSONArray()
        jobs.forEach { j ->
            list.put(
                JSONObject()
                    .put("jobId", j.jobId).put("area", j.area).put("number", j.number)
                    .put("before", JSONObject(j.before)).put("stage", j.stage.name)
                    .put("requestedAt", j.requestedAt).put("error", j.error ?: JSONObject.NULL)
                    .put("basis", j.basis ?: JSONObject.NULL).put("confirmedAt", j.confirmedAt ?: JSONObject.NULL),
            )
        }
        writeDurably(file, JSONObject().put("formatVersion", 1).put("jobs", list).toString(2), syncDirectory)
    }.isSuccess

    /** Absent (older record) or null → null. */
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else getString(key)

    companion object {
        const val FILE_NAME = "seat-preset-probe.json"
    }
}

data class SeatPresetUi(
    val capability: String = "아직 조회하지 않았어요",
    val numbers: IntRange? = null,
    val positions: Map<SeatAxis, String> = emptyMap(),
    val jobs: List<PresetJob> = emptyList(),
    val problem: String? = null,
    val message: String = "",
    val log: List<String> = emptyList(),
) {
    /** The first request the operator has not confirmed yet: it locks every new select. */
    val unsettled: PresetJob? get() = jobs.firstOrNull { it.stage != PresetStage.CONFIRMED }
}

/**
 * Debug-only check of the public driver seat preset select. A select is sent only while the screen is shown,
 * parked, the passenger door closed, nothing else in the way ([externalBlock]), the number within the capability
 * and every earlier request confirmed by the operator; the diagnostic record is saved first (failure = no call).
 * Every record change reads the file again and changes only its own job, so a late answer to an Activity that is
 * gone neither wipes a newer Activity's job nor touches a closed screen. Main thread only.
 */
class SeatPresetBench(
    private val port: SeatPresetPort,
    private val store: PresetStore,
    private val parking: () -> ParkingState,
    private val door: () -> DoorStatus,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Rest, product work or the other debug tools (also their records) in the way: no select meanwhile. */
    private val externalBlock: () -> String? = { null },
) {
    var state = SeatPresetUi()
        private set
    var onState: (SeatPresetUi) -> Unit = {}

    private var closed = false
    private var hidden = true

    fun start() {
        val loaded = store.load()
        if (loaded == null) {
            update { copy(problem = "진단 기록 파일을 읽을 수 없어 프리셋 요청을 막았어요") }
            note("진단 기록 읽기 실패: 파일을 그대로 두고 요청을 막음")
        } else {
            update { copy(jobs = loaded) }
        }
    }

    fun onVisible() {
        hidden = false
    }

    fun onHidden() {
        hidden = true
    }

    /** The screen is gone: no new vehicle command and no screen update. A late answer still reaches the record. */
    fun close() {
        closed = true
    }

    /** For the other seat tools and the rest start: a preset request not confirmed yet, or an unreadable record. */
    fun busyReason(): String? = state.problem?.let { "시트 프리셋 진단 기록을 읽을 수 없어요" }
        ?: state.unsettled?.let { "시트 프리셋 요청 #${it.jobId.take(8)}이 운영자 확인 전이에요" }

    /** Read-only. */
    fun checkCapability() {
        if (closed) return
        port.capability({ access, map ->
            if (closed) return@capability
            val numbers = map[AREA]
            val text = "접근 $access, 영역 " + map.entries.joinToString { "${it.key}:${it.value.first}..${it.value.last}" }.ifEmpty { "없음" }
            update { copy(capability = text, numbers = numbers?.takeIf { access == Access.WRITE || access == Access.READ_WRITE }) }
            note("프리셋 지원: $text")
        }, { e ->
            if (closed) return@capability
            update { copy(capability = "조회 실패: ${e.text()}", numbers = null) }
            note("프리셋 지원 조회 실패: ${e.text()}")
        })
    }

    /** Read-only: every axis the SDK reads, for the record and the screen. */
    fun readPositions() {
        if (closed) return
        SeatAxis.entries.forEach { axis ->
            port.position(axis, AREA, { v ->
                if (closed) return@position
                update { copy(positions = positions + (axis to (v?.toString() ?: "null"))) }
            }, { e ->
                if (closed) return@position
                update { copy(positions = positions + (axis to "조회 실패(${e.type})")) }
            })
        }
        note("SDK 시트 값 조회 요청(${SeatAxis.entries.size}축)")
    }

    fun select(number: Int) {
        if (closed) return
        val numbers = state.numbers
        val unsettled = state.unsettled
        val block = when {
            hidden -> "화면이 보이지 않는 동안에는 요청하지 않아요"
            state.problem != null -> state.problem
            unsettled != null -> "앞선 요청 #${unsettled.jobId.take(8)}을 운영자가 확인하기 전에는 새로 요청하지 않아요"
            !parking().parked -> "P(주차)이고 주행 제한이 없을 때만 요청해요"
            door() != DoorStatus.CLOSED -> "조수석 문이 닫힌 것을 확인할 수 없어 요청하지 않아요"
            numbers == null -> "먼저 [지원 조회]로 쓸 수 있는 번호를 확인해 주세요"
            number !in numbers -> "지원 번호(${numbers.first}..${numbers.last}) 밖이에요"
            else -> externalBlock()
        }
        if (block != null) return reject(block)
        val job = PresetJob(newId(), AREA, number, state.positions.mapKeys { it.key.name }, PresetStage.REQUESTED, clock())
        // The file is read again: an unconfirmed job another Activity wrote also blocks.
        val saved = change { jobs -> if (jobs.any { it.stage != PresetStage.CONFIRMED }) null else jobs + job }
        if (!saved) return reject("진단 기록을 저장하지 못했거나 확인 전 요청이 있어 요청하지 않았어요")
        told("selectSeatMemory($AREA, $number) 요청함 · 결과 미확인 #${job.jobId.take(8)}")
        try {
            port.select(AREA, number) { e -> onFailed(job.jobId, e.text()) }
        } catch (e: RuntimeException) {
            // Whether the request was queued before the throw is unknown: recorded like onFailed, still locked.
            onFailed(job.jobId, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** The operator ends the observation. The result stays unknown and the lock stays. */
    fun closeObservation() {
        if (closed) return
        val open = state.jobs.firstOrNull { it.stage == PresetStage.REQUESTED } ?: return reject("관측 중인 요청이 없어요")
        if (!change { jobs -> jobs.map { if (it.jobId == open.jobId && it.stage == PresetStage.REQUESTED) it.copy(stage = PresetStage.CLOSED) else it } }) {
            return reject("진단 기록을 저장하지 못했어요")
        }
        told("관측 종료 #${open.jobId.take(8)} · 결과 미확인 · 잠금 유지")
    }

    /**
     * The operator checked, outside the app, the handling of this request in the logs and every seat area at
     * moves 0 / actuator 0, and writes down where ([basis]). Releases the lock; says nothing about the pose.
     */
    fun confirm(basis: String) {
        if (closed) return
        val target = state.unsettled ?: return reject("확인할 요청이 없어요")
        if (target.stage != PresetStage.CLOSED) return reject("먼저 [관측 종료]로 관측을 닫아 주세요")
        if (basis.isBlank()) return reject("근거(외부 처리 로그, 전 영역 moves 0 · actuator 0)를 적어 주세요")
        val at = clock()
        val ok = change { jobs ->
            jobs.map { if (it.jobId == target.jobId && it.stage == PresetStage.CLOSED) it.copy(stage = PresetStage.CONFIRMED, basis = basis.trim(), confirmedAt = at) else it }
        }
        if (!ok) return reject("진단 기록을 저장하지 못했어요")
        told("운영자 확인 #${target.jobId.take(8)}: ${basis.trim()} · 자세 결과 미확인")
    }

    /** Emergency only: STOP on every axis the SDK moves. Not a restore; no other check is in its way. */
    fun stopAll() {
        if (closed) return
        note("모든 축 STOP 요청")
        SeatAxis.entries.forEach { axis ->
            port.stop(axis, AREA, { note("${axis.label} STOP 응답") }, { e -> note("${axis.label} STOP 실패: ${e.text()}") })
        }
    }

    /** Only this job's error is written, also after [close]; the stage and the lock stay as they are. */
    private fun onFailed(jobId: String, error: String) {
        val late = state.jobs.firstOrNull { it.jobId == jobId }?.stage != PresetStage.REQUESTED
        change { jobs -> jobs.map { if (it.jobId == jobId) it.copy(error = error) else it } }
        note("onFailed #${jobId.take(8)}${if (late) "(늦은 응답)" else ""}: $error")
    }

    /** Reads the file again, applies [edit] (null = no change) and saves. The screen follows only while open. */
    private fun change(edit: (List<PresetJob>) -> List<PresetJob>?): Boolean {
        val now = store.load() ?: return false
        val next = edit(now) ?: return false
        if (!store.save(next)) return false
        if (!closed) update { copy(jobs = next) }
        return true
    }

    private fun reject(text: String) {
        note("거절: $text")
        if (!closed) update { copy(message = text) }
    }

    /** A step went through: logged and shown in place of an older refusal. */
    private fun told(text: String) {
        note(text)
        if (!closed) update { copy(message = text) }
    }

    private fun note(line: String) {
        log(line)
        if (!closed) update { copy(log = (log + line).takeLast(LOG_LIMIT)) }
    }

    private inline fun update(change: SeatPresetUi.() -> SeatPresetUi) {
        state = state.change()
        onState(state)
    }

    companion object {
        const val AREA = "ROW_1_LEFT"
        private const val LOG_LIMIT = 30
    }
}
