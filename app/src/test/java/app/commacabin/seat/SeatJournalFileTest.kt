package app.commacabin.seat

import app.commacabin.climate.FileClimateStore
import app.commacabin.control.FileControlStore
import app.commacabin.control.FilePresetStore
import app.commacabin.control.Move
import app.commacabin.control.SYNC_DIRECTORY
import app.commacabin.control.SeatAxis
import app.commacabin.lighting.FileLightStore
import app.commacabin.probe.FakeScheduler
import app.commacabin.probe.FileRecoveryStore
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SeatJournalFileTest {
    @get:Rule val temp = TemporaryFolder()

    private val done = SeatRequestState.COMPLETED

    /** A pending attempt: MOVE entered, unanswered; one STOP answered. */
    private val base = SeatSnapshot(
        "job-1", SeatPurpose.APPLY, SeatAxis.BACKREST, 20, 20, 60, SeatRequestMark("move-1", SeatRequestState.ENTERING),
        listOf(SeatRequestMark("stop-1", done, "onComplete(STOP)", timedOut = false, afterMove = true)),
    )

    private fun newFile(): File = File(temp.newFolder(), FileSeatJournal.FILE_NAME)

    private fun listing(dir: File): Map<String, List<Byte>> = dir.listFiles()!!.associate { it.name to it.readBytes().toList() }

    private fun loaded(f: File) = FileSeatJournal(f).load()

    // --- round trip ---

    @Test
    fun `every field, several STOPs, nulls, timeouts and held survive a save and a new instance`() {
        val full = SeatSnapshot(
            "job-한글", SeatPurpose.RESTORE, SeatAxis.TILT, Int.MIN_VALUE, 0, Int.MAX_VALUE,
            SeatRequestMark("move-1", done, "onComplete(FORWARD)", timedOut = true, afterMove = false),
            listOf(
                SeatRequestMark("stop-1", SeatRequestState.FAILED, "onFailed(\"x\" \\ / \n\t 한글 \u0001 😀 \uD800)", timedOut = true),
                SeatRequestMark("final-1", done, null, timedOut = false, afterMove = true),
                SeatRequestMark("stop-x", SeatRequestState.ENTERING, null, timedOut = true, afterMove = true),
            ),
            SeatOutcome.HELD, "화면 이탈",
        )
        val pending = SeatSnapshot("job-2", SeatPurpose.APPLY, SeatAxis.FORE_AFT, 0, 0, -5, SeatRequestMark("move-9"))
        for (s in listOf(full, pending, base)) {
            val f = newFile()
            val journal = FileSeatJournal(f)
            assertTrue(journal.save(s))
            assertNull(journal.lastError)
            assertEquals(SeatJournalLoad.Found(s), FileSeatJournal(f).load())
        }
        val text = SeatJournalCodec.encode(pending)
        assertTrue(text.startsWith("{\"formatVersion\":1,\"snapshot\":{"))
        assertTrue(text.contains("\"stops\":[]") && text.contains("\"outcome\":null") && text.contains("\"held\":null") && text.contains("\"answer\":null"))
    }

    @Test
    fun `a missing file is Empty, an empty or blank one Unreadable, and load or construction writes nothing`() {
        val dir = temp.newFolder()
        val f = File(dir, FileSeatJournal.FILE_NAME)
        val journal = FileSeatJournal(f)
        assertEquals(SeatJournalLoad.Empty, journal.load())
        assertTrue(dir.listFiles()!!.isEmpty())

        for (content in listOf("", "   \n")) {
            f.writeText(content)
            val before = listing(dir)
            assertTrue(content, FileSeatJournal(f).load() is SeatJournalLoad.Unreadable)
            assertEquals(before, listing(dir))
        }
        // A leftover temp file is neither used nor removed.
        f.delete()
        File(dir, FileSeatJournal.FILE_NAME + FileSeatJournal.TEMP_SUFFIX).writeText(SeatJournalCodec.encode(base))
        val before = listing(dir)
        assertEquals(SeatJournalLoad.Empty, FileSeatJournal(f).load())
        assertEquals(before, listing(dir))
    }

    // --- strict format ---

    @Test
    fun `malformed or unsupported content is Unreadable, never repaired, and save leaves its bytes alone`() {
        val t = SeatJournalCodec.encode(base)
        val stops = t.substringAfter("\"stops\":").substringBefore(",\"outcome\"")
        val bad = listOf(
            t.dropLast(1), t + " x", t + t, "/*c*/$t", "[$t]", "{\"formatVersion\":1,\"snapshot\":null}",
            t.replace("\"formatVersion\"", "formatVersion"),
            t.replace("\"job-1\"", "'job-1'"),
            t.replace("\"formatVersion\":1,", ""),
            t.replace("\"formatVersion\":1", "\"formatVersion\":\"1\""),
            t.replace("\"formatVersion\":1", "\"formatVersion\":1.0"),
            t.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            t.replace("{\"formatVersion\":1,", "{\"formatVersion\":1,\"extra\":0,"),
            t.replace(",\"held\":null", ""),
            t.replace("\"held\":null", "\"held\":null,\"note\":null"),
            t.replaceFirst("\"afterMove\":false}", "\"afterMove\":false,\"x\":1}"),
            t.replaceFirst("\"answer\":null,", ""),
            t.replace("\"original\":20", "\"original\":1.5"),
            t.replace("\"original\":20", "\"original\":\"20\""),
            t.replace("\"original\":20", "\"original\":2e1"),
            t.replace("\"original\":20", "\"original\":2147483648"),
            t.replace("\"original\":20", "\"original\":-2147483649"),
            t.replace("\"original\":20", "\"original\":true"),
            t.replace("\"original\":20", "\"original\":null"),
            t.replace("\"start\":20", "\"start\":020"),
            t.replaceFirst("\"timedOut\":false", "\"timedOut\":\"false\""),
            t.replaceFirst("\"timedOut\":false", "\"timedOut\":0"),
            t.replaceFirst("\"timedOut\":false", "\"timedOut\":null"),
            t.replace("\"APPLY\"", "\"apply\""),
            t.replace("\"BACKREST\"", "\"SEAT\""),
            t.replace("\"ENTERING\"", "\"DONE\""),
            t.replace("\"outcome\":null", "\"outcome\":\"OK\""),
            t.replace("\"job-1\"", "\"\""),
            t.replace("\"job-1\"", "\"  \""),
            t.replace("\"move-1\"", "\"\""),
            t.replace("\"stop-1\"", "\"move-1\""),
            t.replace("\"stops\":$stops", "\"stops\":{}"),
            t.replace("\"stops\":[", "\"stops\":[1,"),
            t.replace("\"jobId\":\"job-1\",", "\"jobId\":\"job-1\",\"jobId\":\"job-1\","),
            t.replace("\"held\":null", "\"held\":" + "[".repeat(40) + "]".repeat(40)),
            "[".repeat(200_000),
            t.replace("job-1", "job\u0001-1"),
            t.replace("onComplete(STOP)", "onComplete\\x"),
        )
        val badBytes = bad.map { it.toByteArray(Charsets.UTF_8) } + listOf(t.toByteArray(Charsets.UTF_8).let { it.copyOf(it.size + 1).also { b -> b[b.size - 1] = 0xFF.toByte() } })
        badBytes.forEachIndexed { n, bytes ->
            val f = newFile()
            f.writeBytes(bytes)
            val journal = FileSeatJournal(f)
            val got = journal.load()
            assertTrue("case $n: $got", got is SeatJournalLoad.Unreadable)
            assertFalse("case $n", journal.save(base))
            assertTrue("case $n", journal.lastError!!.contains("읽을 수 없는"))
            assertArrayEquals("case $n", bytes, f.readBytes())
            assertEquals("case $n", listOf(f.name), f.parentFile!!.list()!!.toList())
        }
        val version = FileSeatJournal(newFile().also { it.writeText(t.replace("\"formatVersion\":1", "\"formatVersion\":2")) }).load()
        assertTrue((version as SeatJournalLoad.Unreadable).reason.contains("버전 2"))
    }

    @Test
    fun `the 256 KiB read limit is enforced on the bytes actually read`() {
        val t = SeatJournalCodec.encode(base)
        val atLimit = newFile().also { it.writeText(t + " ".repeat(FileSeatJournal.MAX_BYTES - t.length)) }
        assertEquals(FileSeatJournal.MAX_BYTES.toLong(), atLimit.length())
        assertEquals(SeatJournalLoad.Found(base), loaded(atLimit))

        val over = newFile().also { it.writeText(t + " ".repeat(FileSeatJournal.MAX_BYTES - t.length + 1)) }
        val bytes = over.readBytes()
        val journal = FileSeatJournal(over)
        assertTrue((journal.load() as SeatJournalLoad.Unreadable).reason.contains("상한"))
        assertFalse(journal.save(base))
        assertArrayEquals(bytes, over.readBytes())

        // A snapshot whose encoding would exceed the limit is not written.
        val f = newFile()
        val big = FileSeatJournal(f)
        assertFalse(big.save(base.copy(held = "x".repeat(FileSeatJournal.MAX_BYTES))))
        assertTrue(big.lastError!!.contains("상한"))
        assertFalse(f.exists())
    }

    // --- overwrite rules ---

    @Test
    fun `another job, axis or original, or a left-out unfinished request, is never written over`() {
        val f = newFile()
        val journal = FileSeatJournal(f)
        assertTrue(journal.save(base))
        val bytes = f.readBytes()
        val refused = listOf(
            base.copy(jobId = "job-2"),
            base.copy(axis = SeatAxis.HEIGHT),
            base.copy(original = 30),
            base.copy(move = SeatRequestMark("move-2")), // the unanswered MOVE left out
        )
        for (s in refused) {
            assertFalse(s.toString(), journal.save(s))
            assertNotNull(journal.lastError)
            assertArrayEquals(bytes, f.readBytes())
        }
        // The same attempt goes on: its MOVE answers, one more STOP is pending.
        val going = base.copy(
            move = base.move.copy(state = done, answer = "onComplete(FORWARD)"),
            stops = base.stops + SeatRequestMark("final-1", SeatRequestState.ENTERING, afterMove = true),
        )
        assertTrue(journal.save(going))
        assertFalse(journal.save(going.copy(stops = going.stops.take(1)))) // the pending STOP left out
        assertTrue(journal.lastError!!.contains("STOP"))
        // Every request ended: the next attempt of the same job and original may replace it.
        val ended = going.copy(stops = going.stops.map { it.copy(state = done) }, outcome = SeatOutcome.UNCONFIRMED)
        assertTrue(journal.save(ended))
        val next = SeatSnapshot("job-1", SeatPurpose.RESTORE, SeatAxis.BACKREST, 20, 59, 20, SeatRequestMark("move-2"))
        assertTrue(journal.save(next))
        assertEquals(SeatJournalLoad.Found(next), loaded(f))
    }

    // --- write failures ---

    @Test
    fun `no atomic move means no write and no fallback, the old file stays`() {
        val f = newFile()
        assertTrue(FileSeatJournal(f).save(base))
        val bytes = f.readBytes()
        val journal = FileSeatJournal(f, replace = { _, _ -> throw AtomicMoveNotSupportedException(null, null, "test") })
        assertFalse(journal.save(base.copy(stops = base.stops + SeatRequestMark("stop-2", SeatRequestState.ENTERING))))
        assertTrue(journal.lastError!!.contains("원자적 교체"))
        assertTrue(journal.lastError!!.contains("단정하지 않음"))
        assertFalse(journal.lastError!!.contains(temp.root.path))
        assertArrayEquals(bytes, f.readBytes())

        val fresh = newFile()
        assertFalse(FileSeatJournal(fresh, replace = { _, _ -> throw AtomicMoveNotSupportedException(null, null, "test") }).save(base))
        assertFalse(fresh.exists())

        val noDir = File(File(temp.root, "missing"), FileSeatJournal.FILE_NAME)
        val j = FileSeatJournal(noDir)
        assertFalse(j.save(base))
        assertTrue(j.lastError!!.contains("임시 파일 쓰기"))
        assertFalse(noDir.parentFile!!.exists())
    }

    @Test
    fun `a directory fsync failure after the replace is a failed save, yet the file did change`() {
        val f = newFile()
        val journal = FileSeatJournal(f, syncDirectory = { throw IOException("fsync dir") })
        assertFalse(journal.save(base))
        assertTrue(journal.lastError!!.contains("디렉터리 fsync"))
        assertEquals(SeatJournalLoad.Found(base), loaded(f)) // the replace had happened
        assertTrue(FileSeatJournal(f).save(base)) // a later good save of the same attempt
        assertNull(FileSeatJournal(f).lastError)
    }

    // --- with the core ---

    private fun core(port: FakeSeatPort, journal: SeatJournal) =
        SeatMoveCore(SeatAxis.BACKREST, port, journal, FakeScheduler(), { null }, { null })

    @Test
    fun `core - a confirmed attempt is on disk as the core sees it, and a new run accepts it`() {
        val f = newFile()
        val port = FakeSeatPort(mutableListOf()).also { it.position = 20 }
        val scheduler = FakeScheduler()
        val c = SeatMoveCore(SeatAxis.BACKREST, port, FileSeatJournal(f), scheduler, { null }, { null })
        assertNull(c.begin(req()))
        port.moves[0].ok("FORWARD")
        port.emit(59)
        port.stops[0].ok("STOP")
        scheduler.advance(LIMITS.settleMs)
        scheduler.advance(LIMITS.confirmIntervalMs)
        assertEquals(SeatOutcome.CONFIRMED, c.state.outcome)
        assertEquals(SeatJournalLoad.Found(c.state.snapshot!!), loaded(f))
        val again = core(FakeSeatPort(mutableListOf()), FileSeatJournal(f))
        assertNull(again.state.lock)
        assertEquals(20, again.state.snapshot!!.original)
    }

    @Test
    fun `core - a first save failing after the replace sends no MOVE, and the next run stays locked`() {
        val f = newFile()
        val port = FakeSeatPort(mutableListOf()).also { it.position = 20 }
        val journal = FileSeatJournal(f, syncDirectory = { throw IOException("fsync dir") })
        val c = core(port, journal)
        assertNull(c.begin(req()))
        assertEquals(SeatOutcome.NOT_SENT, c.state.outcome)
        assertTrue(port.moveCalls.isEmpty())
        assertTrue(c.state.lock!!.contains("저장 실패"))
        assertTrue(journal.lastError!!.contains("디렉터리 fsync"))
        // The file changed anyway: a pending attempt that never got its MOVE.
        val onDisk = (loaded(f) as SeatJournalLoad.Found).snapshot
        assertEquals(SeatRequestState.SAVED, onDisk.move.state)
        assertNull(onDisk.outcome)

        val bytes = f.readBytes()
        val port2 = FakeSeatPort(mutableListOf()).also { it.position = 20 }
        val next = core(port2, FileSeatJournal(f))
        assertNotNull(next.state.lock)
        assertEquals(next.state.lock, next.begin(req(n = 2)))
        assertNotNull(next.stop("수동", "stop-x"))
        assertTrue(port2.subs.isEmpty() && port2.moveCalls.isEmpty() && port2.stops.isEmpty())
        assertArrayEquals(bytes, f.readBytes())
    }

    @Test
    fun `core - saves failing while the seat moves still let the STOP go, and the pending STOP stays on disk`() {
        val f = newFile()
        var failSync = false
        val journal = FileSeatJournal(f, syncDirectory = { if (failSync) throw IOException("fsync dir") else SYNC_DIRECTORY(it) })
        val port = FakeSeatPort(mutableListOf()).also { it.position = 20 }
        val c = core(port, journal)
        assertNull(c.begin(req()))
        assertEquals(listOf(Move.FORWARD), port.entered)
        failSync = true
        port.emit(59)
        assertEquals(1, port.stops.size)
        assertTrue(c.state.lock!!.contains("저장 실패"))
        val onDisk = (loaded(f) as SeatJournalLoad.Found).snapshot
        assertEquals(listOf("stop-1"), onDisk.stops.map { it.requestId })
        assertFalse(onDisk.stops[0].terminal)

        val next = core(FakeSeatPort(mutableListOf()), FileSeatJournal(f))
        assertNotNull(next.state.lock)
    }

    @Test
    fun `core - an unreadable file locks the core and keeps its bytes`() {
        val f = newFile().also { it.writeText("{\"formatVersion\":2}") }
        val bytes = f.readBytes()
        val port = FakeSeatPort(mutableListOf()).also { it.position = 20 }
        val c = core(port, FileSeatJournal(f))
        assertNotNull(c.state.lock)
        assertNotNull(c.begin(req()))
        assertTrue(port.subs.isEmpty())
        assertArrayEquals(bytes, f.readBytes())
    }

    // --- independence from the other record files ---

    @Test
    fun `its own file name, and the app's other record files are not touched`() {
        val others = listOf(
            FileControlStore.FILE_NAME, FileRecoveryStore.FILE_NAME, FileLightStore.FILE_NAME, FileClimateStore.FILE_NAME, FilePresetStore.FILE_NAME,
        )
        assertFalse(FileSeatJournal.FILE_NAME in others)
        val dir = temp.newFolder()
        others.forEach { File(dir, it).writeText("{\"keep\":\"$it\"}") }
        val before = listing(dir)
        val journal = FileSeatJournal(File(dir, FileSeatJournal.FILE_NAME))
        assertEquals(SeatJournalLoad.Empty, journal.load())
        assertTrue(journal.save(base))
        val after = listing(dir)
        assertEquals(before, after - FileSeatJournal.FILE_NAME)
        assertEquals((others + FileSeatJournal.FILE_NAME).toSet(), after.keys)
    }

    // --- STEP 26 review ---

    @Test
    fun `review 1 - a snapshot its own load could not read back is not written, and nothing changes`() {
        val invalid = listOf(
            base.copy(jobId = ""),
            base.copy(jobId = "  "),
            base.copy(move = base.move.copy(requestId = "")),
            base.copy(stops = base.stops + base.stops), // a repeated STOP ID
            base.copy(stops = base.stops + SeatRequestMark("move-1", SeatRequestState.ENTERING)), // a STOP with the MOVE's ID
        )
        for (s in invalid) {
            val dir = temp.newFolder()
            val journal = FileSeatJournal(File(dir, FileSeatJournal.FILE_NAME))
            assertFalse(s.toString(), journal.save(s))
            assertTrue(journal.lastError!!.contains("형식 제약"))
            assertTrue(dir.list()!!.isEmpty()) // no file, no temp file
        }
        val f = newFile()
        assertTrue(FileSeatJournal(f).save(base))
        val before = listing(f.parentFile!!)
        val journal = FileSeatJournal(f)
        assertFalse(journal.save(base.copy(stops = base.stops + base.stops)))
        assertTrue(journal.lastError!!.contains("형식 제약"))
        assertEquals(before, listing(f.parentFile!!))
    }

    @Test
    fun `review 2 - only a file known not to exist is Empty, undecidable or failing access is Unreadable and kept`() {
        val dir = temp.newFolder()
        val regular = File(dir, "regular").also { it.writeText("keep") }
        val under = FileSeatJournal(File(regular, FileSeatJournal.FILE_NAME)) // existence cannot be decided
        val got = under.load()
        assertTrue(got.toString(), got is SeatJournalLoad.Unreadable)
        assertFalse(under.save(base))
        assertEquals("keep", regular.readText())
        assertEquals(listOf("regular"), dir.list()!!.toList())

        val asDir = File(temp.newFolder(), FileSeatJournal.FILE_NAME).also { it.mkdir() }
        val d = FileSeatJournal(asDir)
        assertTrue(d.load() is SeatJournalLoad.Unreadable)
        assertFalse(d.save(base))
        assertTrue(asDir.isDirectory && asDir.list()!!.isEmpty())

        val f = newFile()
        assertTrue(FileSeatJournal(f).save(base))
        val bytes = f.readBytes()
        for (e in listOf(SecurityException("denied"), IOException("io"))) {
            val j = FileSeatJournal(f, openRead = { throw e })
            val r = j.load() // never thrown out of the port
            assertTrue(r.toString(), r is SeatJournalLoad.Unreadable)
            assertFalse(j.save(base.copy(move = base.move.copy(state = done))))
            assertArrayEquals(bytes, f.readBytes())
        }
    }

    @Test
    fun `review 3 - a request ID keeps what it stands for, pending or not`() {
        val f = newFile()
        val journal = FileSeatJournal(f)
        assertTrue(journal.save(base)) // MOVE move-1 pending
        val bytes = f.readBytes()
        val changed = listOf(
            base.copy(start = 25),
            base.copy(target = 70),
            base.copy(purpose = SeatPurpose.RESTORE),
            base.copy(stops = listOf(base.stops[0].copy(afterMove = false))), // a STOP's meaning changed
            base.copy(stops = emptyList()), // an answered STOP left out
            base.copy(move = SeatRequestMark("stop-1"), stops = listOf(SeatRequestMark("move-1", done))), // roles swapped
            base.copy(move = SeatRequestMark("move-2")), // the pending MOVE replaced
        )
        for (s in changed) {
            assertFalse(s.toString(), journal.save(s))
            assertArrayEquals(bytes, f.readBytes())
        }
        val two = base.copy(stops = base.stops + SeatRequestMark("stop-2", SeatRequestState.ENTERING, afterMove = true))
        assertTrue(journal.save(two))
        assertFalse(journal.save(two.copy(stops = two.stops.reversed()))) // STOP order changed
        // Every request ended: the same IDs still keep their meaning, and the next attempt needs new ones.
        val ended = two.copy(move = two.move.copy(state = done), stops = two.stops.map { it.copy(state = done) }, outcome = SeatOutcome.UNCONFIRMED)
        assertTrue(journal.save(ended))
        val endedBytes = f.readBytes()
        assertFalse(journal.save(ended.copy(target = 30)))
        assertFalse(journal.save(SeatSnapshot("job-1", SeatPurpose.RESTORE, SeatAxis.BACKREST, 20, 59, 20, SeatRequestMark("stop-2"))))
        assertTrue(journal.lastError!!.contains("이전 요청 ID"))
        assertArrayEquals(endedBytes, f.readBytes())
        assertTrue(journal.save(SeatSnapshot("job-1", SeatPurpose.RESTORE, SeatAxis.BACKREST, 20, 59, 20, SeatRequestMark("move-2"))))
    }

    @Test
    fun `review 4 - the reader takes standard JSON with any spacing, order and escapes, and rejects non-standard forms`() {
        val pretty = """
            {
              "snapshot" : {
                "held" : "h\u00e9\n\t\"q\" \\ \/ \b\f\r \uD83D\uDE00",
                "outcome" : null, "stops" : [ ], "target" : -2147483648, "start" : 2147483647, "original" : -0,
                "axis" : "LUMBAR", "purpose" : "RESTORE", "jobId" : "j",
                "move" : { "afterMove" : true, "timedOut" : false, "answer" : null, "state" : "SAVED", "requestId" : "m" }
              },
              "formatVersion" : 1
            }
        """.trimIndent()
        val expected = SeatSnapshot(
            "j", SeatPurpose.RESTORE, SeatAxis.LUMBAR, 0, Int.MAX_VALUE, Int.MIN_VALUE,
            SeatRequestMark("m", SeatRequestState.SAVED, null, timedOut = false, afterMove = true),
            held = "h\u00e9\n\t\"q\" \\ / \b\u000C\r \uD83D\uDE00",
        )
        assertEquals(SeatJournalLoad.Found(expected), SeatJournalCodec.decode(pretty))
        assertEquals(SeatJournalLoad.Found(expected), SeatJournalCodec.decode(SeatJournalCodec.encode(expected)))

        val t = SeatJournalCodec.encode(base)
        val bad = listOf("-", "1.", ".5", "+1", "-01", "1e", "1e+", "0x10", "Infinity", "NaN").map { t.replace("\"original\":20", "\"original\":$it") } +
            listOf(
                t.replace("onComplete(STOP)", "onComplete\\u12G4"),
                t.replace("onComplete(STOP)", "line\nbreak"),
                t.replace("onComplete(STOP)\"", "onComplete(STOP)\\\""),
                t.replace("\"stops\":[", "\"stops\":[,"),
                t.replace("}],", "},],"),
                t.replace("\"held\":null}}", "\"held\":null,}}"),
                t.replace("null", "nul"),
                t.replace("\"original\":20", "\"original\":\"\\u0032\""),
            )
        bad.forEachIndexed { n, text -> assertTrue("case $n: $text", SeatJournalCodec.decode(text) is SeatJournalLoad.Unreadable) }
    }
}
