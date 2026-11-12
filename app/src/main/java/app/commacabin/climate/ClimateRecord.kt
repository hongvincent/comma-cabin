package app.commacabin.climate

import app.commacabin.control.SYNC_DIRECTORY
import app.commacabin.control.writeDurably
import app.commacabin.probe.KNOWN_SEATS
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class ClimateProp(val label: String) { FAN("바람 세기"), TEMP("운전석 온도") }

enum class MemberStage { APPLY_REQUESTED, APPLIED, RESULT_UNKNOWN, RESTORE_REQUESTED, RESTORED, RESTORE_FAILED }

/**
 * ACTIVE: under the app's control. EXTERNAL_CHANGE: a change from the vehicle side was confirmed, so no further
 * apply and no automatic restore. RESULT_UNKNOWN: something the app cannot attribute (left for a manual check).
 * HELD: a restore was not sent (not parked, power off, Sync on…). AUTO_*: the group's single auto restore.
 */
enum class GroupStage { ACTIVE, EXTERNAL_CHANGE, RESULT_UNKNOWN, HELD, AUTO_RESTORE_REQUESTED, AUTO_RESTORE_FAILED }

/** One fan or driver-temperature change. Values: fan whole steps, temperature °C. [payload] is what [requestId] sent. */
data class ClimateMember(
    val prop: ClimateProp,
    val area: String,
    val original: Float,
    val target: Float,
    val stage: MemberStage,
    val requestId: String,
    val payload: Float,
    val responseReceived: Boolean,
    val lastEcho: Float? = null,
    val lastRead: Float? = null,
)

/**
 * The fan and temperature changes that share one auto (and power) area. [autoOriginal] is read once, before
 * the group's first change, and never replaced. [expected] holds the shared states (POWER, AUTO, DUAL) as last
 * read right after the app's own command; any other value seen later is a change from the vehicle side. A
 * shared state that moved across one of the app's own commands, or could not be read around it, makes the group
 * RESULT_UNKNOWN (its source cannot be told). A group with any request not answered (members or the auto restore), or carrying
 * [priorRequestUnresolved], is locked: no write, restore or delete from the app.
 */
data class ClimateGroup(
    val groupId: String,
    val autoArea: String,
    val powerArea: String,
    val dualArea: String?,
    val autoOriginal: Boolean,
    val expected: Map<String, Boolean>,
    val stage: GroupStage,
    val members: List<ClimateMember>,
    val autoRequestId: String? = null,
    val autoResponseReceived: Boolean = true,
    val priorRequestUnresolved: Boolean = false,
) {
    val locked: Boolean get() = priorRequestUnresolved || !autoResponseReceived || members.any { !it.responseReceived }

    fun member(prop: ClimateProp, area: String) = members.firstOrNull { it.prop == prop && it.area == area }
}

/** The only temperature area of this contract: the driver alone. */
const val DRIVER_AREA = "ROW_1_LEFT"

object ClimateValues {
    const val DEFAULT_TEMP = 22.0f

    /** Fan steps are compared exactly; temperatures within 0.01 °C. */
    fun same(prop: ClimateProp, a: Float?, b: Float?): Boolean =
        a != null && b != null && if (prop == ClimateProp.FAN) a.roundToLong() == b.roundToLong() && abs(a - b) < 0.001f else abs(a - b) < 0.01f

    /** On the vehicle's temperature grid: min + k × increment. Any non-finite input or increment ≤ 0 is false, never a crash. */
    fun onGrid(value: Float, min: Float, increment: Float): Boolean {
        if (!value.isFinite() || !min.isFinite() || !increment.isFinite() || increment <= 0f) return false
        val k = (value - min) / increment
        if (!k.isFinite()) return false
        return abs(k - k.roundToLong()) < 0.001f
    }

    /**
     * Screen text. A temperature is shown exactly as held (Float.toString, locale-independent: 22.0, 22.5, 22.25),
     * never rounded coarser than the vehicle's step.
     */
    fun show(prop: ClimateProp, v: Float): String = if (prop == ClimateProp.FAN) v.roundToLong().toString() else v.toString()

    /**
     * What goes to the SDK: a fan step only as a finite whole number, a temperature unrounded (Float.toString).
     * null = not a valid payload (nothing is sent).
     */
    fun payload(prop: ClimateProp, v: Float): String? = when {
        !v.isFinite() -> null
        prop == ClimateProp.FAN -> v.roundToLong().takeIf { abs(v - it) < 0.0001f && it >= 0 }?.toString()
        else -> v.toString()
    }
}

sealed interface ClimateLoad {
    data object Empty : ClimateLoad
    data class Found(val groups: List<ClimateGroup>) : ClimateLoad
    data class Unreadable(val reason: String) : ClimateLoad
}

