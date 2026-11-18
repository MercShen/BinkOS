package com.github.gezimos.inkos.helper

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * 「系统不可清除通知」过滤：NFC、USB 调试、充电、常驻服务这类关不掉的系统通知，
 * 只从 Bink OS 自己的 Hub / Tray / 计数里隐藏，不碰系统通知本身
 * （普通应用本来也取消不掉 ongoing / FLAG_NO_CLEAR 通知，系统通知栏照常显示）。
 *
 * 规则：
 * - 只处理不可清除的（isOngoing 或 FLAG_NO_CLEAR / FLAG_ONGOING_EVENT）；
 * - 只处理系统应用（ApplicationInfo.FLAG_SYSTEM）发出的，第三方常驻通知（下载、录音、导航）保留；
 * - 电话类一律放行（category 是 CALL/MISSED_CALL，或发件包是默认拨号器）：
 *   Hub 隐藏「通话中」的 ongoing 状态，Tray 保留；未接来电是可清除通知，正常显示并计入。
 */
object NotificationFilter {

    /** 系统是否允许用户清除这条通知（ongoing / FLAG_NO_CLEAR 不允许）。 */
    fun isDismissible(sbn: StatusBarNotification): Boolean =
        !sbn.isOngoing &&
            (sbn.notification.flags and
                (Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT)) == 0

    /** category 明确标成通话/未接来电。 */
    fun isCallCategory(category: String?): Boolean =
        category == Notification.CATEGORY_CALL ||
            category == Notification.CATEGORY_MISSED_CALL

    fun isCallCategory(sbn: StatusBarNotification): Boolean =
        isCallCategory(sbn.notification.category)

    /**
     * 发件包是否属于拨号器相关。部分 ROM 的拨号器给通话/未接来电通知不打 category，
     * 只靠 CATEGORY_CALL 判断会漏，所以同时看默认拨号器（RoleManager）和常见包名。
     */
    @Volatile
    private var cachedDialerPackages: Set<String>? = null

    private fun dialerPackages(context: Context): Set<String> {
        cachedDialerPackages?.let { return it }
        val packages = mutableSetOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.incallui",
            "com.android.server.telecom",
            "com.android.phone"
        )
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            telecom?.defaultDialerPackage?.let { packages.add(it) }
        } catch (_: Exception) {
        }
        cachedDialerPackages = packages
        return packages
    }

    fun isCallPackage(context: Context, packageName: String): Boolean =
        packageName in dialerPackages(context)

    /** 通话类通知（category 或发件包任一命中）。 */
    fun isCallNotification(context: Context, sbn: StatusBarNotification): Boolean =
        isCallCategory(sbn) || isCallPackage(context, sbn.packageName)

    /**
     * 通话中的状态通知：属于通话类且关不掉。
     * Hub 不显示、不计入消息数，但 Tray 仍允许显示（状态而非消息）。
     * 未接来电是可清除通知，不会命中这里。
     */
    fun isOngoingCall(context: Context, sbn: StatusBarNotification): Boolean =
        isCallNotification(context, sbn) && !isDismissible(sbn)

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
        if (isCallNotification(context, sbn)) return false
        return isSystemApp(context, sbn.packageName)
    }
}
