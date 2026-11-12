package app.commacabin.probe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileRecoveryStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val record = RecoveryRecord(
        jobId = "job-1",
        area = "ROW_1_LEFT",
        originalValue = 80,
        targetValue = 60,
        stage = Stage.APPLY_REQUESTED,
        responseReceived = false,
        lastReadValue = 80,
    )

    @Test
    fun `saves and loads every field`() {
        val file = File(temp.root, FileRecoveryStore.FILE_NAME)
        val store = FileRecoveryStore(file)

        assertEquals(LoadResult.Empty, store.load())
        assertTrue(store.save(record))
        assertEquals(LoadResult.Found(record), store.load())
    }

    @Test
    fun `replacing a record leaves no temp file`() {
        val file = File(temp.root, FileRecoveryStore.FILE_NAME)
        val store = FileRecoveryStore(file)
        val next = record.copy(stage = Stage.APPLY_CONFIRMED, responseReceived = true, lastCallbackValue = 60)

        assertTrue(store.save(record))
        assertTrue(store.save(next))
        assertEquals(LoadResult.Found(next), store.load())
        assertEquals(listOf(FileRecoveryStore.FILE_NAME), temp.root.list()!!.toList())
    }

    @Test
    fun `unsupported version and corrupt json are unreadable and kept on disk`() {
        val file = File(temp.root, FileRecoveryStore.FILE_NAME)
        val store = FileRecoveryStore(file)

        file.writeText(RecoveryCodec.encode(record).replace("\"formatVersion\": 1", "\"formatVersion\": 2"))
        assertTrue(store.load() is LoadResult.Unreadable)
        assertTrue(file.exists())

        file.writeText("{\"formatVersion\": 1, \"jobId\":")
        assertTrue(store.load() is LoadResult.Unreadable)
        assertTrue(file.exists())

        file.writeText(RecoveryCodec.encode(record).replace("APPLY_REQUESTED", "SOMETHING_ELSE"))
        assertTrue(store.load() is LoadResult.Unreadable)
        assertTrue(file.exists())
    }

    @Test
    fun `decoder rejects blank ids, unknown names, wrong types and impossible values without deleting`() {
        val file = File(temp.root, FileRecoveryStore.FILE_NAME)
        val store = FileRecoveryStore(file)
        val good = RecoveryCodec.encode(record)
        val damaged = listOf(
            good.replace("\"jobId\": \"job-1\"", "\"jobId\": \"  \""),
            good.replace("\"ROW_1_LEFT\"", "\"ROW_9_LEFT\""),
            good.replace("\"ROW_1_LEFT\"", "\"ROW_1_LEFT+ROW_1_LEFT\""),
            good.replace("\"originalValue\": 80", "\"originalValue\": \"80\""),
            good.replace("\"originalValue\": 80", "\"originalValue\": 80.5"),
            good.replace("\"targetValue\": 60", "\"targetValue\": -1"),
            good.replace("\"targetValue\": 60", "\"targetValue\": 90"),
            good.replace("\"responseReceived\": false", "\"responseReceived\": \"false\""),
            good.replace("\"formatVersion\": 1", "\"formatVersion\": \"1\""),
            good.replace("\"lastReadValue\": 80", "\"lastReadValue\": \"80\""),
            good.replace("\"stage\": \"APPLY_REQUESTED\",", ""),
        )
        damaged.forEachIndexed { i, text ->
            assertTrue("case $i must differ from the valid record", text != good)
            file.writeText(text)
            assertTrue("case $i", store.load() is LoadResult.Unreadable)
            assertTrue(file.exists())
        }
    }

    @Test
    fun `save into a missing directory fails`() {
        val store = FileRecoveryStore(File(File(temp.root, "missing"), FileRecoveryStore.FILE_NAME))
        assertFalse(store.save(record))
    }

    @Test
    fun `delete is true when nothing is stored`() {
        val file = File(temp.root, FileRecoveryStore.FILE_NAME)
        val store = FileRecoveryStore(file)
        assertTrue(store.delete())
        assertTrue(store.save(record))
        assertTrue(store.delete())
        assertFalse(file.exists())
    }
}
