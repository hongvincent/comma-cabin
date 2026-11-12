package app.commacabin.lighting

import app.commacabin.control.SYNC_DIRECTORY
import app.commacabin.control.writeDurably
import app.commacabin.probe.KNOWN_SEATS
import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class LightProp(val label: String) { BRIGHTNESS("무드등 밝기"), COLOR("무드등 색") }

enum class LightStage { APPLY_REQUESTED, APPLY_CONFIRMED, RESULT_UNKNOWN, RESTORE_REQUESTED, RESTORE_FAILED, RESTORE_HELD, USER_CHANGED }

/**
 * One product lighting change, saved before every command. [original] is the raw value read before the
 * change (brightness, or the ARGB exactly as read), [target] what the change asked for, [payload] what the
 * latest request ([requestId]) sent. Without [responseReceived], or with [priorRequestUnresolved] (an
 * earlier run never answered), the record is locked: no write, restore or delete from the app.
 */
data class LightRecord(
    val jobId: String,
    val prop: LightProp,
    val area: String,
    val original: Int,
    val target: Int,
    val stage: LightStage,
    val requestId: String,
    val payload: Int,
    val responseReceived: Boolean,
    val priorRequestUnresolved: Boolean = false,
    val lastEcho: Int? = null,
    val lastRead: Int? = null,
) {
    val locked: Boolean get() = !responseReceived || priorRequestUnresolved
}

/** PM decisions of STEP 8: brightness target 30 and one warm color; colors compare by RGB with alpha 0x00 or 0xFF only. */
object LightValues {
    const val TARGET_BRIGHTNESS = 30
    const val WARM_COLOR: Int = 0xFFFF9A40.toInt()

    fun alphaAllowed(raw: Int): Boolean = (raw ushr 24).let { it == 0x00 || it == 0xFF }

    fun rgb(raw: Int): Int = raw and 0xFFFFFF

    /** Brightness: equal. Color: same RGB and both alphas allowed (the vehicle reads back alpha 0x00). */
    fun same(prop: LightProp, read: Int?, expected: Int): Boolean = when {
        read == null -> false
        prop == LightProp.BRIGHTNESS -> read == expected
        else -> alphaAllowed(read) && alphaAllowed(expected) && rgb(read) == rgb(expected)
    }

    /** Every color request carries alpha 0xFF as documented; the original raw value stays in the record. */
    fun restorePayload(prop: LightProp, original: Int): Int =
        if (prop == LightProp.COLOR) (0xFF shl 24) or rgb(original) else original

    fun hex(raw: Int): String = "0x%08X".format(raw)
}

sealed interface LightLoad {
    data object Empty : LightLoad
    data class Found(val records: List<LightRecord>) : LightLoad
    data class Unreadable(val reason: String) : LightLoad
}

interface LightStore {
    fun load(): LightLoad
    /** True only when the whole list is durably written. */
    fun save(records: List<LightRecord>): Boolean
    val lastError: String? get() = null
}

private class Damaged(message: String) : Exception(message)

/** Version 1. Brightness values are JSON integers; colors are "0xAARRGGBB" strings. Anything else is Unreadable. */
object LightCodec {
    const val FORMAT_VERSION = 1
    private val COLOR = Regex("^0x[0-9A-F]{8}$")

    fun encode(records: List<LightRecord>): String = JSONObject()
        .put("formatVersion", FORMAT_VERSION)
        .put("records", JSONArray(records.map { r ->
            JSONObject()
                .put("jobId", r.jobId)
                .put("prop", r.prop.name)
                .put("area", r.area)
                .put("original", value(r.prop, r.original))
                .put("target", value(r.prop, r.target))
                .put("stage", r.stage.name)
                .put("requestId", r.requestId)
                .put("payload", value(r.prop, r.payload))
                .put("responseReceived", r.responseReceived)
                .put("priorRequestUnresolved", r.priorRequestUnresolved)
                .put("lastEcho", r.lastEcho?.let { value(r.prop, it) } ?: JSONObject.NULL)
                .put("lastRead", r.lastRead?.let { value(r.prop, it) } ?: JSONObject.NULL)
        }))
        .toString(2)

