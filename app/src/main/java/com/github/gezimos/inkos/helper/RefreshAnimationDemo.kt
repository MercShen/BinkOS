package com.github.gezimos.inkos.helper

/** Shared once-only marker for the refresh-animation style demo. */
object RefreshAnimationDemo {
    const val SHOWN_KEY = "tooltip_refresh_animation_demo_shown"

    fun shouldShow(prefs: com.github.gezimos.inkos.data.Prefs): Boolean =
        !prefs.isTooltipShown(SHOWN_KEY)

    fun markShown(prefs: com.github.gezimos.inkos.data.Prefs) {
        prefs.markTooltipShown(SHOWN_KEY)
    }
}
