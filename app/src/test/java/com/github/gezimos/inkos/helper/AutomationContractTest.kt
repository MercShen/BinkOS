package com.github.gezimos.inkos.helper

import org.junit.Assert.*
import org.junit.Test

class AutomationContractTest {
    private fun accepted(vararg pairs: Pair<String, Any?>) =
        (AutomationContract.validate(mapOf(*pairs), true) as AutomationContract.Validation.Accepted).request
    private fun rejected(vararg pairs: Pair<String, Any?>) =
        assertTrue(AutomationContract.validate(mapOf(*pairs), true) is AutomationContract.Validation.Rejected)

    @Test fun releaseRejectsEvenReadOnlyRequest() {
        assertTrue(AutomationContract.validate(mapOf("dest" to "status"), false) is AutomationContract.Validation.Rejected)
    }
    @Test fun aliasesNormalizeToCanonicalCommands() {
        assertEquals("edit_bg", accepted("dest" to " EDIT_BACKGROUND ").dest)
        assertEquals("drawer", accepted("dest" to "applist").dest)
        assertEquals("bottomwidget", accepted("dest" to "bottom_widget", "type" to "total_usage", "confirm_write" to true).dest)
        assertEquals("usage", accepted("dest" to "bottomwidget", "type" to "total_usage", "confirm_write" to true).type)
    }
    @Test fun unknownAndMistypedRequestsReject() {
        rejected("dest" to "does_not_exist")
        rejected("dest" to 1)
        rejected("dest" to "status", "confirm_write" to "true")
        rejected("dest" to "status\n")
    }
    @Test fun tabDefaultsAreZeroAndExplicitBoundsAreStrict() {
        assertEquals(0, accepted("dest" to "edit_apps").tab)
        assertEquals(3, accepted("dest" to "edit_apps", "tab" to 3).tab)
        rejected("dest" to "edit_apps", "tab" to 4)
        rejected("dest" to "edit_clock", "tab" to -1)
        rejected("dest" to "edit_clock", "tab" to "1")
        rejected("dest" to "edit_bg_theme", "tab" to 0)
        rejected("dest" to "settings", "tab" to 0)
    }
    @Test fun everyStateCommandRequiresConfirmation() {
        for (dest in listOf("pager_on", "pager_off", "home_reset_on", "home_reset_off", "bottom_android",
            "bottom_shortcuts", "bottom_quote", "bottom_events", "bottom_usage", "bottom_pagedots", "bottom_off",
            "allowlist_clear", "allowlist_hub_clear", "allowlist_tray_clear")) {
            rejected("dest" to dest)
            assertTrue(accepted("dest" to dest, "confirm_write" to true).writesPreferences)
        }
    }
    @Test fun marginMustBePresentIntegerInRange() {
        rejected("dest" to "pager_margin", "confirm_write" to true)
        rejected("dest" to "pager_margin", "value" to -1, "confirm_write" to true)
        rejected("dest" to "pager_margin", "value" to 13, "confirm_write" to true)
        rejected("dest" to "pager_margin", "value" to "0", "confirm_write" to true)
        assertEquals(12, accepted("dest" to "pager_margin", "value" to 12, "confirm_write" to true).value)
    }
    @Test fun irrelevantAndMissingTypeParametersReject() {
        rejected("dest" to "bottomwidget", "confirm_write" to true)
        rejected("dest" to "bottomwidget", "type" to "", "confirm_write" to true)
        rejected("dest" to "bottomwidget", "type" to "calendar", "confirm_write" to true)
        rejected("dest" to "home", "type" to "quote")
        rejected("dest" to "settings", "value" to 1)
    }
    @Test fun extrasWithSideEffectsRequireConfirmation() {
        rejected("dest" to "home", "skip_onboarding" to true)
        rejected("dest" to "home", "refresh_eink" to true)
        assertTrue(accepted("dest" to "home", "skip_onboarding" to true, "confirm_write" to true).skipOnboarding)
    }
    @Test fun statusCannotBeCombinedWithSideEffects() {
        rejected("dest" to "status", "skip_onboarding" to true, "confirm_write" to true)
        rejected("dest" to "status", "refresh_eink" to true, "confirm_write" to true)
        assertFalse(accepted("dest" to "status").needsHome)
    }
    @Test fun homeDependentCommandsAreMarked() {
        for (dest in listOf("edit_apps", "edit_clock", "edit_date", "edit_bg", "quick_menu", "home_next",
            "home_prev", "editmode", "editmode_on", "shortswipe")) assertTrue(accepted("dest" to dest).needsHome)
        assertFalse(accepted("dest" to "settings").needsHome)
    }
}
