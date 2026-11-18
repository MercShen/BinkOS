package com.github.gezimos.inkos.services

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.os.Handler
import android.os.Looper
import com.github.gezimos.inkos.helper.CoalescingFileWriter
import java.io.File

class NotificationManager private constructor(private val context: Context) {

    data class NotificationInfo(
        val count: Int,
        val title: String?,
        val text: String?,
        val category: String?,
        val timestamp: Long
    )

    data class ConversationNotification(
        val conversationId: String,
        val conversationTitle: String?,
        val sender: String?,
        val message: String?,
        val timestamp: Long,
        val category: String? = null,
        val notificationKey: String? = null
    )

    private val summaryCache = mutableMapOf<String, Boolean>()


    private val notificationInfo = mutableMapOf<String, NotificationInfo>()
    private val _notificationInfoState = MutableStateFlow<Map<String, NotificationInfo>>(emptyMap())
    val notificationInfoState: StateFlow<Map<String, NotificationInfo>> = _notificationInfoState.asStateFlow()

    private var lastPostedNotificationInfo: Map<String, NotificationInfo>? = null

    private val conversationNotifications =
        mutableMapOf<String, MutableMap<String, ConversationNotification>>()
    private val _conversationNotificationsState =
        MutableStateFlow<Map<String, List<ConversationNotification>>>(emptyMap())
    val conversationNotificationsState: StateFlow<Map<String, List<ConversationNotification>>> =
        _conversationNotificationsState.asStateFlow()


    private val NOTIF_SAVE_FILE = "inkos_notifications.json"

    /** 历史 map 只在主线程变更；所有读写都走 historyLock，后台只做解析与快照。 */
    private val historyLock = Any()
    @Volatile private var historyGeneration = 0L

    private val historyWriter = CoalescingFileWriter<Map<String, List<ConversationNotification>>>(
        threadName = "inkos-notif-history",
        debounceMillis = 500L,
        target = { File(context.filesDir, NOTIF_SAVE_FILE) },
        encode = { snapshot -> Gson().toJson(snapshot).toByteArray(Charsets.UTF_8) }
    )

    /** restore 期间的写抑制与 tombstone：避免旧磁盘快照覆盖/复活加载期间的新增与删除。 */
    private val restoreLock = Any()
    @Volatile private var restoreInFlight = false
    @Volatile private var restoreCompleted = false
    private var pendingSaveAfterRestore = false
    private val removedDuringRestore = mutableSetOf<String>()

