package com.github.gezimos.inkos.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

class SerialSnapshotWriterTest {

    private class ManualScheduler {
        val tasks = mutableListOf<Runnable>()
        fun schedule(delay: Long, runnable: Runnable) { tasks.add(runnable) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeAt(0).run() }
    }

    private class MemoryPersistence : SnapshotPersistence {
        var failCount = 0
        var attempts = 0
        val writes = mutableListOf<String>()
        override fun write(file: File, bytes: ByteArray) {
            attempts++
            if (failCount > 0) {
                failCount--
                throw IllegalStateException("mock write failure")
            }
            writes.add(String(bytes, Charsets.UTF_8))
        }
        override fun read(file: File): ByteArray? = null
    }

    private val direct = Executor { it.run() }

    private fun writer(scheduler: ManualScheduler, persistence: MemoryPersistence, maxAttempts: Int = 4) =
        SerialSnapshotWriter<String>(
            executor = direct,
            debounceMillis = 0L,
            encode = { it.toByteArray(Charsets.UTF_8) },
            persistence = persistence,
            target = { File("unused") },
            schedule = scheduler::schedule,
            maxAttempts = maxAttempts,
            retryBaseMillis = 0L,
            sleeper = {}
        )

    @Test
    fun coalescesToLatestBeforeDrain() {
        val scheduler = ManualScheduler()
        val persistence = MemoryPersistence()
        val w = writer(scheduler, persistence)
        w.submit("first")
        w.submit("second")
        assertEquals(1, scheduler.tasks.size)
        scheduler.runAll()
        assertEquals(listOf("second"), persistence.writes)
    }

    @Test
    fun retriesThenSucceeds() {
        val scheduler = ManualScheduler()
        val persistence = MemoryPersistence().apply { failCount = 2 }
        val w = writer(scheduler, persistence, maxAttempts = 5)
        w.submit("payload")
        scheduler.runAll()
        assertEquals(listOf("payload"), persistence.writes)
        assertEquals(3, w.lastAttempts)
    }

    @Test
    fun failureDoesNotSilentlyDiscardPending() {
        val scheduler = ManualScheduler()
        val persistence = MemoryPersistence().apply { failCount = 100 }
        val w = writer(scheduler, persistence, maxAttempts = 2)
        w.submit("keep-me")
        scheduler.runAll()
        assertTrue(w.hasPending())
        persistence.failCount = 0
        w.flushSync()
        assertEquals(listOf("keep-me"), persistence.writes)
        assertFalse(w.hasPending())
    }

    @Test
    fun flushSyncWritesWithoutScheduledTask() {
        val scheduler = ManualScheduler()
        val persistence = MemoryPersistence()
        val w = writer(scheduler, persistence)
        w.submit("now")
        w.flushSync()
        assertEquals(listOf("now"), persistence.writes)
    }
}
