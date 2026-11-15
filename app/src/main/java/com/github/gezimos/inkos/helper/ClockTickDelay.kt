package com.github.gezimos.inkos.helper

/**
 * 时钟 tick 对齐：返回距离“下一个整分钟”的毫秒数（1..60000）。
 * 纯函数（注入 now/zone），便于 JVM 单测。
 */
object ClockTickDelay {
    fun untilNextMinute(nowMillis: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long {
        val calendar = java.util.Calendar.getInstance(zone).apply { timeInMillis = nowMillis }
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        calendar.add(java.util.Calendar.MINUTE, 1)
        return (calendar.timeInMillis - nowMillis).coerceIn(1L, 60_000L)
    }
}
