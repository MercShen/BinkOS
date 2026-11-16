package com.github.gezimos.inkos.helper

import java.util.Locale

/** Pure request validation shared by the Android dispatcher and JVM contract tests. */
object AutomationContract {
    data class Request(
        val dest: String,
        val tab: Int = 0,
        val value: Int? = null,
        val type: String? = null,
        val skipOnboarding: Boolean = false,
        val refreshEink: Boolean = false,
        val onboardingPage: Int? = null
    ) {
        val isStateCommand: Boolean get() = dest in stateCommands
        val writesPreferences: Boolean get() = isStateCommand || skipOnboarding || onboardingPage != null
        val needsHome: Boolean get() = dest in homeCommands
    }

    sealed interface Validation {
        data class Accepted(val request: Request) : Validation
        data class Rejected(val message: String) : Validation
    }

    private val aliases = buildMap {
        fun names(canonical: String, vararg others: String) {
            put(canonical, canonical)
            others.forEach { put(it, canonical) }
        }
        names("home")
        names("status")
        names("drawer", "apps", "applist")
        names("recents")
        names("tray", "simpletray")
        names("hub")
        names("letters", "notifications")
        names("settings")
        names("lookfeel")
        names("fonts", "typography")
        names("gestures", "gesture")
        names("notifsettings", "notification_settings")
        names("advanced")
        names("drawersettings", "drawer_settings")
        names("about", "support")
        names("wallpaper")
        names("colors", "coloreditor")
        names("themes", "themepresets")
        names("guide")
        names("onboarding")
        names("editmode", "edit_mode")
        names("editmode_on", "edit_on")
        names("editmode_off", "edit_off")
        names("quick_menu")
        names("edit_bg", "edit_background")
        names("edit_bg_theme")
        names("edit_bg_align")
        names("edit_bg_islands")
        names("edit_bg_gestures")
        names("edit_clock")
        names("edit_date")
        names("edit_apps")
        names("shortswipe", "short_swipe", "zone")
        names("editfavorites", "edit_favorites")
        names("shortcuticon", "shortcut_icon")
        names("badgeindicator", "badge_indicator")
        names("privacy", "privacypolicy")
        names("fontlicense", "font_license")
        names("home_next")
        names("home_prev")
        names("home_reset_off")
        names("home_reset_on")
        names("pager_margin")
        names("pager_on")
        names("pager_off")
        names("bottomwidget", "bottom_widget")
        names("bottom_android")
        names("bottom_shortcuts")
        names("bottom_quote")
        names("bottom_events")
        names("bottom_usage")
        names("bottom_pagedots")
        names("bottom_off")
        names("allowlist_clear")
        names("allowlist_tray_clear")
        names("allowlist_hub_clear")
    }

    private val types = mapOf(
        "android" to "android", "android_widget" to "android",
        "shortcuts" to "shortcuts", "search" to "shortcuts",
        "quote" to "quote", "events" to "events",
        "usage" to "usage", "total_usage" to "usage",
        "pagedots" to "pagedots", "page_dots" to "pagedots",
        "off" to "off", "disabled" to "off"
    )
    private val tabLimits = mapOf("edit_bg" to 3, "edit_clock" to 2, "edit_date" to 2, "edit_apps" to 3)
    private val stateCommands = setOf(
        "home_reset_off", "home_reset_on", "pager_margin", "pager_on", "pager_off", "bottomwidget",
        "bottom_android", "bottom_shortcuts", "bottom_quote", "bottom_events", "bottom_usage",
        "bottom_pagedots", "bottom_off", "allowlist_clear", "allowlist_tray_clear", "allowlist_hub_clear"
    )
    private val homeCommands = setOf(
        "quick_menu", "edit_bg", "edit_bg_theme", "edit_bg_align", "edit_bg_islands", "edit_bg_gestures",
        "edit_clock", "edit_date", "edit_apps", "editmode", "editmode_on", "shortswipe", "home_next", "home_prev"
    )

    fun validate(extras: Map<String, Any?>, debug: Boolean): Validation {
        fun reject(message: String) = Validation.Rejected(message)
        if (!debug) return reject("Automation is available only in debug builds")
        for (name in listOf("skip_onboarding", "refresh_eink", "confirm_write")) {
            if (extras.containsKey(name) && extras[name] !is Boolean) return reject("$name must be a boolean (--ez)")
        }
        val rawDest = extras["dest"] as? String ?: return reject("dest must be a non-empty string (--es)")
        if (rawDest.length > 64 || rawDest.any { it.isISOControl() }) return reject("dest must be a short printable name")
        val dest = aliases[rawDest.trim().lowercase(Locale.ROOT)] ?: return reject("Unknown dest")
        if (dest == "status" && (extras["skip_onboarding"] == true || extras["refresh_eink"] == true)) {
            return reject("status is read-only; do not combine it with skip_onboarding or refresh_eink")
        }
        val tab = if (extras.containsKey("tab")) {
            val limit = tabLimits[dest] ?: return reject("tab is not supported for $dest")
            val number = extras["tab"] as? Int ?: return reject("tab must be an integer (--ei)")
            if (number !in 0..limit) return reject("tab must be in 0..$limit")
            number
        } else 0
        val value = if (dest == "pager_margin") {
            val number = extras["value"] as? Int ?: return reject("pager_margin requires --ei value N")
            if (number !in 0..12) return reject("value must be in 0..12")
            number
        } else {
            if (extras.containsKey("value")) return reject("value is not supported for $dest")
            null
        }
        val type = if (dest == "bottomwidget") {
            val name = extras["type"] as? String ?: return reject("bottomwidget requires --es type NAME")
            types[name.trim().lowercase(Locale.ROOT)] ?: return reject("Unknown bottomwidget type")
        } else {
            if (extras.containsKey("type")) return reject("type is not supported for $dest")
            null
        }
        val onboardingPage = if (dest == "onboarding") {
            if (extras.containsKey("onboarding_page")) {
                val number = extras["onboarding_page"] as? Int
                    ?: return reject("onboarding_page must be an integer (--ei)")
                if (number !in 0..7) return reject("onboarding_page must be in 0..7")
                number
            } else null
        } else {
            if (extras.containsKey("onboarding_page")) {
                return reject("onboarding_page is only supported for onboarding")
            }
            null
        }
        val request = Request(dest, tab, value, type, extras["skip_onboarding"] == true,
            extras["refresh_eink"] == true, onboardingPage)
        if ((request.writesPreferences || request.refreshEink) && extras["confirm_write"] != true) {
            return reject("This command changes settings or forces a refresh; pass --ez confirm_write true only after authorization")
        }
        return Validation.Accepted(request)
    }
}
