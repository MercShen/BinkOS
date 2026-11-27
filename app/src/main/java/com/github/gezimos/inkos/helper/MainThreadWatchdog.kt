package com.github.gezimos.inkos.helper

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 主线程看门狗：抓「主线程自转」的现场栈。
 *
 * 为什么要在进程内自己取栈：B651 是 user 版，`kill -3` 不出栈、
 * `debuggerd -b` 要 root、`/data/anr/` 下的文件是 system 私有。2026-11-28 那次
 * 「进设置无响应」我们只测到主线程空闲时烧满一个核（6 秒 516 ticks），
 * 却拿不到任何栈——只能靠这个看门狗在下次发生时自己把栈打出来。
 *
 * 取栈用 [Looper.getMainThread] 的 `stackTrace`：这是公开 API（API 23+，
 * 本包 minSdk 26），不需要 hidden API、不需要 root。
 *
 * 只做诊断，不改任何行为；取栈失败也只记日志，绝不抛出去。
 */
object MainThreadWatchdog {

    private const val TAG = "MainThreadWatchdog"

    /** 心跳间隔：主线程每 200ms 跑一次空消息。 */
    private const val HEARTBEAT_MS = 200L

    /** 心跳静默多久算「卡住」。2 秒已经足够排除偶发抖动，又远早于 5 秒的输入 ANR。 */
    private const val STALL_LIMIT_MS = 2000L

    /** 同一次卡顿只报一次，避免刷屏。 */
    private const val REPORT_COOLDOWN_MS = 15_000L

    /** 栈最多打这么多帧：巨型 Compose 方法能把栈深拉到几百层。 */
    private const val MAX_FRAMES = 30

    @Volatile private var lastBeatAt = 0L
    @Volatile private var lastReportAt = 0L
    @Volatile private var running = false

    /**
     * 主线程的 Thread 对象。
     * 用 `Thread.currentThread()` 在 start()（必须在主线程调用）里抓下来 ——
     * `Looper.getMainThread()` 是 hidden API，普通应用拿不到。
     */
    @Volatile private var mainThread: Thread? = null

    /** 卡顿发生在哪个界面，靠调用方在主线程更新这个提示串。 */
    @Volatile var screenHint: String = "unknown"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val beat = object : Runnable {
        override fun run() {
            lastBeatAt = SystemClock.elapsedRealtime()
            mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /** 必须在主线程调用（onCreate 里就是主线程）。 */
    fun start() {
        if (running) return
        mainThread = Thread.currentThread()
        running = true
        lastBeatAt = SystemClock.elapsedRealtime()
        mainHandler.post(beat)
        val watcher = Thread({
            // 注意：这里是 Runnable 转换的 lambda，不能用 return；用标志位退出
            var alive = true
            while (running && alive) {
                try {
                    Thread.sleep(HEARTBEAT_MS)
                } catch (_: InterruptedException) {
                    alive = false
                }
                if (!running || !alive) continue
                val now = SystemClock.elapsedRealtime()
                val silentFor = now - lastBeatAt
                if (silentFor >= STALL_LIMIT_MS && now - lastReportAt >= REPORT_COOLDOWN_MS) {
                    lastReportAt = now
                    report(silentFor)
                }
            }
        }, "MainThreadWatchdog")
        watcher.isDaemon = true
        watcher.priority = Thread.MIN_PRIORITY
        watcher.start()
        Log.i(TAG, "watchdog started (stall limit ${STALL_LIMIT_MS}ms, heartbeat ${HEARTBEAT_MS}ms)")
    }

    private fun report(silentFor: Long) {
        val sb = StringBuilder()
        sb.appendLine("MAIN THREAD STALLED ${silentFor}ms (screen=$screenHint)")
        val stack = try {
            mainThread?.stackTrace
        } catch (t: Throwable) {
            sb.appendLine("  stack unavailable: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (stack != null) {
            if (stack.isEmpty()) {
                sb.appendLine("  empty stack")
            } else {
                stack.take(MAX_FRAMES).forEach { frame -> sb.appendLine("    at $frame") }
                if (stack.size > MAX_FRAMES) {
                    sb.appendLine("    ... ${stack.size - MAX_FRAMES} more frames")
                }
            }
        }
        Log.w(TAG, sb.toString())
    }
}
