package com.github.gezimos.inkos.helper

import android.os.Handler
import android.os.HandlerThread
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Android 侧 AtomicFile 实现：写用 startWrite/finishWrite/failWrite，读用 openRead。 */
class AtomicFilePersistence(private val target: () -> File) : SnapshotPersistence {
    override fun write(file: File, bytes: ByteArray) {
        val atomicFile = AtomicFile(file)
        var out: FileOutputStream? = null
        try {
            out = atomicFile.startWrite()
            out.write(bytes)
            out.flush()
            atomicFile.finishWrite(out)
        } catch (t: Throwable) {
            try {
                if (out != null) atomicFile.failWrite(out)
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    override fun read(file: File): ByteArray? {
        return try { AtomicFile(file).openRead().use { it.readBytes() } }
        catch (_: java.io.FileNotFoundException) { null }
    }
}

/**
 * 单写线程 + 最新快照合并 + AtomicFile 落盘。
 * - 所有读写（包括 restore 读取）都排到同一个 writer 线程，避免读时被写截断
 * - submit()/flushAsync() 不阻塞调用线程；flushBlocking() 仅用于显式 shutdown 契约
 */
class CoalescingFileWriter<T>(
    threadName: String,
    private val debounceMillis: Long,
    private val target: () -> File,
    private val encode: (T) -> ByteArray
) {
    private val thread = HandlerThread(threadName).also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { command -> handler.post(command) }
    private val persistence = AtomicFilePersistence(target)

    private val core = SerialSnapshotWriter<T>(
        executor = executor,
        debounceMillis = debounceMillis,
        encode = encode,
        persistence = persistence,
        target = target,
        schedule = { delay, runnable -> handler.postDelayed(runnable, delay) }
    )

    fun submit(snapshot: T) = core.submit(snapshot)

    /** 在同一 writer 线程读取文件（restore 用），读完后再由调用方回主线程合并。 */
    fun readOnWriterThread(onRead: (ByteArray?) -> Unit) {
        core.runSerial {
            val bytes = try {
                persistence.read(target())
            } catch (_: Throwable) {
                null
            }
            handler.post { onRead(bytes) }
        }
    }

    /** 非阻塞 flush：排到同一个 writer 线程，不再每次 new Thread。 */
    fun flushAsync() {
        handler.post { core.flushSync() }
    }

    fun flushBlocking(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                core.flushSync()
            } finally {
                latch.countDown()
            }
        }
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun shutdown(timeoutMs: Long = 1000L) {
        flushBlocking(timeoutMs)
        core.shutdown()
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "CoalescingFileWriter"
    }
}
