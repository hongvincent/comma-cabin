package app.commacabin.control

import app.commacabin.probe.KNOWN_SEATS
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** STEP 7 control targets. The brightness probe keeps its own record (ambient-probe-recovery.json). */
enum class ControlProp(val label: String) {
    SEAT_BACKREST("운전석 등받이"),
    AMBIENT_COLOR("무드등 색"),
    HVAC_FAN_SPEED("풍량"),
    HVAC_TEMPERATURE("목표 온도"),
}

enum class ControlStage(val label: String) {
    APPLY_REQUESTED("변경 요청 중"),
    APPLY_CONFIRMED("적용 확인"),
    RESULT_UNKNOWN("결과 불명"),
    RESTORE_REQUESTED("복원 중"),
    RESTORE_FAILED("복원 실패"),
    USER_CHANGED("사용자 변경"),
}

/**
 * One control job, saved before every command. Values are text in the property's form ([ControlValues]):
 * seat position Int, color "0xAARRGGBB", fan Int, temperature Float (°C). [target] of a seat job is the
 * explored direction (BACKWARD/FORWARD), not a position. [related] holds shared state read before the first
 * command (HVAC power/auto/dual, the other temperature area, the light switch) so side effects can be seen.
 * [requestId] names the latest request; [responseReceived] = its onComplete/onFailed (for a seat: the move
 * and its STOP) arrived in this run.
 */
data class ControlRecord(
    val jobId: String,
    val prop: ControlProp,
    val area: String,
    val original: String,
    val target: String,
    val related: Map<String, String>,
    val stage: ControlStage,
    val requestId: String,
    val responseReceived: Boolean,
    val priorRequestUnresolved: Boolean = false,
    val lastEcho: String? = null,
    val lastRead: String? = null,
    val note: String? = null,
) {
    /** Any stage counts: a stage name never proves that the latest request answered. */
    val awaitingResponse: Boolean get() = !responseReceived
}

object ControlValues {
    private val COLOR = Regex("^0x[0-9A-Fa-f]{8}$")
    private val INT = Regex("^-?[0-9]+$")
    private val LIGHT_SWITCH = setOf("OFF", "ON", "DAYTIME_RUNNING", "AUTOMATIC")
    private val HVAC_FLAGS = setOf("HVAC_POWER", "HVAC_AUTO", "HVAC_DUAL")

    fun color(argb: Int): String = "0x%08X".format(java.util.Locale.ROOT, argb)

    /** Parsed value for comparisons, or null when the text is not exactly the form of [prop]. */
    fun parse(prop: ControlProp, text: String): Any? = when (prop) {
        ControlProp.SEAT_BACKREST, ControlProp.HVAC_FAN_SPEED -> text.takeIf { INT.matches(it) }?.toIntOrNull()
        ControlProp.AMBIENT_COLOR -> text.takeIf { COLOR.matches(it) }?.substring(2)?.toLong(16)?.toInt()
        ControlProp.HVAC_TEMPERATURE -> text.toFloatOrNull()?.takeIf { it.isFinite() }
    }

    fun same(prop: ControlProp, a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val x = parse(prop, a) ?: return false
        return x == parse(prop, b)
    }

    /**
     * Shared state saved before the first command: keys "<NAME> <area>", each required name exactly once,
     * typed values. A bad value is never replaced by a default; the record is refused (or Unreadable).
     */
    fun relatedProblem(prop: ControlProp, related: Map<String, String>): String? {
        val required = when (prop) {
            ControlProp.SEAT_BACKREST -> emptyList()
            ControlProp.AMBIENT_COLOR -> listOf("LIGHT_SWITCH")
            ControlProp.HVAC_FAN_SPEED, ControlProp.HVAC_TEMPERATURE -> HVAC_FLAGS.toList()
        }
        for ((key, value) in related) {
            val parts = key.split(' ')
            if (parts.size != 2) return "보조값 키 형식 오류($key)"
            val (name, area) = parts
            val seats = area.split('+')
            if (seats.any { it !in KNOWN_SEATS } || seats.toSet().size != seats.size) return "보조값 영역 오류($key)"
            val valid = when (name) {
                "LIGHT_SWITCH" -> prop == ControlProp.AMBIENT_COLOR && value in LIGHT_SWITCH
                in HVAC_FLAGS -> (prop == ControlProp.HVAC_FAN_SPEED || prop == ControlProp.HVAC_TEMPERATURE) && (value == "true" || value == "false")
                "HVAC_TEMPERATURE" -> prop == ControlProp.HVAC_TEMPERATURE && value.toFloatOrNull()?.isFinite() == true
                else -> false
            }
            if (!valid) return "보조값 오류($key=$value)"
        }
        val names = related.keys.map { it.substringBefore(' ') }
        required.forEach { name -> if (names.count { it == name } != 1) return "필수 보조값($name)이 없거나 중복" }
        return null
    }
}

sealed interface ControlLoad {
    data object Empty : ControlLoad
    data class Found(val records: List<ControlRecord>) : ControlLoad
    data class Unreadable(val reason: String) : ControlLoad
}

interface ControlStore {
    fun load(): ControlLoad

    /** Returns true only when the whole list is durably written. */
    fun save(records: List<ControlRecord>): Boolean

    /** Why the last save failed, for the log. */
    val lastError: String? get() = null
}

private class Damaged(message: String) : Exception(message)

object ControlCodec {
    const val FORMAT_VERSION = 1
    private val SEAT_DIRECTIONS = setOf("BACKWARD", "FORWARD")

