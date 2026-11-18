package com.github.gezimos.inkos.helper

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * 「系统不可清除通知」过滤：NFC、USB 调试、充电、常驻服务这类关不掉的系统通知，
 * 只从 BinkOS 自己的 Hub / Tray / 计数里隐藏，不碰系统通知本身
 * （普通应用本来也取消不掉 ongoing / FLAG_NO_CLEAR 通知，系统通知栏照常显示）。
 *
 * 规则：
 * - 只处理不可清除的（isOngoing 或 FLAG_NO_CLEAR / FLAG_ONGOING_EVENT）；
 * - 只处理系统应用（ApplicationInfo.FLAG_SYSTEM）发出的，第三方常驻通知（下载、录音、导航）保留；
 * - 电话类（CATEGORY_CALL / CATEGORY_MISSED_CALL）一律放行，来电与通话不受影响。
 */
object NotificationFilter {

    /** 系统是否允许用户清除这条通知（ongoing / FLAG_NO_CLEAR 不允许）。 */
    fun isDismissible(sbn: StatusBarNotification): Boolean =
        !sbn.isOngoing &&
            (sbn.notification.flags and
                (Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT)) == 0

    /** 通话 / 来电 / 未接来电：即使 ongoing 也必须在 Hub 里可见。 */
    fun isCallNotification(sbn: StatusBarNotification): Boolean =
        sbn.notification.category == Notification.CATEGORY_CALL ||
            sbn.notification.category == Notification.CATEGORY_MISSED_CALL

    private val systemAppCache = ConcurrentHashMap<String, Boolean>()

    /** 是否为系统应用（FLAG_SYSTEM）；查询失败按「非系统应用」处理，宁可多显示。 */
    fun isSystemApp(context: Context, packageName: String): Boolean =
        systemAppCache.getOrPut(packageName) {
            try {
                (context.packageManager.getApplicationInfo(packageName, 0).flags and
                    ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (_: Exception) {
                false
            }
        }

    /** 这条通知是否应从 Hub / Tray / 通知计数里隐藏。 */
    fun shouldHideFromViews(
        context: Context,
        sbn: StatusBarNotification,
        hideUndeletableSystemNotifications: Boolean
    ): Boolean {
        if (!hideUndeletableSystemNotifications) return false
        if (isDismissible(sbn)) return false
        if (isCallNotification(sbn)) return false
        return isSystemApp(context, sbn.packageName)
    }
}
