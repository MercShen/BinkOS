package com.github.gezimos.inkos.helper

import androidx.annotation.StringRes
import com.github.gezimos.inkos.R
import com.github.gezimos.inkos.data.Constants

/** 角标样式名称的本地化资源。 */
@StringRes
fun notificationIndicatorLabelRes(indicator: Constants.NotificationIndicator): Int = when (indicator) {
    Constants.NotificationIndicator.Asterisk -> R.string.badge_indicator_asterisk
    Constants.NotificationIndicator.FilledCircle -> R.string.badge_indicator_filled_circle
    Constants.NotificationIndicator.OutlineCircle -> R.string.badge_indicator_outline_circle
    Constants.NotificationIndicator.FilledDiamond -> R.string.badge_indicator_filled_diamond
    Constants.NotificationIndicator.FilledSquare -> R.string.badge_indicator_filled_square
}

/** 快捷方式图标名称的本地化资源。 */
@StringRes
fun shortcutIconLabelRes(icon: Constants.ShortcutIcon): Int = when (icon) {
    Constants.ShortcutIcon.Disabled -> R.string.shortcut_icon_disabled
    Constants.ShortcutIcon.Search -> R.string.shortcut_icon_search
    Constants.ShortcutIcon.Phone -> R.string.shortcut_icon_phone
    Constants.ShortcutIcon.Messages -> R.string.shortcut_icon_messages
    Constants.ShortcutIcon.Camera -> R.string.shortcut_icon_camera
    Constants.ShortcutIcon.Notes -> R.string.shortcut_icon_notes
    Constants.ShortcutIcon.Bubble -> R.string.shortcut_icon_bubble
    Constants.ShortcutIcon.Music -> R.string.shortcut_icon_music
    Constants.ShortcutIcon.Light -> R.string.shortcut_icon_light
    Constants.ShortcutIcon.Star -> R.string.shortcut_icon_star
    Constants.ShortcutIcon.Clock -> R.string.shortcut_icon_clock
}