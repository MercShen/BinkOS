package com.github.gezimos.inkos.helper

import android.content.Context
import android.view.KeyEvent
import com.github.gezimos.inkos.R

/**
 * 把 keycode 念给人听。用于「自定义翻页键」的行值与提示文案。
 *
 * 表里只收键盘机上真可能被映射的键；其余退回 `KEYCODE_XXX` 的美化形式，
 * 至少让用户看出自己按到了哪颗键，而不是显示一个数字。
 */
object PageKeyLabels {

    /** 一次能显示几颗键（超出就省略，避免行文案在 Extra Large 字号下截断）。 */
    private const val MAX_VISIBLE = 3

    fun label(context: Context, keyCode: Int): String {
        val resId = when (keyCode) {
            KeyEvent.KEYCODE_PAGE_UP -> R.string.page_key_page_up
            KeyEvent.KEYCODE_PAGE_DOWN -> R.string.page_key_page_down
            KeyEvent.KEYCODE_VOLUME_UP -> R.string.page_key_volume_up
            KeyEvent.KEYCODE_VOLUME_DOWN -> R.string.page_key_volume_down
            KeyEvent.KEYCODE_DPAD_UP -> R.string.page_key_dpad_up
            KeyEvent.KEYCODE_DPAD_DOWN -> R.string.page_key_dpad_down
            KeyEvent.KEYCODE_DPAD_LEFT -> R.string.page_key_dpad_left
            KeyEvent.KEYCODE_DPAD_RIGHT -> R.string.page_key_dpad_right
            KeyEvent.KEYCODE_DPAD_CENTER -> R.string.page_key_center
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> R.string.page_key_enter
            KeyEvent.KEYCODE_BACK -> R.string.page_key_back
            KeyEvent.KEYCODE_ESCAPE -> R.string.page_key_escape
            KeyEvent.KEYCODE_HOME -> R.string.page_key_home
            KeyEvent.KEYCODE_MENU -> R.string.page_key_menu
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> R.string.page_key_delete
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> R.string.page_key_media_play_pause
            KeyEvent.KEYCODE_MEDIA_NEXT -> R.string.page_key_media_next
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> R.string.page_key_media_previous
            KeyEvent.KEYCODE_BOOKMARK -> R.string.page_key_bookmark
            KeyEvent.KEYCODE_STAR -> R.string.page_key_star
            KeyEvent.KEYCODE_POUND -> R.string.page_key_pound
            KeyEvent.KEYCODE_0 -> R.string.page_key_num_0
            KeyEvent.KEYCODE_1 -> R.string.page_key_num_1
            KeyEvent.KEYCODE_2 -> R.string.page_key_num_2
            KeyEvent.KEYCODE_3 -> R.string.page_key_num_3
            KeyEvent.KEYCODE_4 -> R.string.page_key_num_4
            KeyEvent.KEYCODE_5 -> R.string.page_key_num_5
            KeyEvent.KEYCODE_6 -> R.string.page_key_num_6
            KeyEvent.KEYCODE_7 -> R.string.page_key_num_7
            KeyEvent.KEYCODE_8 -> R.string.page_key_num_8
            KeyEvent.KEYCODE_9 -> R.string.page_key_num_9
            else -> 0
        }
        if (resId != 0) return context.getString(resId)
        // 兜底：KEYCODE_MEDIA_FORWARD -> "Media forward"
        return KeyEvent.keyCodeToString(keyCode)
            .removePrefix("KEYCODE_")
            .lowercase()
            .replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /** 行值文案：多颗键用顿号/逗号连接，超过上限就收尾加省略。 */
    fun summary(context: Context, codes: Set<Int>): String {
        if (codes.isEmpty()) return context.getString(R.string.page_key_none)
        val sorted = codes.sorted()
        val shown = sorted.take(MAX_VISIBLE).joinToString(if (isChinese(context)) "、" else ", ") { label(context, it) }
        return if (sorted.size > MAX_VISIBLE) {
            context.getString(R.string.page_key_more, shown, sorted.size - MAX_VISIBLE)
        } else shown
    }

    private fun isChinese(context: Context): Boolean =
        context.resources.configuration.locales[0].language.equals("zh", ignoreCase = true)
}
