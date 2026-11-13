package app.commacabin.seat

import app.commacabin.control.SYNC_DIRECTORY
import app.commacabin.control.SeatAxis
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/*
 * STEP 26: the seat journal file and its strict format (version 1), see docs/seat-recovery-format.md.
 * Not created or used by the app yet: tests give it explicit files in a JVM temp directory only.
 */

/**
 * Format version 1 of one [SeatSnapshot]: `{"formatVersion":1,"snapshot":{...}}`, every field present, null written as
 * null, enums by their current names, positions as plain Ints (no unit or range is assumed here).
 *
 * Structure only. Whether a snapshot is finished or consistent with its outcome is [SeatMoveCore]'s check on load:
 * a valid pending snapshot (SAVED/ENTERING, timed out, outcome null) is [SeatJournalLoad.Found], never Empty.
 *
 * Its own small reader instead of org.json: Android's built-in org.json and the JVM test one are both lenient
 * (unquoted keys, single quotes) and differ in number types, so a strict reader keeps device and tests the same.
 */
object SeatJournalCodec {
    const val FORMAT_VERSION = 1

    fun encode(s: SeatSnapshot): String = json(
        linkedMapOf(
            "formatVersion" to FORMAT_VERSION,
            "snapshot" to linkedMapOf(
                "jobId" to s.jobId,
                "purpose" to s.purpose,
                "axis" to s.axis,
                "original" to s.original,
                "start" to s.start,
                "target" to s.target,
                "move" to request(s.move),
                "stops" to s.stops.map(::request),
                "outcome" to s.outcome,
                "held" to s.held,
            ),
        ),
    )

    /** [SeatJournalLoad.Found] or [SeatJournalLoad.Unreadable]; nothing is defaulted or repaired. */
    fun decode(text: String): SeatJournalLoad = try {
        val root = StrictJsonReader(text).root() as? Map<*, *> ?: throw Damaged("최상위가 객체가 아님")
        if (!root.containsKey("formatVersion")) throw Damaged("formatVersion 없음")
        val version = root["formatVersion"] as? Int ?: throw Damaged("formatVersion이 정수가 아님")
        if (version != FORMAT_VERSION) throw Damaged("지원하지 않는 형식 버전 $version")
        root.exactKeys("기록", "formatVersion", "snapshot")
        SeatJournalLoad.Found(snapshot(root.obj("snapshot")))
    } catch (e: Damaged) {
        SeatJournalLoad.Unreadable("시트 기록 형식 오류: ${e.message}")
    }

    private fun request(r: SeatRequestMark) = linkedMapOf(
        "requestId" to r.requestId,
        "state" to r.state,
        "answer" to r.answer,
        "timedOut" to r.timedOut,
        "afterMove" to r.afterMove,
    )

    private fun snapshot(m: Map<*, *>): SeatSnapshot {
        m.exactKeys("snapshot", "jobId", "purpose", "axis", "original", "start", "target", "move", "stops", "outcome", "held")
        val move = mark(m.obj("move"), "move")
        val stops = m.list("stops").mapIndexed { i, v -> mark(v as? Map<*, *> ?: throw Damaged("stops[$i]가 객체가 아님"), "stops[$i]") }
        val ids = listOf(move.requestId) + stops.map { it.requestId }
        if (ids.toSet().size != ids.size) throw Damaged("요청 ID 중복")
        return SeatSnapshot(
            jobId = m.id("jobId"),
            purpose = m.enum("purpose", SeatPurpose.entries),
            axis = m.enum("axis", SeatAxis.entries),
            original = m.int("original"),
            start = m.int("start"),
            target = m.int("target"),
            move = move,
            stops = stops,
            outcome = if (m["outcome"] == null) null else m.enum("outcome", SeatOutcome.entries),
            held = if (m["held"] == null) null else m.str("held"),
        )
    }

