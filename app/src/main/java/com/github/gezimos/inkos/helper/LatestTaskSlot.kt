package com.github.gezimos.inkos.helper

/** Pure generation guard: queued work cannot resurrect after replacement or cancellation. */
class LatestTaskSlot<T> {
    private var generation = 0L
    private var owner: T? = null
    @Synchronized fun replace(value: T): Long { owner = value; return ++generation }
    @Synchronized fun owns(value: T, token: Long): Boolean = owner === value && generation == token
    @Synchronized fun cancel(value: T) { if (owner === value) { owner = null; generation++ } }
    @Synchronized fun cancelAll() { owner = null; generation++ }
}