    fun encode(records: List<ControlRecord>): String = JSONObject()
        .put("formatVersion", FORMAT_VERSION)
        .put("records", JSONArray(records.map { r ->
            JSONObject()
                .put("jobId", r.jobId)
                .put("prop", r.prop.name)
                .put("area", r.area)
                .put("original", r.original)
                .put("target", r.target)
                .put("related", JSONObject(r.related))
                .put("stage", r.stage.name)
                .put("requestId", r.requestId)
                .put("responseReceived", r.responseReceived)
                .put("priorRequestUnresolved", r.priorRequestUnresolved)
                .put("lastEcho", r.lastEcho ?: JSONObject.NULL)
                .put("lastRead", r.lastRead ?: JSONObject.NULL)
                .put("note", r.note ?: JSONObject.NULL)
        }))
        .toString(2)

    /** Strict: an unknown version, name, area or value makes the whole file Unreadable (it stays on disk). */
    fun decode(text: String): ControlLoad = try {
        val json = JSONObject(text)
        val version = json.get("formatVersion") as? Int ?: throw Damaged("formatVersion이 정수가 아님")
        if (version != FORMAT_VERSION) throw Damaged("지원하지 않는 기록 형식 버전($version)")
        val array = json.get("records") as? JSONArray ?: throw Damaged("records가 배열이 아님")
        val records = (0 until array.length()).map { i -> record(array.get(i) as? JSONObject ?: throw Damaged("기록 $i 형식 오류")) }
        if (records.map { it.prop to it.area }.toSet().size != records.size) throw Damaged("같은 속성·영역 기록이 둘 이상")
        if (records.map { it.jobId }.toSet().size != records.size) throw Damaged("같은 jobId 기록이 둘 이상")
        if (records.map { it.requestId }.toSet().size != records.size) throw Damaged("같은 requestId 기록이 둘 이상")
        if (records.isEmpty()) ControlLoad.Empty else ControlLoad.Found(records)
    } catch (e: Damaged) {
        ControlLoad.Unreadable(e.message ?: "손상된 기록")
    } catch (e: JSONException) {
        ControlLoad.Unreadable("손상된 기록(${e.javaClass.simpleName})")
    }

    private fun record(o: JSONObject): ControlRecord {
        val prop = ControlProp.entries.firstOrNull { it.name == o.str("prop") } ?: throw Damaged("알 수 없는 속성(${o.opt("prop")})")
        val area = o.str("area")
        val seats = area.split('+')
        if (seats.any { it !in KNOWN_SEATS } || seats.toSet().size != seats.size) throw Damaged("알 수 없는 영역($area)")
        val original = o.str("original")
        val target = o.str("target")
        if (ControlValues.parse(prop, original) == null) throw Damaged("원래값 형식 오류($original)")
        val targetOk = if (prop == ControlProp.SEAT_BACKREST) target in SEAT_DIRECTIONS else ControlValues.parse(prop, target) != null
        if (!targetOk) throw Damaged("목표값 형식 오류($target)")
        val relatedJson = o.get("related") as? JSONObject ?: throw Damaged("related가 객체가 아님")
        val related = relatedJson.keys().asSequence().associateWith { k -> relatedJson.get(k) as? String ?: throw Damaged("related.${k}가 문자열이 아님") }
        ControlValues.relatedProblem(prop, related)?.let { throw Damaged(it) }
        val stage = ControlStage.entries.firstOrNull { it.name == o.str("stage") } ?: throw Damaged("알 수 없는 단계(${o.opt("stage")})")
        val jobId = o.str("jobId")
        val requestId = o.str("requestId")
        if (jobId.isBlank() || requestId.isBlank()) throw Damaged("작업·요청 ID가 비어 있음")
        return ControlRecord(
            jobId = jobId,
            prop = prop,
            area = area,
            original = original,
            target = target,
            related = related,
            stage = stage,
            requestId = requestId,
            responseReceived = o.get("responseReceived") as? Boolean ?: throw Damaged("responseReceived 형식 오류"),
            priorRequestUnresolved = o.get("priorRequestUnresolved") as? Boolean ?: throw Damaged("priorRequestUnresolved 형식 오류"),
            lastEcho = o.nullableStr("lastEcho"),
            lastRead = o.nullableStr("lastRead"),
            note = o.nullableStr("note"),
        )
    }

    private fun JSONObject.str(name: String): String = get(name) as? String ?: throw Damaged("$name 값의 형식이 문자열이 아님")

    private fun JSONObject.nullableStr(name: String): String? = if (isNull(name)) null else str(name)
}

/**
 * Temp file, fsync, atomic rename, then fsync of the parent directory: only then is the rename itself
 * durable, so only then is the save a success (and a command may follow).
 */
class FileControlStore(
    private val file: File,
    private val syncDirectory: (File) -> Unit = { dir -> FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } },
) : ControlStore {
    override var lastError: String? = null
        private set

    override fun load(): ControlLoad {
        if (!file.exists()) return ControlLoad.Empty
        val text = try {
            file.readText()
        } catch (e: java.io.IOException) {
            return ControlLoad.Unreadable("기록을 읽지 못함(${e.javaClass.simpleName})")
        }
        return ControlCodec.decode(text)
    }

    override fun save(records: List<ControlRecord>): Boolean = try {
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(ControlCodec.encode(records).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(file.absoluteFile.parentFile)
        lastError = null
        true
    } catch (e: Exception) {
        lastError = "${e.javaClass.name}: ${e.message}"
        false
    }

    companion object {
        const val FILE_NAME = "vehicle-control-records.json"
    }
}