interface ClimateStore {
    fun load(): ClimateLoad
    /** True only when the whole list is durably written. */
    fun save(groups: List<ClimateGroup>): Boolean
    val lastError: String? get() = null
}

private class Damaged(message: String) : Exception(message)

/** Version 1. Fan values are JSON integers, temperatures JSON numbers (°C), shared states booleans. Anything else is Unreadable. */
object ClimateCodec {
    const val FORMAT_VERSION = 1
    private val SIDES = setOf("POWER", "AUTO", "DUAL")

    fun encode(groups: List<ClimateGroup>): String = JSONObject()
        .put("formatVersion", FORMAT_VERSION)
        .put("groups", JSONArray(groups.map { g ->
            JSONObject()
                .put("groupId", g.groupId)
                .put("autoArea", g.autoArea)
                .put("powerArea", g.powerArea)
                .put("dualArea", g.dualArea ?: JSONObject.NULL)
                .put("autoOriginal", g.autoOriginal)
                .put("expected", JSONObject(g.expected))
                .put("stage", g.stage.name)
                .put("autoRequestId", g.autoRequestId ?: JSONObject.NULL)
                .put("autoResponseReceived", g.autoResponseReceived)
                .put("priorRequestUnresolved", g.priorRequestUnresolved)
                .put("members", JSONArray(g.members.map { m ->
                    JSONObject()
                        .put("prop", m.prop.name)
                        .put("area", m.area)
                        .put("original", value(m.prop, m.original))
                        .put("target", value(m.prop, m.target))
                        .put("stage", m.stage.name)
                        .put("requestId", m.requestId)
                        .put("payload", value(m.prop, m.payload))
                        .put("responseReceived", m.responseReceived)
                        .put("lastEcho", m.lastEcho?.let { value(m.prop, it) } ?: JSONObject.NULL)
                        .put("lastRead", m.lastRead?.let { value(m.prop, it) } ?: JSONObject.NULL)
                }))
        }))
        .toString(2)

    fun decode(text: String): ClimateLoad = try {
        val json = JSONObject(text)
        val version = json.get("formatVersion") as? Int ?: throw Damaged("formatVersion이 정수가 아님")
        if (version != FORMAT_VERSION) throw Damaged("지원하지 않는 기록 형식 버전($version)")
        val array = json.get("groups") as? JSONArray ?: throw Damaged("groups가 배열이 아님")
        val groups = (0 until array.length()).map { i -> group(array.get(i) as? JSONObject ?: throw Damaged("그룹 $i 형식 오류")) }
        if (groups.map { it.groupId }.toSet().size != groups.size) throw Damaged("같은 groupId가 둘 이상")
        if (groups.map { it.autoArea }.toSet().size != groups.size) throw Damaged("같은 자동 모드 영역의 그룹이 둘 이상")
        val members = groups.flatMap { it.members }
        if (members.map { it.prop to it.area }.toSet().size != members.size) throw Damaged("같은 항목·영역 기록이 둘 이상")
        val ids = members.map { it.requestId } + groups.mapNotNull { it.autoRequestId }
        if (ids.toSet().size != ids.size) throw Damaged("같은 requestId가 둘 이상")
        if (groups.isEmpty()) ClimateLoad.Empty else ClimateLoad.Found(groups)
    } catch (e: Damaged) {
        ClimateLoad.Unreadable(e.message ?: "손상된 기록")
    } catch (e: JSONException) {
        ClimateLoad.Unreadable("손상된 기록(${e.javaClass.simpleName})")
    }

    private fun value(prop: ClimateProp, v: Float): Any = if (prop == ClimateProp.FAN) v.roundToLong().toInt() else v.toDouble()

    private fun area(o: JSONObject, name: String): String {
        val area = o.get(name) as? String ?: throw Damaged("${name}이 문자열이 아님")
        val seats = area.split('+')
        if (seats.any { it !in KNOWN_SEATS } || seats.toSet().size != seats.size) throw Damaged("알 수 없는 영역($area)")
        return area
    }

    private fun bool(o: JSONObject, name: String): Boolean = o.get(name) as? Boolean ?: throw Damaged("${name}이 참/거짓이 아님")

    private fun id(o: JSONObject, name: String): String = (o.get(name) as? String)?.takeIf { it.isNotBlank() } ?: throw Damaged("${name}이 비어 있거나 문자열이 아님")