    private fun mark(m: Map<*, *>, where: String): SeatRequestMark {
        m.exactKeys(where, "requestId", "state", "answer", "timedOut", "afterMove")
        return SeatRequestMark(
            requestId = m.id("requestId"),
            state = m.enum("state", SeatRequestState.entries),
            answer = if (m["answer"] == null) null else m.str("answer"),
            timedOut = m["timedOut"] as? Boolean ?: throw Damaged("timedOut이 Boolean이 아님"),
            afterMove = m["afterMove"] as? Boolean ?: throw Damaged("afterMove가 Boolean이 아님"),
        )
    }

    // Presence is checked by exactKeys first, so a null value below is a JSON null.
    private fun Map<*, *>.exactKeys(where: String, vararg names: String) {
        val missing = names.filterNot { containsKey(it) }
        val unknown = keys.map { it as String }.filterNot { it in names }
        if (missing.isNotEmpty() || unknown.isNotEmpty()) throw Damaged("$where 필드 오류(없음 $missing, 모름 $unknown)")
    }

    private fun Map<*, *>.int(name: String): Int = this[name] as? Int ?: throw Damaged("${name}이 정수가 아님")
    private fun Map<*, *>.str(name: String): String = this[name] as? String ?: throw Damaged("${name}이 문자열이 아님")
    private fun Map<*, *>.id(name: String): String = str(name).also { if (it.isBlank()) throw Damaged("${name}이 비어 있음") }
    private fun Map<*, *>.obj(name: String): Map<*, *> = this[name] as? Map<*, *> ?: throw Damaged("${name}이 객체가 아님")
    private fun Map<*, *>.list(name: String): List<*> = this[name] as? List<*> ?: throw Damaged("${name}이 배열이 아님")
    private fun <E : Enum<E>> Map<*, *>.enum(name: String, values: List<E>): E {
        val text = str(name)
        return values.firstOrNull { it.name == text } ?: throw Damaged("$name 값을 모름")
    }

    private fun json(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Int, is Boolean -> v.toString()
        is Enum<*> -> quote(v.name)
        is List<*> -> v.joinToString(",", "[", "]") { json(it) }
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> quote(k as String) + ":" + json(x) }
        else -> throw IllegalArgumentException("unsupported ${v.javaClass.simpleName}")
    }

    /** Control characters and every surrogate are escaped, so any Kotlin string round-trips exactly. */
    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' || Character.isSurrogate(c) -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}

private class Damaged(message: String) : Exception(message)

/** A JSON number that is not a plain Int (fraction, exponent or out of range): kept only to be rejected by type. */
private object NotInt

/** RFC 8259 JSON, strictly: no comments, unquoted names, single quotes, duplicate names or trailing content. */
private class StrictJsonReader(private val s: String) {
    private var i = 0

    fun root(): Any? {
        space()
        val v = value(0)
        space()
        if (i != s.length) fail("JSON 뒤에 다른 내용이 있음")
        return v
    }

