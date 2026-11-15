package com.github.gezimos.inkos.helper

import java.io.File
import java.util.concurrent.Executor

/** 文件读写抽象：Android 侧用 AtomicFile 实现，单测用内存/临时文件实现。 */
interface SnapshotPersistence {
    fun write(file: File, bytes: ByteArray)
    fun read(file: File): ByteArray?
}

/**
 * 单写者 + 最新快照合并（conflation）+ 失败重试的纯逻辑写入器（无 Android 依赖，可 JVM 单测）。
 *
 * 契约：
 * - submit() 只覆盖待写快照，不排队；多个 submit 之间只写最新
 * - 写失败不会静默丢弃快照：exponential bounded retry，预算用尽后保留 pending，等下次 submit/flush
 * - flushSync() 在调用线程立即尝试写一次 pending（shutdown 契约由调用方保证在 writer 线程调用）
 */
class SerialSnapshotWriter<T>(
    private val executor: Executor,
    private val debounceMillis: Long,
    private val encode: (T) -> ByteArray,
    private val persistence: SnapshotPersistence,
    private val target: () -> File,
    private val schedule: (Long, Runnable) -> Unit,
    private val maxAttempts: Int = 4,
    private val retryBaseMillis: Long = 100L,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    private val lock = Any()
    private var pending: T? = null
    private var scheduled = false
    private var closed = false
    private val drainRunnable = Runnable { drain() }

    var lastAttempts: Int = 0
        private set

    fun submit(value: T) {
        synchronized(lock) {
            if (closed) return
            pending = value
            if (!scheduled) {
                scheduled = true
                schedule(debounceMillis, drainRunnable)
            }
        }
    }

    /** 立即尝试写 pending（不依赖 schedule）。 */
    fun flushSync() {
        drain()
    }

    /** 在 writer 线程上串行执行（例如 restore 先于写读取同一文件）。 */
    fun runSerial(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (_: Throwable) {
            }
        }
    }

    fun shutdown() {
        synchronized(lock) { closed = true }
        flushSync()
    }

    fun hasPending(): Boolean = synchronized(lock) { pending != null }

    private fun drain() {
        var attempts = 0
        lastAttempts = 0
        while (true) {
            val snapshot: T?
            synchronized(lock) {
                snapshot = pending
                pending = null
                scheduled = false
            }
            if (snapshot == null) return

            val written = tryWrite(snapshot)
            attempts += 1
            lastAttempts = attempts
            if (written) {
                if (hasPending()) { attempts = 0; continue }
                return
            }

            if (attempts >= maxAttempts) {
                // 不静默丢弃：保留最新待写快照，等下次 submit/flush 重试
                synchronized(lock) {
                    if (pending == null) pending = snapshot
                }
                return
            }
            synchronized(lock) { if (pending == null) pending = snapshot }
            val delay = retryBaseMillis * (1L shl (attempts - 1))
            try {
                sleeper(delay)
            } catch (_: Throwable) {
            }
        }
    }

    private fun tryWrite(snapshot: T): Boolean {
        return try {
            val file = target()
            val bytes = encode(snapshot)
            persistence.write(file, bytes)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
