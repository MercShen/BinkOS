package com.github.gezimos.inkos.services

/**
 * 通知会话历史的纯函数：有界裁剪 + “已有更新版本时合并”的规则。
 * 只做数据运算，不依赖 Android，便于 JVM 单测。
 */
internal object NotificationHistoryLimits {

    data class Row(val id: String, val timestamp: Long)

    const val MAX_PER_PACKAGE: Int = 500 // 安全上限：保留用户既有历史，仅防无界增长

    /** 只对新增做限制：cap 至少保留已加载的历史条数（用户历史不被擅自裁剪）。 */
    fun effectiveCap(existingCount: Int, floor: Int = MAX_PER_PACKAGE): Int =
        maxOf(floor, existingCount)

    /** 按 timestamp 保留最新 [max] 条；返回按时间倒序的列表。 */
    fun bounded(rows: List<Row>, max: Int = MAX_PER_PACKAGE): List<Row> {
        if (max <= 0) return emptyList()
        if (rows.size <= max) return rows.sortedByDescending { it.timestamp }
        return rows.sortedByDescending { it.timestamp }.take(max)
    }

    /**
     * 启动恢复与“正在到来的新通知”合并：
     * current（运行期内存）永远优先于 restored（磁盘旧快照），同 id 取 current；
     * 仅补充 current 里没有的旧条目，最后按 max 有界裁剪。
     */
    fun mergePreferCurrent(
        current: Map<String, List<Row>>,
        restored: Map<String, List<Row>>,
        max: Int = MAX_PER_PACKAGE,
        tombstones: Set<String> = emptySet()
    ): Map<String, List<Row>> {
        if (restored.isEmpty()) return current.mapValues { bounded(it.value, max) }
        val result = LinkedHashMap<String, List<Row>>()
        val packageNames = LinkedHashSet<String>().apply {
            addAll(current.keys)
            addAll(restored.keys)
        }
        for (pkg in packageNames) {
            val merged = LinkedHashMap<String, Row>()
            restored[pkg]?.forEach { row ->
                // loading 期间已被删除的 id 不允许复活
                if (tombstones.contains("$pkg|${row.id}")) return@forEach
                merged[row.id] = row
            }
            current[pkg]?.forEach { merged[it.id] = it } // current wins
            if (merged.isNotEmpty()) result[pkg] = bounded(merged.values.toList(), max)
        }
        return result
    }
}
