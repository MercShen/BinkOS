package com.github.gezimos.inkos.data.repository

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 全量刷新请求队列（纯逻辑，可单测）：
 * - 按 includeHiddenApps 分成 visible / hidden 两条槽，各自 latest-wins（避免 hidden 刷新把 visible 请求合并掉）
 * - 生成单调 generation；发布前比较，旧结果不覆盖新结果
 * - worker 优先取 visible，再取 hidden，直到两条槽都空
 */
internal class LatestRefreshQueue {

    data class Job(
        val generation: Long,
        val includeHiddenApps: Boolean,
        val flag: String?
    )

    private val generation = AtomicLong(0L)
    private val pendingVisible = AtomicReference<Job?>(null)
    private val pendingHidden = AtomicReference<Job?>(null)

    fun request(includeHiddenApps: Boolean, flag: String?): Long {
        val next = generation.incrementAndGet()
        val job = Job(next, includeHiddenApps, flag)
        if (includeHiddenApps) pendingHidden.set(job) else pendingVisible.set(job)
        return next
    }

    fun take(): Job? = pendingVisible.getAndSet(null) ?: pendingHidden.getAndSet(null)

    fun hasPending(): Boolean = pendingVisible.get() != null || pendingHidden.get() != null

    fun lastRequestedGeneration(): Long = generation.get()

    companion object {
        fun shouldPublish(jobGeneration: Long, publishedGeneration: Long): Boolean =
            jobGeneration > publishedGeneration
    }
}