    private fun value(depth: Int): Any? {
        if (depth > MAX_DEPTH) fail("JSON이 너무 깊음")
        return when (val c = peek()) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> str()
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", null)
            else -> if (c == '-' || c in '0'..'9') number() else fail("JSON 값이 아님")
        }
    }

    private fun obj(depth: Int): Map<String, Any?> {
        i++
        val m = LinkedHashMap<String, Any?>()
        space()
        if (peek() == '}') return m.also { i++ }
        while (true) {
            space()
            if (peek() != '"') fail("이름이 문자열이 아님")
            val name = str()
            space()
            if (next() != ':') fail("':' 없음")
            space()
            if (m.containsKey(name)) fail("이름 중복")
            m[name] = value(depth + 1)
            space()
            when (next()) {
                ',' -> continue
                '}' -> return m
                else -> fail("객체 형식 오류")
            }
        }
    }

    private fun arr(depth: Int): List<Any?> {
        i++
        val list = ArrayList<Any?>()
        space()
        if (peek() == ']') return list.also { i++ }
        while (true) {
            space()
            list += value(depth + 1)
            space()
            when (next()) {
                ',' -> continue
                ']' -> return list
                else -> fail("배열 형식 오류")
            }
        }
    }

    private fun str(): String {
        i++
        val b = StringBuilder()
        while (true) {
            val c = next()
            when {
                c == '"' -> return b.toString()
                c == '\\' -> when (next()) {
                    '"' -> b.append('"')
                    '\\' -> b.append('\\')
                    '/' -> b.append('/')
                    'b' -> b.append('\b')
                    'f' -> b.append('\u000C')
                    'n' -> b.append('\n')
                    'r' -> b.append('\r')
                    't' -> b.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) fail("escape가 중간에 끝남")
                        val hex = s.substring(i, i + 4)
                        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("잘못된 \\u escape")
                        b.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> fail("잘못된 escape")
                }
                c < ' ' -> fail("문자열 안에 제어 문자")
                else -> b.append(c)
            }
        }
    }

    private fun number(): Any {
        val start = i
        if (peek() == '-') i++
        when (peek()) {
            '0' -> i++
            in '1'..'9' -> while (peek() in '0'..'9') i++
            else -> fail("숫자 형식 오류")
        }
        var plain = true
        if (peek() == '.') {
            plain = false
            i++
            digits()
        }
        if (peek() == 'e' || peek() == 'E') {
            plain = false
            i++
            if (peek() == '+' || peek() == '-') i++
            digits()
        }
        return if (plain) s.substring(start, i).toIntOrNull() ?: NotInt else NotInt
    }

    private fun digits() {
        if (peek() !in '0'..'9') fail("숫자 형식 오류")
        while (peek() in '0'..'9') i++
    }

    private fun word(w: String, v: Any?): Any? {
        if (!s.startsWith(w, i)) fail("JSON 값이 아님")
        i += w.length
        return v
    }

    private fun space() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    /** '\u0000' at the end, which no branch accepts. */
    private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

    private fun next(): Char {
        if (i >= s.length) fail("JSON이 중간에 끝남")
        return s[i++]
    }

    private fun fail(why: String): Nothing = throw Damaged(why)

    private companion object {
        const val MAX_DEPTH = 16
    }
}

/**
 * The seat journal in one file (meant for the app's filesDir/[FILE_NAME]; only explicit files are given to it now).
 * Single owner: one instance in one process writes it. There is no lock, compare-and-swap or multi-process support;
 * tying it to the app's lease and owner is the next step's job. It never deletes the file and has no delete API.
 *
 * - [load] and the constructor never create or change anything, and no expected I/O or security error leaves them.
 *   Only a file known not to exist ([Files.notExists]) is Empty. When existence cannot be decided, or the file cannot
 *   be read in full as format version 1 within [MAX_BYTES], it is Unreadable (the bytes stay as they are, and a
 *   leftover temp file is neither used nor removed).
 * - [save] writes only a snapshot its own [load] would read back unchanged. It refuses (false, [lastError]) to replace
 *   an Unreadable file, another job's, axis' or original's snapshot, or to change what an existing request ID stands
 *   for: the same MOVE ID keeps its purpose, start and target and every STOP already listed (same order, same
 *   afterMove); a new MOVE ID (the next attempt) only once every earlier request ended, reusing none of their IDs.
 *   Writing: temp file in the same directory → fsync → atomic replace (no fallback) → fsync of the directory; true
 *   only after all of it. A false save may still have replaced the file.
 */
