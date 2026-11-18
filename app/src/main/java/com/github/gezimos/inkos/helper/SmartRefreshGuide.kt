package com.github.gezimos.inkos.helper

/** Shared once-only marker for the Smart Refresh Mode first-enable guide. */
object SmartRefreshGuide {
    const val SHOWN_KEY = "tooltip_smart_refresh_mode_shown"

    fun shouldShow(prefs: com.github.gezimos.inkos.data.Prefs): Boolean =
        !prefs.isTooltipShown(SHOWN_KEY)

    fun markShown(prefs: com.github.gezimos.inkos.data.Prefs) {
        prefs.markTooltipShown(SHOWN_KEY)
    }
}
