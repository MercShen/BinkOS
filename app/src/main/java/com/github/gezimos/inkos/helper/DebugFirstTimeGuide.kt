package com.github.gezimos.inkos.helper

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Debug-only bridge for replaying first-time UI without clearing app data.
 *
 * Kept intentionally tiny: most guides are re-armed by removing their tooltip
 * preference before navigating; the refresh-animation dialog has no preference
 * trigger, so the onboarding screen observes this flow instead.
 */
object DebugFirstTimeGuide {
    val forceRefreshAnimationScope = MutableStateFlow(false)
    val forcedTooltipKey = MutableStateFlow<String?>(null)
    val forcedHomeStep = MutableStateFlow<Int?>(null)
    val forcedEditModeGuide = MutableStateFlow(false)
    val forcedOnboardingPage = MutableStateFlow<Int?>(null)
    val closeRequest = MutableStateFlow(0L)

    @Volatile var editModeWasEnabled = false
    @Volatile var debugOpenedEditMode = false
}