    private fun group(o: JSONObject): ClimateGroup {
        val expectedJson = o.get("expected") as? JSONObject ?: throw Damaged("expected 형식 오류")
        val expected = expectedJson.keys().asSequence().associateWith { k ->
            if (k !in SIDES) throw Damaged("알 수 없는 공유 상태($k)")
            bool(expectedJson, k)
        }
        if ("POWER" !in expected || "AUTO" !in expected) throw Damaged("전원·자동 상태가 없음")
        val members = (o.get("members") as? JSONArray ?: throw Damaged("members가 배열이 아님")).let { a ->
            (0 until a.length()).map { member(a.get(it) as? JSONObject ?: throw Damaged("항목 $it 형식 오류")) }
        }
        if (members.isEmpty()) throw Damaged("항목이 없는 그룹")
        val dualArea = if (o.isNull("dualArea")) null else area(o, "dualArea")
        members.filter { it.prop == ClimateProp.TEMP }.forEach { t ->
            if (t.area != DRIVER_AREA) throw Damaged("온도 항목은 운전석 단독 영역만 허용(${t.area})")
            if (dualArea == null || "DUAL" !in expected || DRIVER_AREA !in dualArea.split('+')) throw Damaged("온도 항목의 연동 영역·상태가 맞지 않음")
        }
        val autoArea = area(o, "autoArea")
        val powerArea = area(o, "powerArea")
        members.forEach { m ->
            val seats = m.area.split('+')
            if (!autoArea.split('+').containsAll(seats) || !powerArea.split('+').containsAll(seats)) throw Damaged("항목 영역이 그룹 영역 밖(${m.area})")
        }
        return ClimateGroup(
            groupId = id(o, "groupId"),
            autoArea = autoArea,
            powerArea = powerArea,
            dualArea = dualArea,
            autoOriginal = bool(o, "autoOriginal"),
            expected = expected,
            stage = GroupStage.entries.firstOrNull { it.name == o.get("stage") } ?: throw Damaged("알 수 없는 그룹 단계(${o.opt("stage")})"),
            members = members,
            autoRequestId = if (o.isNull("autoRequestId")) null else id(o, "autoRequestId"),
            autoResponseReceived = bool(o, "autoResponseReceived"),
            priorRequestUnresolved = bool(o, "priorRequestUnresolved"),
        )
    }

    private fun member(o: JSONObject): ClimateMember {
        val prop = ClimateProp.entries.firstOrNull { it.name == o.get("prop") } ?: throw Damaged("알 수 없는 항목(${o.opt("prop")})")
        val original = number(o, prop, "original")
        val target = number(o, prop, "target")
        when (prop) {
            ClimateProp.FAN -> if (original < 1f || target < 1f || target >= original) throw Damaged("풍량 값 범위가 맞지 않음")
            ClimateProp.TEMP -> if (ClimateValues.same(prop, original, target)) throw Damaged("온도 목표가 원래값과 같음")
        }
        return ClimateMember(
            prop = prop,
            area = area(o, "area"),
            original = original,
            target = target,
            stage = MemberStage.entries.firstOrNull { it.name == o.get("stage") } ?: throw Damaged("알 수 없는 항목 단계(${o.opt("stage")})"),
            requestId = id(o, "requestId"),
            payload = number(o, prop, "payload"),
            responseReceived = bool(o, "responseReceived"),
            lastEcho = if (o.isNull("lastEcho")) null else number(o, prop, "lastEcho"),
            lastRead = if (o.isNull("lastRead")) null else number(o, prop, "lastRead"),
        )
    }

    private fun number(o: JSONObject, prop: ClimateProp, name: String): Float = when (prop) {
        ClimateProp.FAN -> (o.get(name) as? Int)?.toFloat() ?: throw Damaged("$name 값이 정수가 아님")
        ClimateProp.TEMP -> (o.get(name) as? Number)?.toDouble()?.toFloat()?.takeIf { it.isFinite() } ?: throw Damaged("$name 값이 유한한 숫자가 아님")
    }
}

/** `filesDir/rest-climate-records.json`, written with [writeDurably]. Other record files are never touched. */
class FileClimateStore(
    private val file: File,
    private val syncDirectory: (File) -> Unit = SYNC_DIRECTORY,
) : ClimateStore {
    override var lastError: String? = null
        private set

    override fun load(): ClimateLoad {
        if (!file.exists()) return ClimateLoad.Empty
        val text = try {
            file.readText()
        } catch (e: java.io.IOException) {
            return ClimateLoad.Unreadable("기록을 읽지 못함(${e.javaClass.simpleName})")
        }
        return ClimateCodec.decode(text)
    }

    override fun save(groups: List<ClimateGroup>): Boolean = try {
        writeDurably(file, ClimateCodec.encode(groups), syncDirectory)
        lastError = null
        true
    } catch (e: Exception) {
        lastError = "${e.javaClass.name}: ${e.message}"
        false
    }

    companion object {
        const val FILE_NAME = "rest-climate-records.json"
    }
}
