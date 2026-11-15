package com.github.gezimos.inkos.helper

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One loader at a time; invalidation during load never republishes the old generation. */
class SingleFlightSnapshot<T>(private val ttlMillis: Long, private val now: () -> Long) {
    private val mutex = Mutex()
    private val lock = Any()
    private var generation = 0L
    private var value: T? = null
    private var loadedAt = 0L

    fun invalidate() = synchronized(lock) { generation++; value = null }
    fun peek(): T? = synchronized(lock) { value }

    suspend fun get(load: suspend () -> T): T = mutex.withLock {
        while (true) {
            val token = synchronized(lock) {
                val cached = value
                if (cached != null && now() - loadedAt in 0 until ttlMillis) return@withLock cached
                generation
            }
            val result = load()
            synchronized(lock) {
                if (token == generation) {
                    value = result
                    loadedAt = now()
                    return@withLock result
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}
