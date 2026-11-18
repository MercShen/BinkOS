package com.github.gezimos.inkos

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager.LayoutParams
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.navigation.NavController
import androidx.navigation.findNavController
import com.github.gezimos.common.CrashHandler
import com.github.gezimos.inkos.data.Constants
import com.github.gezimos.inkos.data.Migration
import com.github.gezimos.inkos.data.Prefs
import com.github.gezimos.inkos.databinding.ActivityMainBinding
import com.github.gezimos.inkos.helper.AudioWidgetHelper
import com.github.gezimos.inkos.helper.BigmeEink
import com.github.gezimos.inkos.helper.EditModeHelper
import com.github.gezimos.inkos.helper.GestureBindings
import com.github.gezimos.inkos.helper.hideNavigationBar
import com.github.gezimos.inkos.helper.isTablet
import com.github.gezimos.inkos.helper.showNavigationBar
import com.github.gezimos.inkos.ui.dialogs.ComposeDialogManager
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import com.github.gezimos.inkos.helper.utils.VibrationHelper as VH

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var migration: Migration
    private lateinit var navController: NavController
    private lateinit var viewModel: MainViewModel
    private lateinit var binding: ActivityMainBinding
    private var einkHelper: EinkHelper? = null
    private var wasInBackground: Boolean = false
    var allowEdgeSwipeBack: Boolean = true
    private var backGesture: com.github.gezimos.inkos.helper.BackGesture? = null
    private var lastDestinationId: Int = -1

    // --- Activity Result Launchers ---

    val defaultHomeLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        }

    private fun showBackupError(title: String, message: String) {
        ComposeDialogManager(this, this).showErrorDialog(this, title, message)
    }

    val backupWriteLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            lifecycleScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    com.github.gezimos.inkos.helper.BackupZipHelper.backupToUri(
                        applicationContext,
                        Prefs(applicationContext),
                        uri
                    )
                }
                if (!outcome.ok) {
                    Log.e("BackupExport", "Export failed: ${outcome.message}")
                    showBackupError(
                        getString(R.string.error_invalid_file_title),
                        outcome.message ?: "Export failed"
                    )
                }
            }
        }

    val backupReadLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            lifecycleScope.launch {
                val prefs = Prefs(applicationContext)
                // Reads the URI with a size cap, stages+validates, snapshots, applies with rollback.
                val outcome = withContext(Dispatchers.IO) {
                    com.github.gezimos.inkos.helper.BackupZipHelper.importFromUri(
                        applicationContext,
                        prefs,
                        uri
                    )
                }
                if (!outcome.ok) {
                    showBackupError(
                        getString(R.string.error_invalid_file_title),
                        outcome.message ?: getString(R.string.error_invalid_file_message)
                    )
                    return@launch
                }
                startActivity(Intent.makeRestartActivityTask(this@MainActivity.intent?.component))
            }
        }

    val themeBackupWriteLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            lifecycleScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    com.github.gezimos.inkos.helper.BackupZipHelper.exportThemeToUri(
                        applicationContext,
                        Prefs(applicationContext),
                        uri
                    )
                }
                if (!outcome.ok) {
                    Log.e("ThemeExport", "Export failed: ${outcome.message}")
                    showBackupError("Export failed", outcome.message ?: "Export failed")
                    return@launch
                }
                android.widget.Toast.makeText(
                    this@MainActivity,
                    getString(R.string.theme_exported),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

    val themeBackupReadLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            lifecycleScope.launch {
                val prefs = Prefs(applicationContext)
                val outcome = withContext(Dispatchers.IO) {
                    com.github.gezimos.inkos.helper.BackupZipHelper.importThemeFromUri(
                        applicationContext,
                        prefs,
                        uri
                    )
                }
                if (!outcome.ok) {
                    Log.e("ThemeImport", "Import failed: ${outcome.message}")
                    showBackupError("Import failed", outcome.message ?: "Import failed")
                    return@launch
                }
                android.widget.Toast.makeText(
                    this@MainActivity,
                    getString(R.string.theme_imported),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                startActivity(Intent.makeRestartActivityTask(this@MainActivity.intent?.component))
            }
        }

    companion object {
        private const val TAG = "MainActivity"

        /**
         * 自动化入口（无手势）。给 AI agent / 脚本用：
         *
         *   adb shell am start -n app.binkos/com.github.gezimos.inkos.MainActivity \
         *       --es dest settings --ez skip_onboarding true
         *
         * dest 取值见 [handleAutomationIntent]。
         */
        const val EXTRA_AUTOMATION_DEST = "dest"
        const val EXTRA_AUTOMATION_SKIP_ONBOARDING = "skip_onboarding"
        const val EXTRA_AUTOMATION_REFRESH = "refresh_eink"
    }

    private fun isOnboarding(): Boolean {
        if (!::navController.isInitialized) return false
        val destId = navController.currentDestination?.id
        return destId == R.id.onboardingFragment
    }

    /**
     * 无手势入口：把「只挂在手势上的界面」变成可以用 adb 直接打开的。
     *
     * 支持的 dest：
     *   home / drawer / recents / tray / hub / letters / settings /
     *   lookfeel / about / wallpaper / colors / themes / guide / onboarding
     *
     * 额外开关：
     *    --ez skip_onboarding true   清掉首启标记（不再卡在引导）
     *    --ez refresh_eink true      打开界面后强制一次 GC16 全刷
     *    --ei onboarding_page N     仅 dest=onboarding，直接打开第 N 页（0..7，需 confirm_write）
     *
     * @return true 表示这条 intent 已被自动化入口处理，调用方不必再走普通分支。
     */
    private fun findHomeFragment(): com.github.gezimos.inkos.ui.HomeFragmentCompose? {
        fun walk(f: androidx.fragment.app.Fragment?): com.github.gezimos.inkos.ui.HomeFragmentCompose? {
            if (f == null) return null
            if (f is com.github.gezimos.inkos.ui.HomeFragmentCompose) return f
            for (child in f.childFragmentManager.fragments) {
                walk(child)?.let { return it }
            }
            return null
        }
        return walk(supportFragmentManager.findFragmentById(R.id.nav_host_fragment))
    }

    private val automationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingAutomation: Runnable? = null
    private var pendingAutomationCancelled: (() -> Unit)? = null

    private fun automationResult(requestId: String, dest: String, status: String, message: String) {
        val result = org.json.JSONObject()
            .put("requestId", requestId).put("dest", dest).put("status", status).put("message", message)
        Log.i(TAG, "automation_result: $result")
    }

    private fun cancelPendingAutomation() {
        pendingAutomation?.let { automationHandler.removeCallbacks(it) }
        pendingAutomation = null
        val cancelled = pendingAutomationCancelled
        pendingAutomationCancelled = null
        cancelled?.invoke()
    }

    private fun automationState(): String {
        val state = viewModel.homeUiState.value
        return org.json.JSONObject()
            .put("page", state.currentPage).put("pageCount", state.homePagesNum)
            .put("editMode", EditModeHelper.isEditMode()).put("bottomWidget", state.bottomWidgetType)
            .put("pagerVisible", prefs.homePager).put("pagerMargin", prefs.pageIndicatorMargin)
            .put("homeReset", prefs.homeReset).put("currentDestination", navController.currentDestination?.id?.let {
                resources.getResourceEntryName(it)
            })
            .toString()
    }

    private fun handleAutomationIntent(intent: Intent?): Boolean {
        intent ?: return false
        val keys = listOf(EXTRA_AUTOMATION_DEST, EXTRA_AUTOMATION_SKIP_ONBOARDING, EXTRA_AUTOMATION_REFRESH,
            "onboarding_page", "guide", "tab", "value", "type", "confirm_write", "request_id")
        // Ordinary launcher intents are not automation requests. Never consume their product extras.
        if (!listOf(EXTRA_AUTOMATION_DEST, EXTRA_AUTOMATION_SKIP_ONBOARDING, EXTRA_AUTOMATION_REFRESH,
                "request_id").any { intent.hasExtra(it) }) return false
        val extras = try {
            keys.filter { intent.hasExtra(it) }.associateWith { key ->
                @Suppress("DEPRECATION")
                intent.extras?.get(key)
            }
        } catch (_: Exception) {
            keys.forEach { intent.removeExtra(it) }
            automationResult("", "", "rejected", "Unreadable extras")
            return true
        }
        // Consume before dispatch: recreate must not replay writes or editor requests.
        keys.forEach { intent.removeExtra(it) }
        setIntent(intent)
        val rawId = extras["request_id"]
        val requestId = rawId as? String ?: java.util.UUID.randomUUID().toString()
        val rawDest = (extras["dest"] as? String).orEmpty()
        if (rawId != null && (rawId !is String || rawId.length > 128 || rawId.any { it.isISOControl() })) {
            automationResult("", rawDest.take(64), "rejected", "request_id must be a short printable string")
            return true
        }
        val validation = com.github.gezimos.inkos.helper.AutomationContract.validate(extras, BuildConfig.DEBUG)
        if (validation is com.github.gezimos.inkos.helper.AutomationContract.Validation.Rejected) {
            automationResult(requestId, rawDest.take(64), "rejected", validation.message)
            return true
        }
        val request = (validation as com.github.gezimos.inkos.helper.AutomationContract.Validation.Accepted).request
        if (!::navController.isInitialized || !::viewModel.isInitialized) {
            automationResult(requestId, request.dest, "rejected", "Activity is not ready")
            return true
        }
        if (request.dest == "status") {
            automationResult(requestId, request.dest, "applied", automationState())
            return true
        }
        cancelPendingAutomation()
        if (request.needsHome || request.dest == "home") {
            // Navigation is not user dismissal: suppress parent-reopen callbacks.
            com.github.gezimos.inkos.ui.dialogs.ComposeBottomSheetHost.dismissAllSheets()
            navController.popBackStack(R.id.mainFragment, false)
        } else if (!request.writesPreferences) {
            com.github.gezimos.inkos.ui.dialogs.ComposeBottomSheetHost.dismissAllSheets()
        }
        val startedAt = android.os.SystemClock.uptimeMillis()
        val task = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) {
                    cancelPendingAutomation()
                    return
                }
                val active = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                val homeReady = !request.needsHome ||
                    (navController.currentDestination?.id == R.id.mainFragment &&
                        findHomeFragment()?.let { it.isAdded && it.view != null && it.isResumed } == true)
                val editorDelayReady = request.dest != "editmode_on" ||
                    android.os.SystemClock.uptimeMillis() - startedAt >= 600L
                if (!active || !homeReady || !editorDelayReady) {
                    if (android.os.SystemClock.uptimeMillis() - startedAt > 3_000L) {
                        pendingAutomation = null
                        pendingAutomationCancelled = null
                        automationResult(requestId, request.dest, "rejected", "Timed out waiting for resumed Activity/Home")
                    } else automationHandler.postDelayed(this, 50L)
                    return
                }
                pendingAutomation = null
                pendingAutomationCancelled = null
                try {
                    dispatchAutomation(request)
                    if (request.skipOnboarding) prefs.firstOpen = false
                    automationResult(requestId, request.dest,
                        if (request.isStateCommand || request.dest in setOf("home", "editmode", "editmode_on", "editmode_off", "home_next", "home_prev")) "applied" else "dispatched",
                        if (request.isStateCommand || request.dest in setOf("home", "editmode", "editmode_on", "editmode_off", "home_next", "home_prev")) automationState()
                        else "Dispatched; verify the target UI (authentication/loading may still be pending)")
                } catch (e: Exception) {
                    automationResult(requestId, request.dest, "rejected", "Dispatch failed: ${e.javaClass.simpleName}")
                }
            }
        }
        pendingAutomation = task
        pendingAutomationCancelled = { automationResult(requestId, request.dest, "cancelled", "Superseded or Activity left foreground") }
        automationHandler.post(task)
        return true
    }

    private fun dispatchAutomation(request: com.github.gezimos.inkos.helper.AutomationContract.Request) {
        fun home() = requireNotNull(findHomeFragment()) { "Home is not available" }
        when (request.dest) {
            "home" -> backToHomeScreen()
            "drawer" -> navController.navigate(R.id.appListFragment)
            "recents" -> navController.navigate(R.id.recentsFragment)
            "tray" -> navController.navigate(R.id.simpleTrayFragment)
            "hub" -> navController.navigate(R.id.hubFragment)
            "letters" -> navController.navigate(R.id.lettersFragment)
            "settings" -> navController.navigate(R.id.settingsFragment)
            "lookfeel", "fonts", "gestures", "notifsettings", "advanced", "drawersettings", "about" -> {
                val route = when (request.dest) {
                    "lookfeel" -> "LookFeel"
                    "fonts" -> "Fonts"
                    "gestures" -> "Gestures"
                    "notifsettings" -> "Notifications"
                    "advanced" -> "Advanced"
                    "drawersettings" -> "Drawer"
                    else -> "Support"
                }
                navController.navigate(R.id.settingsFragment, androidx.core.os.bundleOf("initialRoute" to route))
            }
            "wallpaper" -> navController.navigate(R.id.wallpaperFragment)
            "colors" -> navController.navigate(R.id.colorEditorFragment)
            "themes" -> navController.navigate(R.id.themePresetsFragment)
            "guide" -> navController.navigate(R.id.guideFragment)
            "onboarding" -> {
                request.onboardingPage?.let { prefs.onboardingPage = it }
                navController.navigate(R.id.onboardingFragment)
            }
            "firsttime" -> {
                val guide = requireNotNull(request.guide) { "firsttime guide missing" }
                fun replayTooltip(key: String) {
                    com.github.gezimos.inkos.helper.DebugFirstTimeGuide.forcedTooltipKey.value = key
                }
                when (guide) {
                    "home" -> com.github.gezimos.inkos.helper.DebugFirstTimeGuide.forcedHomeStep.value = 1
                    "home_pinch" -> com.github.gezimos.inkos.helper.DebugFirstTimeGuide.forcedHomeStep.value = 1
                    "home_swipe_pages" -> com.github.gezimos.inkos.helper.DebugFirstTimeGuide.forcedHomeStep.value = 2
                    "home_long_swipe" -> com.github.gezimos.inkos.helper.DebugFirstTimeGuide.forcedHomeStep.value = 3
                    "recents" -> {
                        replayTooltip("tooltip_recents_shown")
                        navController.navigate(R.id.recentsFragment)
                    }
                    "letters" -> {
                        replayTooltip("tooltip_letters_shown")
                        navController.navigate(R.id.lettersFragment)
                    }
                    "editfavorites" -> {
                        replayTooltip("tooltip_edit_favorites_shown")
                        navController.navigate(R.id.appsFragment, androidx.core.os.bundleOf("flag" to "EditFavorites", "n" to 0))
                    }
                    "hiddenapps" -> {
                        replayTooltip("tooltip_hidden_apps_shown")
                        navController.navigate(R.id.appsFragment, androidx.core.os.bundleOf("flag" to "HiddenApps", "n" to 0))
                    }
                    "edit_hiddenapps" -> {
                        replayTooltip("tooltip_edit_hidden_apps_shown")
                        navController.navigate(R.id.appsFragment, androidx.core.os.bundleOf("flag" to "EditHiddenApps", "n" to 0))
                    }
                    "editmode" -> {
                        if (!com.github.gezimos.inkos.helper.DebugFirstTimeGuide.debugOpenedEditMode) {
                            com.github.gezimos.inkos.helper.DebugFirstTimeGuide.editModeWasEnabled = EditModeHelper.isEditMode()
                            com.github.gezimos.inkos.helper.DebugFirstTimeGuide.debugOpenedEditMode = true
                        }
                        com.github.gezimos.inkos.helper.DebugFirstTimeGuide.forcedEditModeGuide.value = true
                        EditModeHelper.setEditMode(true)
                    }
                    "refresh_animation" -> {
                        com.github.gezimos.inkos.helper.DebugFirstTimeGuide.forcedOnboardingPage.value = 7
                        com.github.gezimos.inkos.helper.DebugFirstTimeGuide.forceRefreshAnimationScope.value = true
                        navController.navigate(R.id.onboardingFragment)
                    }
                    "close" -> {
                        com.github.gezimos.inkos.helper.DebugFirstTimeGuide.closeRequest.value = System.nanoTime()
                        if (com.github.gezimos.inkos.helper.DebugFirstTimeGuide.debugOpenedEditMode) {
                            EditModeHelper.setEditMode(com.github.gezimos.inkos.helper.DebugFirstTimeGuide.editModeWasEnabled)
                            com.github.gezimos.inkos.helper.DebugFirstTimeGuide.debugOpenedEditMode = false
                        }
                    }
                }
            }
            "editmode" -> EditModeHelper.toggleEditMode()
            "editmode_on" -> EditModeHelper.setEditMode(true)
            "editmode_off" -> EditModeHelper.setEditMode(false)
            "quick_menu" -> home().automationShowQuickMenu()
            "edit_bg" -> home().automationShowBackgroundSettings(request.tab)
            "edit_bg_theme" -> home().automationShowBackgroundSettings(0)
            "edit_bg_align" -> home().automationShowBackgroundSettings(1)
            "edit_bg_islands" -> home().automationShowBackgroundSettings(2)
            "edit_bg_gestures" -> home().automationShowBackgroundSettings(3)
            "edit_clock" -> home().automationShowClockSettings(request.tab)
            "edit_date" -> home().automationShowDateSettings(request.tab)
            "edit_apps" -> home().automationShowAppSettings(request.tab)
            "shortswipe" -> com.github.gezimos.inkos.ui.compose.showShortSwipeZoneEditor(this, prefs) {}
            "editfavorites" -> navController.navigate(R.id.appsFragment, androidx.core.os.bundleOf("flag" to "EditFavorites", "n" to 0))
            "shortcuticon" -> ComposeDialogManager(this, this).showShortcutIconPicker(prefs.shortcutLeftIcon) {}
            "badgeindicator" -> ComposeDialogManager(this, this).showBadgeIndicatorPicker(Constants.NotificationIndicator.fromOrdinal(prefs.notificationIndicatorStyle)) {}
            "privacy" -> ComposeDialogManager(this, this).showPrivacyPolicySheet()
            "fontlicense" -> ComposeDialogManager(this, this).showFontLicenseSheet()
            "home_next" -> viewModel.nextPage()
            "home_prev" -> viewModel.previousPage()
            "home_reset_off" -> viewModel.setHomeReset(false)
            "home_reset_on" -> viewModel.setHomeReset(true)
            "pager_margin" -> viewModel.setPageIndicatorMargin(requireNotNull(request.value))
            "pager_on" -> viewModel.setPageIndicatorVisible(true)
            "pager_off" -> viewModel.setPageIndicatorVisible(false)
            "bottomwidget", "bottom_android", "bottom_shortcuts", "bottom_quote", "bottom_events", "bottom_usage", "bottom_pagedots", "bottom_off" -> {
                val type = request.type ?: request.dest.removePrefix("bottom_")
                viewModel.setBottomWidgetType(when (type) {
                    "android" -> Constants.BottomWidgetType.AndroidWidget.value
                    "shortcuts" -> Constants.BottomWidgetType.Shortcuts.value
                    "quote" -> Constants.BottomWidgetType.Quote.value
                    "events" -> Constants.BottomWidgetType.Events.value
                    "usage" -> Constants.BottomWidgetType.TotalUsage.value
                    "pagedots" -> Constants.BottomWidgetType.PageDots.value
                    else -> Constants.BottomWidgetType.Disabled.value
                })
            }
            "allowlist_tray_clear" -> viewModel.setAllowedSimpleTrayApps(emptySet())
            "allowlist_hub_clear" -> viewModel.setAllowedHubApps(emptySet())
            "allowlist_clear" -> {
                viewModel.setAllowedSimpleTrayApps(emptySet())
                viewModel.setAllowedHubApps(emptySet())
            }
            else -> error("No dispatcher for validated dest")
        }
        if (request.refreshEink) {
            com.github.gezimos.inkos.helper.utils.EinkRefreshHelper.refreshEinkForced(this, prefs, null, 100, useActivityRoot = true)
        }
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (!::navController.isInitialized) return
            val destId = navController.currentDestination?.id
            if (destId == R.id.onboardingFragment) return
            if (destId != R.id.mainFragment) {
                navController.popBackStack()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isOnboarding()) return true
        return when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                when (navController.currentDestination?.id) {
                    R.id.mainFragment -> {
                        this.findNavController(R.id.nav_host_fragment)
                            .navigate(R.id.action_mainFragment_to_appListFragment)
                        true
                    }

                    else -> false
                }
            }

            KeyEvent.KEYCODE_ESCAPE -> {
                backToHomeScreen()
                true
            }

            KeyEvent.KEYCODE_HOME -> {
                com.github.gezimos.inkos.ui.HomeFragmentCompose.sendGoToFirstPageSignal()
                backToHomeScreen()
                true
            }

            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                return super.onKeyDown(keyCode, event)
            }

            else -> {
                return super.onKeyDown(keyCode, event)
            }
        }
    }

    interface PageNavigationHandler {
        val handleDpadAsPage: Boolean
        fun pageUp()
        fun pageDown()
    }

    var pageNavigationHandler: PageNavigationHandler? = null

    interface FragmentKeyHandler {
        fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean
    }

    var fragmentKeyHandler: FragmentKeyHandler? = null

    var suppressKeyForwarding: Boolean = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isOnboarding()) return super.dispatchKeyEvent(event)

        // --- Fragment key handler ---
        val fragHandler = fragmentKeyHandler
        if (event.action == KeyEvent.ACTION_DOWN && fragHandler != null) {
            try {
                if (fragHandler.handleKeyEvent(event.keyCode, event)) return true
            } catch (_: Exception) {}
        }

        val handler = pageNavigationHandler
        if (handler != null) {
            // --- Volume keys for page navigation ---
            if (prefs.useVolumeKeysForPages) {
                val audioWidgetHelper = AudioWidgetHelper.getInstance(this)
                val currentMediaPlayer = audioWidgetHelper.getCurrentMediaPlayer()
                val isAudioPlaying = currentMediaPlayer?.isPlaying == true
                
                when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (!isAudioPlaying) {
                                handler.pageUp()
                            } else {
                                return super.dispatchKeyEvent(event)
                            }
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (!isAudioPlaying) {
                                handler.pageDown()
                            } else {
                                return super.dispatchKeyEvent(event)
                            }
                        }
                        return true
                    }
                }
            }

            // --- DPAD / PAGE keys ---
            if (event.action == KeyEvent.ACTION_DOWN && handler.handleDpadAsPage) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                        handler.pageUp()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                        handler.pageDown()
                        return true
                    }
                }
            }
        }

        val superHandled = super.dispatchKeyEvent(event)
        if (superHandled) return true

        // --- Forward unhandled keys to app drawer search ---
        try {
            if (event.action == KeyEvent.ACTION_DOWN && !suppressKeyForwarding && prefs.appDrawerSearchEnabled) {
                try {
                    val ch = com.github.gezimos.inkos.helper.SearchHelper.keyEventToChar(event)
                    if (ch != null) {
                        try { viewModel.emitTypedChar(ch) } catch (_: Exception) {}
                        try {
                            if (::navController.isInitialized && navController.currentDestination?.id == R.id.mainFragment) {
                                navController.navigate(
                                    R.id.action_mainFragment_to_appListFragment,
                                    androidx.core.os.bundleOf("searchMode" to 0)
                                )
                            }
                        } catch (_: Exception) {}
                        return true
                    }
                } catch (_: Exception) {}

                if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                    try { viewModel.emitBackspaceEvent() } catch (_: Exception) {}
                    try {
                        if (::navController.isInitialized && navController.currentDestination?.id == R.id.mainFragment) {
                            navController.navigate(R.id.action_mainFragment_to_appListFragment)
                        }
                    } catch (_: Exception) {}
                    return true
                }
            }
        } catch (_: Exception) {}

        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleAutomationIntent(intent)) return
        if (isOnboarding()) return
        if (intent.getBooleanExtra("OPEN_SIMPLE_TRAY", false)) {
            navigateToSimpleTray()
            return
        }
        if (!::navController.isInitialized) return
        when (intent.action) {
            Constants.ACTION_OPEN_APP_DRAWER -> {
                try { navController.navigate(R.id.appListFragment) } catch (_: Exception) {}
                return
            }
            Constants.ACTION_OPEN_NOTIFICATIONS -> {
                try { navController.navigate(R.id.lettersFragment) } catch (_: Exception) {}
                return
            }
            Constants.ACTION_OPEN_SIMPLE_TRAY -> {
                try { navController.navigate(R.id.simpleTrayFragment) } catch (_: Exception) {}
                return
            }
            Constants.ACTION_OPEN_HUB -> {
                try { navController.navigate(R.id.hubFragment) } catch (_: Exception) {}
                return
            }
            Constants.ACTION_OPEN_RECENTS -> {
                try { navController.navigate(R.id.recentsFragment) } catch (_: Exception) {}
                return
            }
            Constants.ACTION_OPEN_SETTINGS -> {
                try { navController.navigate(R.id.settingsFragment) } catch (_: Exception) {}
                return
            }
        }
        if (intent.action == Intent.ACTION_MAIN) {
            backToHomeScreen()
        }
    }

    private fun navigateToSimpleTray() {
        if (!::navController.isInitialized) return
        try {
            navController.popBackStack(R.id.mainFragment, false)
            navController.navigate(R.id.simpleTrayFragment)
        } catch (_: Exception) {}
    }

    private fun backToHomeScreen() {
        cancelPendingAutomation()
        if (!::navController.isInitialized) return
        // Home 手势/按键回主页时，先关掉仍打开的底部弹窗（如 App Shortcuts 选择器）
        try {
            com.github.gezimos.inkos.ui.dialogs.ComposeBottomSheetHost.dismissAllSheets()
        } catch (_: Exception) {}
        val alreadyAtHome = navController.currentDestination?.id == R.id.mainFragment
        val returningFromBackground = wasInBackground
        navController.popBackStack(R.id.mainFragment, false)
        // 已经在主屏时 pop 不会产生导航变化（例如从其它 App 按 Home 回来）：
        // 这里补一次 Ripple 关闭。内部界面的返回由导航监听负责，不会重复播放。
        if (alreadyAtHome && returningFromBackground &&
            prefs.einkPageTurnAnimation && prefs.einkPageTurnAnimationStyle == 1
        ) {
            Log.i(TAG, "ripple close: returning to home from another app")
            try { BigmeEink.onScreenClosed(this) } catch (_: Exception) {}
        }
        try {
            com.github.gezimos.inkos.helper.utils.EinkRefreshHelper.refreshEink(
                this,
                prefs,
                null,
                prefs.einkRefreshDelay,
                useActivityRoot = true
            )
        } catch (_: Exception) {}
    }

    /** 首页长滑可绑定的界面 → 对应的 Action。 */
    private fun gestureActionFor(destinationId: Int): Constants.Action? = when (destinationId) {
        R.id.appListFragment, R.id.appDrawerListFragment, R.id.appsFragment -> Constants.Action.OpenAppDrawer
        R.id.lettersFragment -> Constants.Action.OpenLettersScreen
        R.id.recentsFragment -> Constants.Action.OpenRecentsScreen
        R.id.simpleTrayFragment -> Constants.Action.OpenSimpleTray
        R.id.hubFragment -> Constants.Action.OpenHub
        R.id.settingsFragment -> Constants.Action.OpenSettings
        else -> null
    }

    /** 当前界面的「打开方向」；未绑定长滑打开方向的界面为 null，不播放翻页动画。 */
    private var currentScreenOpenDirection: BigmeEink.Direction? = null

    /**
     * 打开 / 关闭界面时播放对应方向的大我动画：
     * 打开用该功能绑定的手势方向，关闭用它的反向（也就是关闭手势的方向）。
     * 设置页允许从图标/齿轮直接点入：没有绑定 OpenSettings 长滑时，用默认 R2L 打开、
     * 关闭 L2R，避免“直接点进去没有动画”；其它界面仍要求绑定方向。
     *
     * Ripple 样式只有一种规则：**除主页翻页外，进入下一级 = 10、返回上一级/回主页 = 9**，
     * 与长滑绑定无关，也绝不回退 Directional。前进/返回由 `previousBackStackEntry`
     * 判断，所以壁纸/指南这类没有长滑入口的界面也能正确播放。
     */
    private fun handleScreenAnimation(currentId: Int, previousId: Int) {
        if (!prefs.einkPageTurnAnimation) return
        val currentAction = gestureActionFor(currentId)
        val previousAction = gestureActionFor(previousId)
        val bound = currentAction?.let { GestureBindings.directionFor(it, prefs) }
        val openDirection = bound ?: if (currentAction == Constants.Action.OpenSettings) {
            BigmeEink.Direction.R2L
        } else null

        if (prefs.einkPageTurnAnimationStyle == 1) {
            if (previousId == -1) return
            // 「前进」= 当前界面的上一层就是刚才那个界面；否则是返回（pop）。
            // 用 previousBackStackEntry 而不是数栈深度，避免连图(nested graph)与
            // 回调时序造成的误判。
            val previousEntryId = try {
                navController.previousBackStackEntry?.destination?.id ?: -1
            } catch (_: Exception) {
                -1
            }
            val opened = when {
                previousEntryId == previousId -> true
                previousEntryId == -1 -> currentId != R.id.mainFragment
                else -> false
            }
            Log.i(TAG, "ripple ${if (opened) "open" else "close"} $previousId->$currentId prevEntry=$previousEntryId")
            if (opened) {
                currentScreenOpenDirection = openDirection
                BigmeEink.onScreenOpened(this, openDirection)
            } else {
                BigmeEink.onScreenClosed(this, currentScreenOpenDirection?.let { GestureBindings.opposite(it) })
                currentScreenOpenDirection = null
            }
            return
        }

        when {
            currentAction != null -> {
                currentScreenOpenDirection = openDirection
                if (previousAction == null && openDirection != null) {
                    BigmeEink.onScreenOpened(this, openDirection)
                }
            }
            previousAction != null -> {
                val direction = currentScreenOpenDirection
                if (direction != null) {
                    BigmeEink.onScreenClosed(this, GestureBindings.opposite(direction))
                }
                currentScreenOpenDirection = null
            }
        }
    }

    @RequiresPermission(anyOf = ["android.permission.READ_WALLPAPER_INTERNAL", Manifest.permission.MANAGE_EXTERNAL_STORAGE])
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Crash loop detection ---
        try {
            val crashPrefs = getSharedPreferences(CrashHandler.CRASH_LOOP_PREFS, MODE_PRIVATE)
            if (crashPrefs.getBoolean(CrashHandler.KEY_SAFE_MODE, false)) {
                crashPrefs.edit().remove(CrashHandler.KEY_SAFE_MODE).remove(CrashHandler.KEY_CRASH_TIMESTAMPS).apply()
                val intent = Intent(this, CrashReportActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
                finish()
                return
            }
        } catch (_: Exception) {}

        // Register back pressed callback
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        // ZIP 恢复/换包后绝对路径可能失效，启动时按自定义壁纸条目自愈
        prefs.repairWallpaperPath()
        // 同一原因：字体路径失效时按实际文件自愈，避免只有某个槽位（如日期）回落系统字体
        prefs.repairFontPaths()

        // --- Bigme (大我) e-ink animation: resolve the vendor DisplayPolicyManager once ---
        try {
            BigmeEink.init(this)
        } catch (_: Exception) {}

        try {
            VH.init(applicationContext, prefs)
        } catch (_: Exception) {}
        migration = Migration(this)
        migration.migratePreferencesOnVersionUpdate(prefs)

        if (EinkHelper.isMuditaKompakt()) {
            einkHelper = EinkHelper(packageName)
            lifecycle.addObserver(einkHelper!!)
            if (prefs.einkHelperMode != EinkHelper.MEINK_MODE_DISABLED) {
                einkHelper!!.setMeinkMode(prefs.einkHelperMode)
            }
        }

        // --- Centralized insets listener ---
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            if (EinkHelper.isMuditaKompakt()) {
                val imeVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
                einkHelper?.let { helper ->
                    val userMode = prefs.einkHelperMode
                    if (imeVisible && userMode != EinkHelper.MEINK_MODE_DISABLED && userMode != EinkHelper.MEINK_MODE_CONTRAST) {
                        helper.setMeinkMode(EinkHelper.MEINK_MODE_CONTRAST)
                    } else if (!imeVisible) {
                        helper.setMeinkMode(userMode)
                    }
                }
            }

            androidx.core.view.WindowInsetsCompat.Builder(insets).apply {
                if (!prefs.showStatusBar) {
                    setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.NONE)
                }
                if (!prefs.showNavigationBar) {
                    setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.NONE)
                }
            }.build()
        }

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext))

        val themeMode = when (prefs.appTheme) {
            Constants.Theme.Light -> AppCompatDelegate.MODE_NIGHT_NO
            Constants.Theme.Dark -> AppCompatDelegate.MODE_NIGHT_YES
            Constants.Theme.System -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(themeMode)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isDarkTheme = prefs.getResolvedTheme() == Constants.Theme.Dark
        windowInsetsController.isAppearanceLightNavigationBars = !isDarkTheme
        windowInsetsController.isAppearanceLightStatusBars = !isDarkTheme

        // --- System bar colors ---
        try {
            val rawBg = try { com.github.gezimos.inkos.style.resolveThemeColors(this).second } catch (_: Exception) { prefs.backgroundColor }
            val opaqueBg = if ((rawBg ushr 24) == 0) rawBg or (0xFF shl 24) else rawBg

            try {
                @Suppress("DEPRECATION")
                try {
                    window.statusBarColor = opaqueBg
                    window.navigationBarColor = opaqueBg
                } catch (_: Exception) {}

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    @Suppress("DEPRECATION")
                    try { window.navigationBarDividerColor = opaqueBg } catch (_: Exception) {}
                }

            } catch (_: Exception) {}
        } catch (_: Exception) {}

        if (prefs.showNavigationBar) {
            showNavigationBar(this)
        } else {
            hideNavigationBar(this)
        }

        if (prefs.showStatusBar) {
            com.github.gezimos.inkos.helper.showStatusBar(this)
        } else {
            com.github.gezimos.inkos.helper.hideStatusBar(this)
        }


        navController = this.findNavController(R.id.nav_host_fragment)

        if (prefs.firstOpen) {
            if (com.github.gezimos.inkos.helper.device.DeviceHelper.isEinkDevice()) {
                prefs.appTheme = Constants.Theme.Light
            }
            navController.navigate(R.id.onboardingFragment)
        }

        // 无手势入口：adb --es dest <name> 直接打开目标界面（见 handleAutomationIntent）
        // 仅在首次创建时消费，避免 Activity 重建（如切换语言）时重复执行
        if (savedInstanceState == null) {
            handleAutomationIntent(intent)
        }

        try {
            backGesture = com.github.gezimos.inkos.helper.BackGesture(
                binding.root,
                navController,
                prefs
            ) { allowEdgeSwipeBack && prefs.edgeSwipeBackEnabled }
        } catch (_: Exception) {}
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        viewModel.getAppList(includeHiddenApps = true)
        
        try {
            val appsRepository = com.github.gezimos.inkos.data.repository.AppsRepository.getInstance(application)
            appsRepository.registerPackageReceiver(this)
        } catch (_: Exception) {
            // Silently ignore registration errors
        }
        
        setupOrientation()
        // --- E-Ink refresh on navigation ---
        try {
            val skipIds = emptySet<Int>()
            navController.addOnDestinationChangedListener { _: NavController, destination: androidx.navigation.NavDestination, _: Bundle? ->
                try {
                    val previousId = lastDestinationId
                    lastDestinationId = destination.id
                    // 大我墨水屏动画：只在打开 / 关闭手势绑定界面时触发，且必须先于重绘
                    handleScreenAnimation(destination.id, previousId)
                    if (destination.id in skipIds) return@addOnDestinationChangedListener
                    if (prefs.einkRefreshEnabled && !prefs.einkRefreshHomeButtonOnly) {
                        com.github.gezimos.inkos.helper.utils.EinkRefreshHelper.refreshEink(
                            this,
                            prefs,
                            null,
                            prefs.einkRefreshDelay,
                            useActivityRoot = true
                        )
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

    }

    override fun onResume() {
        super.onResume()

        // --- Home-only E-Ink refresh on resume ---
        try {
            if (wasInBackground) {
                wasInBackground = false
                try {
                    if (prefs.einkRefreshHomeButtonOnly) {
                        if (::navController.isInitialized && navController.currentDestination?.id == R.id.mainFragment) {
                            com.github.gezimos.inkos.helper.utils.EinkRefreshHelper.refreshEink(
                                this,
                                prefs,
                                null,
                                prefs.einkRefreshDelay,
                                useActivityRoot = true
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        if (prefs.showNavigationBar) {
            showNavigationBar(this)
        } else {
            hideNavigationBar(this)
        }

        if (prefs.showStatusBar) {
            com.github.gezimos.inkos.helper.showStatusBar(this)
        } else {
            com.github.gezimos.inkos.helper.hideStatusBar(this)
        }

        window.decorView.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            try {
                window.decorView.requestFocus()
                val imm =
                    getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
                currentFocus?.let { view ->
                    imm?.hideSoftInputFromWindow(view.windowToken, 0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ignored exception during onResume UI operations", e)
            }

            einkHelper?.let {
                val currentMode = it.getCurrentMeinkMode()
                if (currentMode != -1) {
                    it.setMeinkMode(currentMode)
                }
            }
        }, 100)
    }

    override fun onPause() {
        cancelPendingAutomation()
        super.onPause()
        try {
            wasInBackground = true
            EditModeHelper.exitEditMode()
        } catch (_: Exception) {}
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        try {
            if (backGesture?.onTouchEvent(ev) == true) return true
        } catch (_: Exception) {}
        return super.dispatchTouchEvent(ev)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed, reinitializing MeInk service")
        einkHelper?.reinitializeMeinkService()

        prefs.onSystemThemeChanged()

        // --- Update AppCompat night mode for System theme ---
        if (prefs.appTheme == Constants.Theme.System) {
            val resolvedTheme = prefs.getResolvedTheme()
            val themeMode = when (resolvedTheme) {
                Constants.Theme.Light -> AppCompatDelegate.MODE_NIGHT_NO
                Constants.Theme.Dark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(themeMode)
            
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            val isDarkTheme = resolvedTheme == Constants.Theme.Dark
            windowInsetsController.isAppearanceLightNavigationBars = !isDarkTheme
            windowInsetsController.isAppearanceLightStatusBars = !isDarkTheme
            
            try {
                val rawBg = try { com.github.gezimos.inkos.style.resolveThemeColors(this).second } catch (_: Exception) { prefs.backgroundColor }
                val opaqueBg = if ((rawBg ushr 24) == 0) rawBg or (0xFF shl 24) else rawBg
                window.statusBarColor = opaqueBg
                window.navigationBarColor = opaqueBg
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    @Suppress("DEPRECATION")
                    try { window.navigationBarDividerColor = opaqueBg } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        // --- Update fragment backgrounds ---
        try {
            val fragments = supportFragmentManager.fragments
            for (frag in fragments) {
                if (frag is com.github.gezimos.inkos.ui.HomeFragmentCompose) continue

                if (frag !is androidx.navigation.fragment.NavHostFragment) {
                    try {
                        val fview = frag.view
                        if (fview != null) {
                            val bg = try { com.github.gezimos.inkos.style.resolveThemeColors(frag.requireContext()).second } catch (_: Exception) { null }
                            if (bg != null) {
                                try { fview.setBackgroundColor(bg) } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Also update any child fragments
                try {
                    val childFrags = frag.childFragmentManager.fragments
                    for (cf in childFrags) {
                        if (cf is com.github.gezimos.inkos.ui.HomeFragmentCompose) continue

                        val cv = cf.view
                        if (cv != null) {
                            val cbg = try { com.github.gezimos.inkos.style.resolveThemeColors(cf.requireContext()).second } catch (_: Exception) { null }
                            if (cbg != null) try { cv.setBackgroundColor(cbg) } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            val fragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
            val homeFragment =
                fragment?.childFragmentManager?.fragments?.find { it is com.github.gezimos.inkos.ui.HomeFragmentCompose } as? com.github.gezimos.inkos.ui.HomeFragmentCompose
            if (homeFragment != null && com.github.gezimos.inkos.ui.HomeFragmentCompose.isHomeVisible) {
                homeFragment.onWindowFocusGained()
            }
            einkHelper?.let {
                val currentMode = it.getCurrentMeinkMode()
                if (currentMode != -1) {
                    window.decorView.postDelayed({
                        it.setMeinkMode(currentMode)
                    }, 200)
                }
            }

            // --- Re-apply bar visibility ---
            window.decorView.postDelayed({
                try {
                    if (prefs.showNavigationBar) {
                        showNavigationBar(this)
                    } else {
                        hideNavigationBar(this)
                    }

                    if (prefs.showStatusBar) {
                        com.github.gezimos.inkos.helper.showStatusBar(this)
                    } else {
                        com.github.gezimos.inkos.helper.hideStatusBar(this)
                    }
                } catch (_: Exception) {}
            }, 150)
            
        }
    }

    override fun onDestroy() {
        cancelPendingAutomation()
        // 编辑器覆盖层挂在 decorView 上，随 Activity 一起销毁；顺手清掉标志位，避免下次进来还隐藏拖动条
        com.github.gezimos.inkos.helper.EditModeHelper.zoneEditorVisible.value = false
        super.onDestroy()
        einkHelper?.let {
            lifecycle.removeObserver(it)
        }
        try {
            val appsRepository = com.github.gezimos.inkos.data.repository.AppsRepository.getInstance(application)
            appsRepository.unregisterPackageReceiver(this)
        } catch (_: Exception) {
            // Silently ignore unregistration errors
        }
    }

    @Suppress("unused")
    fun setMeinkMode(mode: Int) {
        einkHelper?.setMeinkMode(mode)
    }

    private fun isNotificationServiceEnabled(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
    }


    @SuppressLint("SourceLockedOrientationActivity")
    private fun setupOrientation() {
        if (isTablet(this)) return
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