class FileSeatJournal(
    private val file: File,
    private val syncDirectory: (File) -> Unit = SYNC_DIRECTORY,
    private val replace: (Path, Path) -> Unit = { from, to -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) },
    /** Opens the file for reading; replaceable only to inject a read failure in tests. */
    private val openRead: (Path) -> InputStream = { Files.newInputStream(it) },
) : SeatJournal {
    /** Why the last save failed (the step and the exception type, no values or paths); null after a success. */
    var lastError: String? = null
        private set

    override fun load(): SeatJournalLoad {
        val bytes = try {
            val path = file.toPath()
            if (Files.notExists(path)) return SeatJournalLoad.Empty
            if (!Files.exists(path)) return SeatJournalLoad.Unreadable("시트 기록이 있는지 판정하지 못함")
            readBounded(path) ?: return SeatJournalLoad.Unreadable("시트 기록이 상한 ${MAX_BYTES}바이트를 넘음")
        } catch (e: IOException) {
            return SeatJournalLoad.Unreadable("시트 기록을 읽지 못함(${e.javaClass.simpleName})")
        } catch (e: SecurityException) {
            return SeatJournalLoad.Unreadable("시트 기록을 읽지 못함(${e.javaClass.simpleName})")
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            return SeatJournalLoad.Unreadable("시트 기록이 UTF-8이 아님")
        }
        return SeatJournalCodec.decode(text)
    }

    override fun save(snapshot: SeatSnapshot): Boolean {
        val refusal = when (val current = load()) {
            SeatJournalLoad.Empty -> null
            is SeatJournalLoad.Unreadable -> "읽을 수 없는 기존 기록은 덮어쓰지 않음(${current.reason})"
            is SeatJournalLoad.Found -> overwriteProblem(current.snapshot, snapshot)
        }
        if (refusal != null) return fail(refusal)
        val text = SeatJournalCodec.encode(snapshot)
        // The same decoder as load: what could not be read back unchanged is never written.
        when (val back = SeatJournalCodec.decode(text)) {
            is SeatJournalLoad.Unreadable -> return fail("저장할 기록이 형식 제약에 맞지 않아 쓰지 않음(${back.reason})")
            is SeatJournalLoad.Found -> if (back.snapshot != snapshot) return fail("저장할 기록이 그대로 다시 읽히지 않아 쓰지 않음")
            SeatJournalLoad.Empty -> return fail("저장할 기록을 다시 읽지 못해 쓰지 않음")
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return fail("기록이 상한 ${MAX_BYTES}바이트를 넘어 쓰지 않음")
        var step = "임시 파일 쓰기"
        return try {
            val dir = file.absoluteFile.parentFile ?: throw IOException("no parent")
            val temp = File(dir, file.name + TEMP_SUFFIX)
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                step = "임시 파일 fsync"
                out.fd.sync()
            }
            step = "원자적 교체"
            replace(temp.toPath(), file.toPath())
            step = "디렉터리 fsync"
            syncDirectory(dir)
            lastError = null
            true
        } catch (e: Exception) {
            fail("저장 실패($step, ${e.javaClass.simpleName}). 주 파일이 바뀌지 않았다고 단정하지 않음")
        }
    }

    /** A request ID keeps what it stands for; the next attempt needs every earlier request ended and new IDs. */
    private fun overwriteProblem(old: SeatSnapshot, new: SeatSnapshot): String? {
        val oldIds = listOf(old.move.requestId) + old.stops.map { it.requestId }
        val newIds = listOf(new.move.requestId) + new.stops.map { it.requestId }
        return when {
            old.jobId != new.jobId -> "다른 작업의 기록은 덮어쓰지 않음"
            old.axis != new.axis -> "다른 축의 기록은 덮어쓰지 않음"
            old.original != new.original -> "원래 위치가 다른 기록은 덮어쓰지 않음"
            new.move.requestId == old.move.requestId -> when {
                new.purpose != old.purpose || new.start != old.start || new.target != old.target -> "같은 MOVE 요청의 목적·시작·목표는 바꾸지 않음"
                new.stops.size < old.stops.size ||
                    old.stops.indices.any { new.stops[it].requestId != old.stops[it].requestId || new.stops[it].afterMove != old.stops[it].afterMove } ->
                    "기존 STOP 기록은 빼거나 순서·의미를 바꾸지 않음"
                else -> null
            }
            !old.move.terminal || old.stops.any { !it.terminal } -> "끝나지 않은 요청이 있어 다른 MOVE로 바꾸지 않음"
            newIds.any { it in oldIds } -> "이전 요청 ID를 다른 요청에 다시 쓰지 않음"
            else -> null
        }
    }

    /** The whole file, or null once it is longer than [MAX_BYTES] (read in chunks, whatever its reported size). */
    private fun readBounded(path: Path): ByteArray? = openRead(path).use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        out.toByteArray()
    }

    private fun fail(why: String): Boolean {
        lastError = why
        return false
    }

    companion object {
        const val FILE_NAME = "rest-seat-recovery.json"
        const val MAX_BYTES = 256 * 1024
        const val TEMP_SUFFIX = ".tmp"
    }
}