    companion object {
        private const val TAG = "NotificationManager"
        @Volatile
        @Suppress("StaticFieldLeak") // Using applicationContext which is safe
        private var INSTANCE: NotificationManager? = null
        fun getInstance(context: Context): NotificationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NotificationManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun updateBadgeNotification(packageName: String, info: NotificationInfo?) {
        if (info == null) {
            notificationInfo.remove(packageName)
        } else {
            notificationInfo[packageName] = info
        }
        // Removed debug log
        val prefs = com.github.gezimos.inkos.data.Prefs(context)
        val allowed = prefs.allowedBadgeNotificationApps
        val filtered = if (allowed.isEmpty()) {
            HashMap(notificationInfo)
        } else {
            // Create a new filtered map
            HashMap(notificationInfo.filter { (pkg, _) -> pkg in allowed })
        }
        if (lastPostedNotificationInfo != filtered) {
            lastPostedNotificationInfo = HashMap(filtered)
            _notificationInfoState.value = filtered
        }
    }

    fun clearMediaNotification(packageName: String) {
        val currentInfo = notificationInfo[packageName]
        if (currentInfo?.category == android.app.Notification.CATEGORY_TRANSPORT) {
            updateBadgeNotification(packageName, null)
        }
    }

    /**
     * Refreshes badge notification state based on current allowlist.
     * Call this when the badge allowlist changes to immediately update the UI.
     */
    fun refreshBadgeNotificationState() {
        val prefs = com.github.gezimos.inkos.data.Prefs(context)
        val allowed = prefs.allowedBadgeNotificationApps
        val filtered = if (allowed.isEmpty()) {
            HashMap(notificationInfo)
        } else {
            HashMap(notificationInfo.filter { (pkg, _) -> pkg in allowed })
        }
        if (lastPostedNotificationInfo != filtered) {
            lastPostedNotificationInfo = HashMap(filtered)
            _notificationInfoState.value = filtered
        }
    }

    /**
     * Refreshes conversation notification state based on current allowlist.
     * Call this when the conversation allowlist changes to immediately update the UI.
     */
    fun refreshConversationNotificationState() {
        _conversationNotificationsState.value = getConversationNotifications()
    }

    fun getConversationNotifications(): Map<String, List<ConversationNotification>> {
        // Only filter by allowlist
        val snapshot = synchronized(historyLock) {
            conversationNotifications.mapValues { entry -> entry.value.values.toList() }
        }
        val prefs = com.github.gezimos.inkos.data.Prefs(context)
        val allowed = prefs.allowedNotificationApps
        return snapshot
            .filter { (pkg, _) -> allowed.isEmpty() || pkg in allowed }
            .mapValues { entry ->
                entry.value.sortedByDescending { n -> n.timestamp }
            }
    }

    fun updateConversationNotification(
        packageName: String,
        conversation: ConversationNotification
    ) {
        if (!restoreCompleted && !restoreInFlight) restoreConversationNotifications()
        val snapshot = synchronized(historyLock) {
            val appMap = conversationNotifications.getOrPut(packageName) { mutableMapOf() }
            val existingCount = appMap.size
            appMap[conversation.conversationId] = conversation
            // Restore preserves all existing rows; new insertions evict the oldest row only above max(500, existingCount).
            val cap = NotificationHistoryLimits.effectiveCap(existingCount)
            if (appMap.size > cap) {
                val rows = appMap.values.map {
                    NotificationHistoryLimits.Row(it.conversationId, it.timestamp)
                }
                val keep = NotificationHistoryLimits.bounded(rows, cap).map { it.id }.toSet()
                appMap.keys.retainAll(keep)
            }
            historyGeneration += 1
            conversationNotifications.mapValues { it.value.values.toList() }
        }
        saveConversationNotifications()
        _conversationNotificationsState.value = getConversationNotifications()
    }

    fun removeConversationNotification(packageName: String, conversationId: String) {
        if (!restoreCompleted && !restoreInFlight) restoreConversationNotifications()
        var snapshot: Map<String, List<ConversationNotification>>? = null
        synchronized(historyLock) {
            if (restoreInFlight) {
                removedDuringRestore.add("$packageName|$conversationId")
            }
            val appMap = conversationNotifications[packageName]
            if (appMap != null) {
                appMap.remove(conversationId)
                if (appMap.isEmpty()) {
                    conversationNotifications.remove(packageName)
                }
                historyGeneration += 1
                snapshot = conversationNotifications.mapValues { it.value.values.toList() }
            }
        }
        val toWrite = snapshot ?: return
        saveConversationNotifications()
        _conversationNotificationsState.value = getConversationNotifications()
    }

    /**
     * Opens a notification conversation and optionally removes it from the local list.
     * This consolidates the common pattern of opening a notification.
     *
     * @param packageName The package name of the app that owns the notification
     * @param notificationKey The notification key to open
     * @param conversationId The conversation ID to remove from local list (if removeAfterOpen is true)
     * @param removeAfterOpen Whether to remove the notification from local list after opening
     * @return true if the notification was opened successfully, false otherwise
     */
    fun openNotification(
        packageName: String,
        notificationKey: String?,
        conversationId: String? = null,
        removeAfterOpen: Boolean = true
    ): Boolean {
        return try {
            val opened = NotificationService.sendConversationIntent(
                context, packageName, notificationKey
            )
            // Fall back to app launch if deep link failed
            if (!opened) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                }
            }
            if (removeAfterOpen && conversationId != null) {
                removeConversationNotification(packageName, conversationId)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 请求落盘：只提交快照，序列化 + AtomicFile 写入在单写线程合并执行（主线程不再做 Gson/文件 IO）。
     */
    fun saveConversationNotifications() {
        if (!restoreCompleted && !restoreInFlight) restoreConversationNotifications()
        synchronized(restoreLock) {
            if (restoreInFlight) {
                pendingSaveAfterRestore = true
                return
            }
            val snapshot = synchronized(historyLock) {
                conversationNotifications.mapValues { it.value.values.toList() }
            }
            historyWriter.submit(snapshot)
        }
    }

    /** shutdown 契约：等待已提交快照落盘（有超时），由 NotificationService.onDestroy 调用。 */
    fun flushPendingWrites(timeoutMs: Long = 1000L): Boolean = historyWriter.flushBlocking(timeoutMs)

    /** 异步 flush：排到同一个 writer 线程（不再每次 new Thread），onDestroy 不 join 主线程。 */
    fun flushAsync() {
        try {
            historyWriter.flushAsync()
        } catch (_: Throwable) {
        }
    }

    /**
     * 启动恢复：磁盘解析放后台线程，合并回内存放在主线程。
     * 若解析期间已有新通知进来（historyGeneration 变化），以内存为准合并（current wins），
     * 不用旧磁盘快照覆盖更新的运行期数据。
     */
    /**
     * 启动恢复：读取排在同一个 writer 线程（AtomicFile.openRead），解析后回主线程合并。
     * - 同一时刻只允许一次 restore（inflight/completed 守卫）
     * - 加载期间的新增保留在内存；删除记 tombstone，合并时不得复活
     * - 加载期间的 save 被抑制，合并完成后再写合并后的快照
     */
    fun restoreConversationNotifications() {
        synchronized(restoreLock) {
            if (restoreInFlight || restoreCompleted) return
            restoreInFlight = true
        }
        synchronized(historyLock) { removedDuringRestore.clear() }
        val generationAtStart = historyGeneration

        historyWriter.readOnWriterThread { bytes ->
            val restored: Map<String, List<ConversationNotification>> = if (bytes == null) {
                emptyMap()
            } else {
                try {
                    val json = String(bytes, Charsets.UTF_8)
                    val type = object : TypeToken<Map<String, List<ConversationNotification>>>() {}.type
                    Gson().fromJson<Map<String, List<ConversationNotification>>>(json, type) ?: emptyMap()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore conversation notifications", e)
                    emptyMap()
                }
            }

            Handler(Looper.getMainLooper()).post {
            try {
            val tombstones = synchronized(historyLock) { removedDuringRestore.toSet() }
            val changedWhileLoading = historyGeneration != generationAtStart
            synchronized(historyLock) {
                val restoredFlat = HashMap<String, MutableMap<String, ConversationNotification>>()
                val restoredRows = HashMap<String, List<NotificationHistoryLimits.Row>>()
                restored.forEach { (pkg, list) ->
                    val byId = list.filter { it.conversationId.isNotBlank() }
                        .associateBy { it.conversationId }
                        .toMutableMap()
                    if (byId.isNotEmpty()) {
                        restoredFlat[pkg] = byId
                        restoredRows[pkg] = byId.values.map {
                            NotificationHistoryLimits.Row(it.conversationId, it.timestamp)
                        }
                    }
                }
                if (changedWhileLoading || tombstones.isNotEmpty()) {
                    val currentRows = conversationNotifications.mapValues { entry ->
                        entry.value.values.map {
                            NotificationHistoryLimits.Row(it.conversationId, it.timestamp)
                        }
                    }
                    val mergedRows = NotificationHistoryLimits.mergePreferCurrent(
                        currentRows, restoredRows, Int.MAX_VALUE, tombstones
                    )
                    val next = LinkedHashMap<String, MutableMap<String, ConversationNotification>>()
                    mergedRows.forEach { (pkg, rows) ->
                        val target = LinkedHashMap<String, ConversationNotification>()
                        rows.forEach { row ->
                            val value = conversationNotifications[pkg]?.get(row.id)
                                ?: restoredFlat[pkg]?.get(row.id)
                            if (value != null) target[row.id] = value
                        }
                        if (target.isNotEmpty()) next[pkg] = target
                    }
                    conversationNotifications.clear()
                    conversationNotifications.putAll(next)
                } else {
                    conversationNotifications.clear()
                    restored.forEach { (pkg, list) ->
                        val valid = list.filter {
                            it.conversationId.isNotBlank() &&
                                !tombstones.contains("$pkg|${it.conversationId}")
                        }
                        if (valid.isNotEmpty()) {
                            // 保留磁盘既有的全部历史：恢复阶段不裁剪
                            conversationNotifications[pkg] =
                                valid.associateBy { it.conversationId }.toMutableMap()
                        }
                    }
                }
            }
            _conversationNotificationsState.value = getConversationNotifications()
            } catch (e: Exception) {
                Log.e(TAG, "Cannot merge restored notification history", e)
            } finally {
                val needSave = synchronized(restoreLock) {
                    restoreInFlight = false
                    restoreCompleted = true
                    pendingSaveAfterRestore.also { pendingSaveAfterRestore = false }
                }
                synchronized(historyLock) { removedDuringRestore.clear() }
                if (needSave) saveConversationNotifications()
            }
            }
        }
    }

    private fun extractNotificationContent(
        sbn: android.service.notification.StatusBarNotification,
        prefs: com.github.gezimos.inkos.data.Prefs
    ): Triple<String?, String?, String?> {
        val extras = sbn.notification.extras
        val showSender = prefs.showNotificationSenderName
        val showGroup = prefs.showNotificationGroupName
        val showMessage = prefs.showNotificationMessage

        // Helper to normalize text
        fun normalizeText(text: String?): String? {
            return text?.trim()?.replace("\n", " ")?.replace("\r", " ")?.replace(Regex("\\s+"), " ")
        }

        val groupRaw = extras?.getCharSequence("android.conversationTitle")?.toString()
            ?: extras?.getString("android.conversationTitle")
        val group = if (showGroup) {
            normalizeText(groupRaw)
        } else null
        
        // Extract sender/title - try multiple fields
        val titleRaw = extras?.getCharSequence("android.title")?.toString()
            ?: extras?.getString("android.title")
        
        val sender: String? = if (showSender) {
            val senderText = if (!titleRaw.isNullOrBlank() && titleRaw.contains(": ")) {
                titleRaw.split(": ", limit = 2).firstOrNull()?.trim()
            } else {
                titleRaw
            } ?: extras?.getCharSequence("android.subText")?.toString()
                ?: extras?.getString("android.subText")
            
            val normalizedSender = normalizeText(senderText)
            if (!normalizedSender.isNullOrBlank() && 
                !groupRaw.isNullOrBlank() && 
                normalizedSender.trim().equals(groupRaw.trim(), ignoreCase = true)) {
                null // Sender is actually the group name, which is wrong
            } else {
                normalizedSender
            }
        } else null
        
        val text = if (showMessage) {
            val rawText = when {
                extras?.getCharSequence("android.bigText") != null -> 
                    extras.getCharSequence("android.bigText")?.toString()
                
                extras?.getCharSequence("android.text") != null -> 
                    extras.getCharSequence("android.text")?.toString()
                
                extras?.getCharSequenceArray("android.textLines") != null -> {
                    val lines = extras.getCharSequenceArray("android.textLines")
                    lines?.lastOrNull()?.toString()
                }
                
                // Try additional fields that some apps use
                extras?.getCharSequence("android.summaryText") != null -> 
                    extras.getCharSequence("android.summaryText")?.toString()
                
                extras?.getCharSequence("android.infoText") != null -> 
                    extras.getCharSequence("android.infoText")?.toString()
                
                sbn.notification.tickerText != null -> 
                    sbn.notification.tickerText?.toString()
                
                else -> null
            }
            val normalizedText = normalizeText(rawText)?.take(30)
            if (!normalizedText.isNullOrBlank() && 
                !groupRaw.isNullOrBlank() && 
                normalizedText.trim().equals(groupRaw.trim(), ignoreCase = true)) {
                null // Text is actually the group name, which is wrong
            } else {
                normalizedText
            }
        } else null

        return Triple(sender, group, text)
    }

    /**
     * Builds notification title and text from extracted content, with intelligent fallback.
     * Provides category-specific fallbacks instead of generic "Notification received".
     */
    private fun buildNotificationTitleAndText(
        sender: String?,
        group: String?,
        text: String?,
        packageName: String,
        category: String? = null
    ): Pair<String?, String?> {
        var notifTitle = buildString {
            if (!sender.isNullOrBlank()) append(sender)
            if (!group.isNullOrBlank()) {
                if (isNotEmpty()) append(": ")
                append(group)
            }
        }.ifBlank { null }
        
        var notifText = text
        
        if ((notifTitle == null || notifTitle.isBlank()) && (notifText == null || notifText.isBlank())) {
            val pm = context.packageManager
            val appLabel = try {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (_: Exception) {
                packageName
            }
            notifTitle = appLabel
            
            notifText = when (category) {
                android.app.Notification.CATEGORY_MESSAGE -> "New message"
                android.app.Notification.CATEGORY_EMAIL -> "New email"
                android.app.Notification.CATEGORY_CALL -> "Missed call"
                android.app.Notification.CATEGORY_ALARM -> "Alarm"
                android.app.Notification.CATEGORY_REMINDER -> "Reminder"
                android.app.Notification.CATEGORY_EVENT -> "Event"
                android.app.Notification.CATEGORY_PROMO -> "Promotion"
                android.app.Notification.CATEGORY_TRANSPORT -> "Media playing"
                android.app.Notification.CATEGORY_SYSTEM -> "System notification"
                android.app.Notification.CATEGORY_SERVICE -> "Service notification"
                android.app.Notification.CATEGORY_ERROR -> "Error"
                android.app.Notification.CATEGORY_PROGRESS -> "Progress update"
                android.app.Notification.CATEGORY_SOCIAL -> "Social update"
                android.app.Notification.CATEGORY_STATUS -> "Status update"
                android.app.Notification.CATEGORY_RECOMMENDATION -> "Recommendation"
                else -> null // Don't show generic fallback - just show app name
            }
        }
        
        return Pair(notifTitle, notifText)
    }

    fun buildNotificationInfo(
        sbn: android.service.notification.StatusBarNotification,
        prefs: com.github.gezimos.inkos.data.Prefs,
        activeNotifications: Array<android.service.notification.StatusBarNotification>
    ): NotificationInfo {
        // Get all notifications for this package
        val samePackage = activeNotifications.filter { it.packageName == sbn.packageName }
        
        val nonSummaryNotifications = samePackage.filter { !isNotificationSummary(it) }
        
        val notificationToShow = when {
            nonSummaryNotifications.isNotEmpty() -> nonSummaryNotifications.maxByOrNull { it.postTime }
            samePackage.isNotEmpty() -> samePackage.maxByOrNull { it.postTime }
            else -> null
        } ?: sbn

        val (sender, group, text) = extractNotificationContent(notificationToShow, prefs)
        val (notifTitle, rawNotifText) = buildNotificationTitleAndText(
            sender, group, text, sbn.packageName, notificationToShow.notification.category
        )
        val notifText = com.github.gezimos.inkos.helper.CallStatusText.localize(
            context, notificationToShow, rawNotifText
        )

        return NotificationInfo(
            count = samePackage.size.coerceAtLeast(1),
            title = notifTitle,
            text = notifText,
            category = notificationToShow.notification.category,
            timestamp = notificationToShow.postTime
        )
    }

    /**
     * Fixed: Builds notification info only from remaining active notifications for a package.
     * Used when a notification is removed to check if there are other notifications to display.
     * Returns null if no active notifications exist, ensuring badges are properly cleared.
     */

    fun buildNotificationInfoForRemaining(
        packageName: String,
        prefs: com.github.gezimos.inkos.data.Prefs,
        activeNotifications: Array<android.service.notification.StatusBarNotification>
    ): NotificationInfo? {
        // Find active notifications for this package only
        val samePackage = activeNotifications.filter { it.packageName == packageName }

        if (samePackage.isEmpty()) {
            return null
        }

        val nonSummaryNotifications = samePackage.filter { !isNotificationSummary(it) }
        
        val notificationToShow = when {
            nonSummaryNotifications.isNotEmpty() -> nonSummaryNotifications.maxByOrNull { it.postTime }
            samePackage.isNotEmpty() -> samePackage.maxByOrNull { it.postTime }
            else -> null
        } ?: return null
        
        val (sender, group, text) = extractNotificationContent(notificationToShow, prefs)
        val (notifTitle, rawNotifText) = buildNotificationTitleAndText(
            sender, group, text, packageName, notificationToShow.notification.category
        )
        val notifText = com.github.gezimos.inkos.helper.CallStatusText.localize(
            context, notificationToShow, rawNotifText
        )

        return NotificationInfo(
            count = samePackage.size.coerceAtLeast(1),
            title = notifTitle,
            text = notifText,
            category = notificationToShow.notification.category,
            timestamp = notificationToShow.postTime
        )
    }

    /**
     * Detects if a notification is a summary notification that should be filtered out.
     * Summary notifications show generic text like "X messages from Y contacts" instead of actual content.
     * Uses caching to improve performance.
     */
    internal fun isNotificationSummary(sbn: android.service.notification.StatusBarNotification): Boolean {
        val cacheKey = "${sbn.key}_${sbn.postTime}"
        
        // Check cache first for performance
        summaryCache[cacheKey]?.let { return it }
        
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) {
            summaryCache[cacheKey] = true
            return true
        }
        
        if (summaryCache.size > 100) {
            val keysToRemove = summaryCache.keys.take(summaryCache.size - 50)
            keysToRemove.forEach { summaryCache.remove(it) }
        }
        
        // Not a summary (no flag set)
        summaryCache[cacheKey] = false
        return false
    }


}
