package com.github.gezimos.inkos.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedShortcutPolicyTest {

    @Test
    fun legacyActionsAreRefused() {
        assertTrue(PinnedShortcutPolicy.isLegacyAction("android.intent.action.CREATE_SHORTCUT"))
        assertTrue(PinnedShortcutPolicy.isLegacyAction("com.android.launcher.action.INSTALL_SHORTCUT"))
        assertFalse(PinnedShortcutPolicy.isLegacyAction("android.content.pm.action.CONFIRM_PIN_SHORTCUT"))
        assertFalse(PinnedShortcutPolicy.isLegacyAction(null))
    }

    @Test
    fun invalidOrMissingInfoRejects() {
        assertEquals(
            PinnedShortcutPolicy.AcceptDecision.REJECT,
            PinnedShortcutPolicy.acceptDecision(isValid = false, hasShortcutInfo = true, alreadyStored = false)
        )
        assertEquals(
            PinnedShortcutPolicy.AcceptDecision.REJECT,
            PinnedShortcutPolicy.acceptDecision(isValid = true, hasShortcutInfo = false, alreadyStored = false)
        )
    }

    @Test
    fun freshRequestStoresAfterAcceptDuplicateOnlyAccepts() {
        assertEquals(
            PinnedShortcutPolicy.AcceptDecision.ACCEPT_AND_STORE,
            PinnedShortcutPolicy.acceptDecision(isValid = true, hasShortcutInfo = true, alreadyStored = false)
        )
        assertEquals(
            PinnedShortcutPolicy.AcceptDecision.ACCEPT_DUPLICATE,
            PinnedShortcutPolicy.acceptDecision(isValid = true, hasShortcutInfo = true, alreadyStored = true)
        )
    }
}
