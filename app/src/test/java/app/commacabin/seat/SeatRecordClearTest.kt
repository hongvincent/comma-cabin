package app.commacabin.seat

import app.commacabin.control.SYNC_DIRECTORY
import app.commacabin.control.SeatAxis
import app.commacabin.probe.FakeScheduler
import app.commacabin.probe.SDK_ERROR
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SeatRecordClearTest {
    @get:Rule val temp = TemporaryFolder()

    private val apply = req(start = 20, target = 60, n = 1)
    private val restore = req(purpose = SeatPurpose.RESTORE, start = 59, target = 20, original = 20, n = 2)

    /** One process: a core on a real journal file, a fake seat and fake time. */
    private inner class Proc(val file: File, journal: SeatJournal = FileSeatJournal(file)) {
        val port = FakeSeatPort(mutableListOf())
        val scheduler = FakeScheduler()
        var safe: String? = null
        val core = SeatMoveCore(SeatAxis.BACKREST, port, journal, scheduler, { safe }, { null })

        fun calls() = listOf(port.subs.size, port.reads, port.moveCalls.size, port.stops.size, port.ended.size)

        /** MOVE answered, the seat reaches [at], STOP answered, the reads after it run. */
        fun run(r: SeatMoveRequest, at: Int) {
            port.position = r.start
            assertNull(core.begin(r))
            port.moves.last().ok("MOVE")
            port.emit(at)
            port.stops.last().ok("STOP")
            scheduler.advance(LIMITS.settleMs)
            scheduler.advance(LIMITS.confirmIntervalMs)
        }

        fun applied() = run(apply, 59).also { assertEquals(SeatOutcome.CONFIRMED, core.state.outcome) }
        fun restored() = run(restore, 21).also { assertEquals(SeatOutcome.CONFIRMED, core.state.outcome) }
    }

    private fun recordFile() = File(temp.newFolder(), FileSeatJournal.FILE_NAME)

    /** [what] is refused: the file bytes and the seat calls stay as they were. */
    private fun refused(p: Proc, what: String) {
        val bytes = if (p.file.exists()) p.file.readBytes() else null
        val calls = p.calls()
        assertNotNull(what, p.core.clearRecord())
        assertEquals(what, bytes?.toList(), if (p.file.exists()) p.file.readBytes().toList() else null)
        assertEquals(what, calls, p.calls())
    }

    @Test
    fun `apply confirmed, restore confirmed, explicit clean-up, then a new job with new IDs`() {
        val f = recordFile()
        val neighbour = File(f.parentFile, "other-record.json").also { it.writeText("keep") }
        val p = Proc(f)
        p.applied()
        assertFalse(p.core.state.clearable)
        refused(p, "apply only")
        p.restored()
        assertTrue(p.core.state.clearable)
        val calls = p.calls()
        assertNull(p.core.clearRecord())
        assertEquals(calls, p.calls()) // no MOVE, STOP, read or endMotion for it
        assertFalse(f.exists())
        assertEquals("keep", neighbour.readText())
        assertNull(p.core.state.snapshot)
        assertFalse(p.core.state.clearable)
        assertNotNull(p.core.clearRecord()) // the same evidence cannot be used twice

        p.run(req(job = "job-2", start = 21, target = 60, n = 3), 59)
        assertEquals(SeatOutcome.CONFIRMED, p.core.state.outcome)
        val bytes = f.readBytes()
        p.port.stops[0].ok("STOP") // repeated answers of the first job's requests
        p.port.moves[0].ok("MOVE")
        assertEquals(SeatOutcome.CONFIRMED, p.core.state.outcome)
        assertArrayEquals(bytes, f.readBytes())
        assertEquals("job-2", (FileSeatJournal(f).load() as SeatJournalLoad.Found).snapshot.jobId)
        assertNotNull(p.core.begin(req(job = "job-3", n = 1))) // used IDs stay used
    }

    @Test
    fun `nothing but this run's confirmed restore or certain non-send is cleaned`() {
        // APPLY held or unconfirmed.
        Proc(recordFile()).also { p ->
            p.port.position = 20
            assertNull(p.core.begin(apply))
            p.port.moves[0].ok("MOVE")
            assertNull(p.core.interrupt("화면 이탈", "stop-i"))
            p.port.stops[0].ok("STOP")
            p.scheduler.advance(LIMITS.settleMs)
            p.scheduler.advance(LIMITS.confirmIntervalMs)
            assertEquals(SeatOutcome.HELD, p.core.state.outcome)
            refused(p, "apply held")
        }
        // RESTORE whose MOVE never answers: still running.
        Proc(recordFile()).also { p ->
            p.applied()
            p.port.position = 59
            assertNull(p.core.begin(restore))
            p.port.emit(21)
            p.port.stops.last().ok("STOP")
            p.scheduler.advance(LIMITS.answerMs)
            refused(p, "move timed out")
        }
        // RESTORE whose STOP failed.
        Proc(recordFile()).also { p ->
            p.applied()
            p.port.position = 59
            assertNull(p.core.begin(restore))
            p.port.moves.last().ok("MOVE")
            p.port.emit(21)
            p.port.stops.last().err(SDK_ERROR)
            assertEquals(SeatOutcome.STOP_FAILED, p.core.state.outcome)
            refused(p, "stop failed")
        }
        // RESTORE not confirmed after the STOP.
        Proc(recordFile()).also { p ->
            p.applied()
            p.port.position = 59
            assertNull(p.core.begin(restore))
            p.port.moves.last().ok("MOVE")
            p.port.emit(21)
            p.port.position = 30
            p.port.stops.last().ok("STOP")
            p.scheduler.advance(LIMITS.settleMs)
            assertEquals(SeatOutcome.UNCONFIRMED, p.core.state.outcome)
            refused(p, "restore unconfirmed")
        }
        // A restarted core holds no evidence, even for a stored, consistent RESTORE CONFIRMED.
        val f = recordFile()
        Proc(f).also { it.applied(); it.restored() }
        Proc(f).also { p ->
            assertNull(p.core.state.lock)
            assertFalse(p.core.state.clearable)
            p.port.position = 20 // the seat reads its original position: no evidence either
            refused(p, "restarted")
            assertEquals(0, p.port.reads)
        }
        // RESTORE refused before it was saved: the earlier APPLY record stays.
        Proc(recordFile()).also { p ->
            p.applied()
            p.port.position = 40
            assertNull(p.core.begin(restore))
            assertEquals(SeatOutcome.NOT_SENT, p.core.state.outcome)
            refused(p, "restore preparation refused")
            assertEquals(SeatPurpose.APPLY, (FileSeatJournal(p.file).load() as SeatJournalLoad.Found).snapshot.purpose)
        }
        // The first save failed after its replace: SAVED stays on disk, and a fault.
        val g = recordFile()
        Proc(g, FileSeatJournal(g, syncDirectory = { throw IOException("fsync dir") })).also { p ->
            p.port.position = 20
            assertNull(p.core.begin(apply))
            assertEquals(SeatOutcome.NOT_SENT, p.core.state.outcome)
            refused(p, "first save failed")
            assertEquals(SeatRequestState.SAVED, (FileSeatJournal(g).load() as SeatJournalLoad.Found).snapshot.move.state)
        }
    }

    @Test
    fun `a new APPLY certainly never sent is cleaned only without an earlier record and with original == start`() {
        fun notSent(p: Proc, r: SeatMoveRequest) {
            p.port.holdDispatch = true
            p.port.position = r.start
            assertNull(p.core.begin(r))
            p.safe = "기어 P 아님" // lost while the MOVE waits: refused at its gate
            p.port.runQueued()
            p.safe = null
            p.port.holdDispatch = false
            assertEquals(SeatOutcome.NOT_SENT, p.core.state.outcome)
        }
        Proc(recordFile()).also { p ->
            notSent(p, apply)
            assertTrue(p.core.state.clearable)
            assertNull(p.core.clearRecord())
            assertFalse(p.file.exists())
        }
        Proc(recordFile()).also { p ->
            notSent(p, req(start = 20, target = 60, original = 10))
            refused(p, "original != start")
        }
        Proc(recordFile()).also { p ->
            p.applied() // an earlier, real APPLY of the same job
            notSent(p, req(start = 20, target = 60, original = 20, n = 3)) // original == start, but the job had a record
            refused(p, "earlier record")
            assertEquals(SeatOutcome.NOT_SENT, (FileSeatJournal(p.file).load() as SeatJournalLoad.Found).snapshot.outcome)
        }
    }

    @Test
    fun `old evidence never removes a newer or changed record`() {
        // A new attempt begun after the evidence.
        Proc(recordFile()).also { p ->
            p.applied()
            p.restored()
            p.port.holdDispatch = true
            p.port.position = 21
            assertNull(p.core.begin(req(start = 21, target = 60, original = 20, n = 3)))
            refused(p, "newer attempt running")
            p.port.runQueued()
            p.port.moves.last().ok("MOVE")
            p.port.emit(59)
            p.port.stops.last().ok("STOP")
            p.scheduler.advance(LIMITS.settleMs)
            p.scheduler.advance(LIMITS.confirmIntervalMs)
            refused(p, "the restore's evidence is gone")
            assertTrue(p.file.exists())
        }
        // The file changed on disk after the evidence: another valid snapshot, a broken file, another version.
        val changes = listOf<(File) -> Unit>(
            { f -> f.writeText(f.readText().replace("onComplete(STOP)", "onComplete(STOP!)")) },
            { f -> f.writeText("{") },
            { f -> f.writeText("{\"formatVersion\":2}") },
        )
        changes.forEachIndexed { n, change ->
            val p = Proc(recordFile())
            p.applied()
            p.restored()
            change(p.file)
            val bytes = p.file.readBytes()
            val calls = p.calls()
            assertNotNull("case $n", p.core.clearRecord())
            assertArrayEquals("case $n", bytes, p.file.readBytes())
            assertNotNull("case $n", p.core.state.lock) // a failed clean-up latches a fault
            assertNotNull(p.core.begin(req(job = "job-9", n = 9)))
            assertEquals(calls, p.calls())
        }
    }

    @Test
    fun `a failing delete or directory fsync is a failed clean-up, the core locks, and nothing else is called`() {
        val f = recordFile()
        val p = Proc(f, FileSeatJournal(f, remove = { throw IOException("delete") }))
        p.applied()
        p.restored()
        val calls = p.calls()
        assertNotNull(p.core.clearRecord())
        assertTrue(f.exists()) // the delete itself failed
        assertNotNull(p.core.state.lock)
        assertNotNull(p.core.begin(req(job = "job-2", n = 3)))
        assertNotNull(p.core.clearRecord()) // no retry
        assertEquals(calls, p.calls())

        val g = recordFile()
        var failSync = false
        val q = Proc(g, FileSeatJournal(g, syncDirectory = { if (failSync) throw IOException("fsync dir") else SYNC_DIRECTORY(it) }))
        q.applied()
        q.restored()
        failSync = true
        assertNotNull(q.core.clearRecord())
        assertFalse(g.exists()) // already deleted when the directory fsync failed
        assertNotNull(q.core.state.lock)
        assertTrue(q.core.state.lock!!.contains("정리 실패"))
    }

    @Test
    fun `the file journal deletes only its own finished, exactly expected record`() {
        val done = SeatSnapshot(
            "job-1", SeatPurpose.RESTORE, SeatAxis.BACKREST, 20, 59, 20, SeatRequestMark("move-1", SeatRequestState.COMPLETED),
            listOf(SeatRequestMark("stop-1", SeatRequestState.COMPLETED, afterMove = true)), SeatOutcome.CONFIRMED,
        )
        val pending = done.copy(stops = listOf(done.stops[0].copy(state = SeatRequestState.ENTERING)), outcome = null)
        val f = recordFile()
        val journal = FileSeatJournal(f)
        assertFalse(journal.clear(done)) // no file
        assertTrue(journal.save(pending))
        val bytes = f.readBytes()
        assertFalse(journal.clear(pending)) // equal, but unfinished
        assertFalse(journal.clear(done)) // finished, but not what is on disk
        assertArrayEquals(bytes, f.readBytes())
        assertTrue(journal.save(done))
        val tmp = File(f.parentFile, f.name + FileSeatJournal.TEMP_SUFFIX).also { it.writeText("tmp") }
        val other = File(f.parentFile, "rest-lighting-records.json").also { it.writeText("other") }
        assertTrue(journal.clear(done))
        assertNull(journal.lastError)
        assertFalse(f.exists())
        assertEquals("tmp", tmp.readText())
        assertEquals("other", other.readText())

        val u = recordFile().also { it.writeText("{\"formatVersion\":2}") }
        val ub = u.readBytes()
        val uj = FileSeatJournal(u)
        assertFalse(uj.clear(done))
        assertTrue(uj.lastError!!.contains("읽을 수 없는"))
        assertArrayEquals(ub, u.readBytes())

        val d = recordFile()
        val dj = FileSeatJournal(d, remove = { throw SecurityException("denied") })
        assertTrue(dj.save(done))
        assertFalse(dj.clear(done))
        assertTrue(dj.lastError!!.contains("기록 파일 삭제") && dj.lastError!!.contains("SecurityException"))
        assertFalse(dj.lastError!!.contains(temp.root.path))
        assertTrue(d.exists())
    }

    @Test
    fun `the core locks a loaded record whose afterMove does not fit its MOVE, and marks STOPs after a non-send as before`() {
        val bad = listOf(
            SeatSnapshot("job-1", SeatPurpose.APPLY, SeatAxis.BACKREST, 20, 20, 60, SeatRequestMark("move-1", SeatRequestState.NOT_SENT),
                listOf(SeatRequestMark("stop-1", SeatRequestState.COMPLETED, afterMove = true)), SeatOutcome.NOT_SENT),
            SeatSnapshot("job-1", SeatPurpose.APPLY, SeatAxis.BACKREST, 20, 20, 60, SeatRequestMark("move-1", SeatRequestState.COMPLETED),
                listOf(SeatRequestMark("stop-1", SeatRequestState.COMPLETED, afterMove = true), SeatRequestMark("stop-2", SeatRequestState.COMPLETED)),
                SeatOutcome.CONFIRMED),
        )
        for (s in bad) assertNotNull(s.toString(), SeatMoveCore(SeatAxis.BACKREST, FakeSeatPort(mutableListOf()), FakeJournal(mutableListOf(), s), FakeScheduler(), { null }, { null }).state.lock)

        // A STOP sent while a MOVE that was never sent awaits its first STOP's answer is "before" the MOVE.
        val f = recordFile()
        val p = Proc(f)
        p.port.holdDispatch = true
        p.port.position = 20
        assertNull(p.core.begin(apply))
        assertNull(p.core.stop("수동", "stop-a"))
        assertNull(p.core.stop("다시", "stop-b"))
        p.port.stops.forEach { it.ok("STOP") }
        assertEquals(SeatOutcome.NOT_SENT, p.core.state.outcome)
        val stored = (FileSeatJournal(f).load() as SeatJournalLoad.Found).snapshot
        assertTrue(stored.stops.none { it.afterMove })
        assertNull(Proc(f).core.state.lock) // consistent after a restart
    }

    @Test
    fun `the file journal refuses to delete an inconsistent or ineligible record even when it matches, keeping its bytes`() {
        val c = SeatRequestState.COMPLETED
        val ok = SeatSnapshot(
            "job-1", SeatPurpose.RESTORE, SeatAxis.BACKREST, 20, 59, 20, SeatRequestMark("move-1", c),
            listOf(SeatRequestMark("stop-1", c, afterMove = true)), SeatOutcome.CONFIRMED,
        )
        val ineligible = listOf(
            ok.copy(move = SeatRequestMark("move-1", SeatRequestState.NOT_SENT)), // CONFIRMED with a MOVE never sent
            ok.copy(stops = listOf(SeatRequestMark("stop-1", c))), // no STOP after the MOVE
            ok.copy(move = ok.move.copy(afterMove = true)), // afterMove on the MOVE itself
            ok.copy(target = 25), // a RESTORE not to its original
            ok.copy(purpose = SeatPurpose.APPLY, start = 20, target = 60), // APPLY CONFIRMED
            ok.copy(purpose = SeatPurpose.APPLY, start = 20, target = 60, outcome = SeatOutcome.HELD, held = "화면 이탈"),
            ok.copy(outcome = SeatOutcome.UNCONFIRMED),
            ok.copy(outcome = SeatOutcome.STOP_FAILED),
            SeatSnapshot("job-1", SeatPurpose.APPLY, SeatAxis.BACKREST, 10, 20, 60, SeatRequestMark("move-1", SeatRequestState.NOT_SENT),
                outcome = SeatOutcome.NOT_SENT), // NOT_SENT, original != start
            SeatSnapshot("job-1", SeatPurpose.RESTORE, SeatAxis.BACKREST, 20, 59, 20, SeatRequestMark("move-1", SeatRequestState.NOT_SENT),
                outcome = SeatOutcome.NOT_SENT), // RESTORE NOT_SENT
        )
        for (s in ineligible) {
            val f = recordFile()
            val journal = FileSeatJournal(f)
            assertTrue(s.toString(), journal.save(s))
            val bytes = f.readBytes()
            assertFalse(s.toString(), journal.clear(s))
            assertArrayEquals(s.toString(), bytes, f.readBytes())
        }
        // The same inconsistent shapes lock a core loading them, and get a safety STOP from the restart manager.
        for (s in ineligible.take(4)) {
            assertNotNull(s.toString(), SeatMoveCore(SeatAxis.BACKREST, FakeSeatPort(mutableListOf()), FakeJournal(mutableListOf(), s), FakeScheduler(), { null }, { null }).state.lock)
            val port = FakeSeatPort(mutableListOf())
            SeatEmergencyStop(port, FakeJournal(mutableListOf(), s), FakeScheduler(), 5_000L, { "es-1" }).start()
            assertEquals(s.toString(), 1, port.stops.size)
        }
        val f = recordFile()
        assertTrue(FileSeatJournal(f).save(ok))
        assertTrue(FileSeatJournal(f).clear(ok))
    }
}
