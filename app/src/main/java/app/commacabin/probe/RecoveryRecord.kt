package app.commacabin.probe

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONException
import org.json.JSONObject

enum class Stage(val label: String) {
    APPLY_REQUESTED("변경 요청 중"),
    APPLY_CONFIRMED("적용 확인"),
    RESULT_UNKNOWN("결과 불명"),
    RESTORE_REQUESTED("복원 중"),
    RESTORE_FAILED("복원 실패"),
    RESTORE_HELD("복원 보류"),
    USER_CHANGED("사용자 변경"),
}

/** Pleos SeatValue names. A record naming anything else is treated as damaged. */
val KNOWN_SEATS = setOf(
    "ROW_1_LEFT", "ROW_1_CENTER", "ROW_1_RIGHT",
    "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT",
    "ROW_3_LEFT", "ROW_3_CENTER", "ROW_3_RIGHT",
)

/**
 * Saved before every vehicle write. [jobId] names the latest request. [responseReceived] tells
 * whether that request's onComplete/onFailed arrived. A record without a received response (whatever
 * its stage says), or one carrying [priorRequestUnresolved], locks the app out of writing or deleting.
 */
data class RecoveryRecord(
    val formatVersion: Int = FORMAT_VERSION,
    val jobId: String,
    val area: String,
    val originalValue: Int,
    val targetValue: Int,
    val stage: Stage,
    val responseReceived: Boolean,
    val priorRequestUnresolved: Boolean = false,
    val lastCallbackValue: Int? = null,
    val lastReadValue: Int? = null,
) {
    /** Any stage counts: a stage name never proves that the latest request answered. */
    val awaitingResponse: Boolean
        get() = !responseReceived

    companion object {
        const val FORMAT_VERSION = 1
    }
}

sealed interface LoadResult {
    data object Empty : LoadResult
    data class Found(val record: RecoveryRecord) : LoadResult
    data class Unreadable(val reason: String) : LoadResult
}

interface RecoveryStore {
    fun load(): LoadResult
    /** Returns true only when the record is durably written. */
    fun save(record: RecoveryRecord): Boolean
    fun delete(): Boolean
}

private class DamagedRecord(message: String) : Exception(message)

object RecoveryCodec {
    fun encode(record: RecoveryRecord): String = JSONObject()
        .put("formatVersion", record.formatVersion)
        .put("jobId", record.jobId)
        .put("area", record.area)
        .put("originalValue", record.originalValue)
        .put("targetValue", record.targetValue)
        .put("stage", record.stage.name)
        .put("responseReceived", record.responseReceived)
        .put("priorRequestUnresolved", record.priorRequestUnresolved)
        .put("lastCallbackValue", record.lastCallbackValue ?: JSONObject.NULL)
        .put("lastReadValue", record.lastReadValue ?: JSONObject.NULL)
        .toString(2)

    /** Strict: wrong types, unknown names or impossible values make the record Unreadable (kept on disk). */
    fun decode(text: String): LoadResult = try {
        val json = JSONObject(text)
        val version = json.strictInt("formatVersion")
        if (version != RecoveryRecord.FORMAT_VERSION) throw DamagedRecord("지원하지 않는 기록 형식 버전($version)")
        val jobId = json.strictString("jobId")
        if (jobId.isBlank()) throw DamagedRecord("작업 ID가 비어 있음")
        val area = json.strictString("area")
        val seats = area.split('+')
        if (seats.any { it !in KNOWN_SEATS } || seats.toSet().size != seats.size) throw DamagedRecord("알 수 없는 영역($area)")
        val original = json.strictInt("originalValue")
        val target = json.strictInt("targetValue")
        if (original < 0 || target < 0 || target >= original) throw DamagedRecord("값 범위가 맞지 않음(원래 $original, 목표 $target)")
        val stageName = json.strictString("stage")
        val stage = Stage.entries.firstOrNull { it.name == stageName } ?: throw DamagedRecord("알 수 없는 처리 단계($stageName)")
        LoadResult.Found(
            RecoveryRecord(
                formatVersion = version,
                jobId = jobId,
                area = area,
                originalValue = original,
                targetValue = target,
                stage = stage,
                responseReceived = json.strictBoolean("responseReceived"),
                priorRequestUnresolved = json.strictBoolean("priorRequestUnresolved"),
                lastCallbackValue = json.nullableInt("lastCallbackValue"),
                lastReadValue = json.nullableInt("lastReadValue"),
            ),
        )
    } catch (e: DamagedRecord) {
        LoadResult.Unreadable(e.message ?: "손상된 기록")
    } catch (e: JSONException) {
        LoadResult.Unreadable("손상된 기록(${e.javaClass.simpleName})")
    }

    private fun JSONObject.strictInt(name: String): Int =
        get(name) as? Int ?: throw DamagedRecord("$name 값의 형식이 정수가 아님")

    private fun JSONObject.strictString(name: String): String =
        get(name) as? String ?: throw DamagedRecord("$name 값의 형식이 문자열이 아님")

    private fun JSONObject.strictBoolean(name: String): Boolean =
        get(name) as? Boolean ?: throw DamagedRecord("$name 값의 형식이 참/거짓이 아님")

    private fun JSONObject.nullableInt(name: String): Int? = if (isNull(name)) null else strictInt(name)
}

/** Writes a temp file, syncs it, then renames it over the record so readers never see half a file. */
class FileRecoveryStore(private val file: File) : RecoveryStore {
    override fun load(): LoadResult {
        if (!file.exists()) return LoadResult.Empty
        val text = try {
            file.readText()
        } catch (e: java.io.IOException) {
            return LoadResult.Unreadable("기록을 읽지 못함(${e.javaClass.simpleName})")
        }
        return RecoveryCodec.decode(text)
    }

    override fun save(record: RecoveryRecord): Boolean = try {
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(RecoveryCodec.encode(record).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (e: Exception) {
        false
    }

    override fun delete(): Boolean = !file.exists() || file.delete()

    companion object {
        const val FILE_NAME = "ambient-probe-recovery.json"
    }
}
