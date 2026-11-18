package com.github.gezimos.inkos.helper

import android.app.Notification
import android.content.Context
import androidx.annotation.StringRes
import com.github.gezimos.inkos.R

/**
 * 通知没有正文时的分类兜底文案。全部来自 `strings.xml`，跟随 Bink OS 的 App 语言
 * （以前这些是硬编码英文，切了语言也不变）。
 *
 * 注意 `CATEGORY_CALL` 故意不在这里：通话/未接来电的文案由 [CallStatusText] 按
 * 「通话中 / 未接来电」区分，兜底这里再出文案会冲突。
 */
object NotificationFallbackText {

    @StringRes
    fun forCategory(category: String?): Int? = when (category) {
        Notification.CATEGORY_MESSAGE -> R.string.notif_fallback_message
        Notification.CATEGORY_EMAIL -> R.string.notif_fallback_email
        Notification.CATEGORY_ALARM -> R.string.notif_fallback_alarm
        Notification.CATEGORY_REMINDER -> R.string.notif_fallback_reminder
        Notification.CATEGORY_EVENT -> R.string.notif_fallback_event
        Notification.CATEGORY_PROMO -> R.string.notif_fallback_promo
        Notification.CATEGORY_TRANSPORT -> R.string.notif_fallback_transport
        Notification.CATEGORY_SYSTEM -> R.string.notif_fallback_system
        Notification.CATEGORY_SERVICE -> R.string.notif_fallback_service
        Notification.CATEGORY_ERROR -> R.string.notif_fallback_error
        Notification.CATEGORY_PROGRESS -> R.string.notif_fallback_progress
        Notification.CATEGORY_SOCIAL -> R.string.notif_fallback_social
        Notification.CATEGORY_STATUS -> R.string.notif_fallback_status
        Notification.CATEGORY_RECOMMENDATION -> R.string.notif_fallback_recommendation
        else -> null
    }

    fun get(context: Context, category: String?): String? =
        forCategory(category)?.let { context.getString(it) }
}
