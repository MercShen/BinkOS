package com.github.gezimos.inkos.helper

import android.content.Context
import android.service.notification.StatusBarNotification
import com.github.gezimos.inkos.R

/**
 * 通话类通知的「状态行」本地化。
 *
 * 拨号器把状态文案（Ongoing call / Calling… / Missed call）直接写进通知正文，
 * Bink OS 只能照搬；当 App 语言和系统语言不一致（Bink OS 可单独切换语言）时，
 * 这行就会和界面语言打架。这里只替换**已知状态短语**或空白文案，其它内容原样显示，
 * 避免吞掉拨号器提供的额外信息（联系人、时长等）。
 */
object CallStatusText {

    private val ongoingPhrases = setOf(
        "ongoing call", "ongoing", "connected", "call connected", "in call", "call in progress"
    )
    private val callingPhrases = setOf(
        "calling", "dialing", "ringing", "connecting"
    )
    private val missedPhrases = setOf(
        "missed call", "missed calls", "missed"
    )

    private fun normalize(text: String?): String? =
        text?.trim()?.trimEnd('.', '…')?.trim()?.lowercase()

    fun localize(ctx: Context, sbn: StatusBarNotification, text: String?): String? {
        if (!NotificationFilter.isCallNotification(ctx, sbn)) return text
        val normalized = normalize(text)
        val ongoing = NotificationFilter.isOngoingCall(ctx, sbn)
        return when {
            normalized.isNullOrEmpty() ->
                if (ongoing) ctx.getString(R.string.call_status_ongoing)
                else ctx.getString(R.string.call_status_missed)
            ongoing && normalized in callingPhrases -> ctx.getString(R.string.call_status_calling)
            ongoing && normalized in ongoingPhrases -> ctx.getString(R.string.call_status_ongoing)
            !ongoing && normalized in missedPhrases -> ctx.getString(R.string.call_status_missed)
            else -> text
        }
    }
}