    fun decode(text: String): LightLoad = try {
        val json = JSONObject(text)
        val version = json.get("formatVersion") as? Int ?: throw Damaged("formatVersion이 정수가 아님")
        if (version != FORMAT_VERSION) throw Damaged("지원하지 않는 기록 형식 버전($version)")
        val array = json.get("records") as? JSONArray ?: throw Damaged("records가 배열이 아님")
        val records = (0 until array.length()).map { i -> record(array.get(i) as? JSONObject ?: throw Damaged("기록 $i 형식 오류")) }
        if (records.map { it.prop to it.area }.toSet().size != records.size) throw Damaged("같은 속성·영역 기록이 둘 이상")
        if (records.map { it.jobId }.toSet().size != records.size) throw Damaged("같은 jobId 기록이 둘 이상")
        if (records.map { it.requestId }.toSet().size != records.size) throw Damaged("같은 requestId 기록이 둘 이상")
        if (records.isEmpty()) LightLoad.Empty else LightLoad.Found(records)
    } catch (e: Damaged) {
        LightLoad.Unreadable(e.message ?: "손상된 기록")
    } catch (e: JSONException) {
        LightLoad.Unreadable("손상된 기록(${e.javaClass.simpleName})")
    }

    private fun value(prop: LightProp, v: Int): Any = if (prop == LightProp.COLOR) LightValues.hex(v) else v

    private fun record(o: JSONObject): LightRecord {
        val prop = LightProp.entries.firstOrNull { it.name == o.get("prop") } ?: throw Damaged("알 수 없는 속성(${o.opt("prop")})")
        val area = o.get("area") as? String ?: throw Damaged("area가 문자열이 아님")
        val seats = area.split('+')
        if (seats.any { it !in KNOWN_SEATS } || seats.toSet().size != seats.size) throw Damaged("알 수 없는 영역($area)")
        val original = typed(o, prop, "original")
        val target = typed(o, prop, "target")
        val payload = typed(o, prop, "payload")
        when (prop) {
            LightProp.BRIGHTNESS -> if (original < 0 || target < 0 || target >= original || payload < 0) throw Damaged("밝기 값 범위가 맞지 않음")
            LightProp.COLOR -> if (!LightValues.alphaAllowed(original) || target != LightValues.WARM_COLOR || payload ushr 24 != 0xFF) throw Damaged("색 값이 계약과 맞지 않음")
        }
        val stage = LightStage.entries.firstOrNull { it.name == o.get("stage") } ?: throw Damaged("알 수 없는 처리 단계(${o.opt("stage")})")
        val jobId = o.get("jobId") as? String ?: throw Damaged("jobId가 문자열이 아님")
        val requestId = o.get("requestId") as? String ?: throw Damaged("requestId가 문자열이 아님")
        if (jobId.isBlank() || requestId.isBlank()) throw Damaged("ID가 비어 있음")
        return LightRecord(
            jobId = jobId,
            prop = prop,
            area = area,
            original = original,
            target = target,
            stage = stage,
            requestId = requestId,
            payload = payload,
            responseReceived = o.get("responseReceived") as? Boolean ?: throw Damaged("responseReceived가 참/거짓이 아님"),
            priorRequestUnresolved = o.get("priorRequestUnresolved") as? Boolean ?: throw Damaged("priorRequestUnresolved가 참/거짓이 아님"),
            lastEcho = if (o.isNull("lastEcho")) null else typed(o, prop, "lastEcho"),
            lastRead = if (o.isNull("lastRead")) null else typed(o, prop, "lastRead"),
        )
    }

    private fun typed(o: JSONObject, prop: LightProp, name: String): Int = when (prop) {
        LightProp.BRIGHTNESS -> o.get(name) as? Int ?: throw Damaged("$name 값이 정수가 아님")
        LightProp.COLOR -> (o.get(name) as? String)?.takeIf { COLOR.matches(it) }?.substring(2)?.toLong(16)?.toInt() ?: throw Damaged("$name 값이 색 형식이 아님")
    }
}

/** `filesDir/rest-lighting-records.json`, written with [writeDurably]. The debug record files are never touched. */
class FileLightStore(
    private val file: File,
    private val syncDirectory: (File) -> Unit = SYNC_DIRECTORY,
) : LightStore {
    override var lastError: String? = null
        private set

    override fun load(): LightLoad {
        if (!file.exists()) return LightLoad.Empty
        val text = try {
            file.readText()
        } catch (e: java.io.IOException) {
            return LightLoad.Unreadable("기록을 읽지 못함(${e.javaClass.simpleName})")
        }
        return LightCodec.decode(text)
    }

    override fun save(records: List<LightRecord>): Boolean = try {
        writeDurably(file, LightCodec.encode(records), syncDirectory)
        lastError = null
        true
    } catch (e: Exception) {
        lastError = "${e.javaClass.name}: ${e.message}"
        false
    }

    companion object {
        const val FILE_NAME = "rest-lighting-records.json"
    }
}
