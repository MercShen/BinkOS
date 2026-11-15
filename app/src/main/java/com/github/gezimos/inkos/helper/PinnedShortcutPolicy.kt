package com.github.gezimos.inkos.helper

/**
 * 固定快捷方式的纯策略（无 Android 依赖，便于 JVM 单测）：
 * - legacy（ACTION_CREATE_SHORTCUT / com.android.launcher.action.INSTALL_SHORTCUT）一律拒绝：
 *   minSdk 26 起应走系统 PinItemRequest，不再静默注入本地 PinnedShortcutData。
 * - 现代 PinItemRequest 先校验 isValid/shortcutInfo，再决定 accept；已存在时只 accept 不重复存。
 */
object PinnedShortcutPolicy {

    const val ACTION_CREATE_SHORTCUT_LEGACY = "android.intent.action.CREATE_SHORTCUT"
    const val ACTION_INSTALL_SHORTCUT_LEGACY = "com.android.launcher.action.INSTALL_SHORTCUT"

    enum class AcceptDecision { ACCEPT_AND_STORE, ACCEPT_DUPLICATE, REJECT }

    fun isLegacyAction(action: String?): Boolean =
        action == ACTION_CREATE_SHORTCUT_LEGACY || action == ACTION_INSTALL_SHORTCUT_LEGACY

    fun acceptDecision(isValid: Boolean, hasShortcutInfo: Boolean, alreadyStored: Boolean): AcceptDecision {
        if (!isValid || !hasShortcutInfo) return AcceptDecision.REJECT
        return if (alreadyStored) AcceptDecision.ACCEPT_DUPLICATE else AcceptDecision.ACCEPT_AND_STORE
    }
}
