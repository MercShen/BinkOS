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

    val backupWriteLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.also { uri ->
                    try {
                        // ZIP = prefs.json + files/（字体、壁纸等私有文件）
                        val zip = com.github.gezimos.inkos.helper.BackupZipHelper.createBackupZip(
                            applicationContext,
                            Prefs(applicationContext)
                        )
                        zip.inputStream().use { input ->
                            applicationContext.contentResolver.openOutputStream(uri)?.use { out ->
                                input.copyTo(out)
                            }
                        }
                        zip.delete()
                    } catch (e: Exception) {
                        Log.e("BackupExport", "Export failed", e)
                    }
                }
            }
        }

    val backupReadLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.also { uri ->
                    val tmp = File(cacheDir, "inkos_import.tmp")
                    try {
                        applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { out -> input.copyTo(out) }
                        }
                    } catch (e: Exception) {
                        ComposeDialogManager(this, this).showErrorDialog(
                            this,
                            getString(R.string.error_invalid_file_title),
                            getString(R.string.error_invalid_file_message)
                        )
                        return@registerForActivityResult
                    }

                    val prefs = Prefs(applicationContext)

                    // 新格式：ZIP（prefs.json + files/），能把字体和壁纸一起恢复
                    if (com.github.gezimos.inkos.helper.BackupZipHelper.isZip(tmp)) {
                        val restored = com.github.gezimos.inkos.helper.BackupZipHelper.restore(
                            applicationContext,
                            prefs,
                            tmp
                        )
                        tmp.delete()
                        if (!restored) {
                            ComposeDialogManager(this, this).showErrorDialog(
                                this,
                                getString(R.string.error_invalid_file_title),
                                getString(R.string.error_invalid_file_message)
                            )
                            return@registerForActivityResult
                        }
                        startActivity(Intent.makeRestartActivityTask(this.intent?.component))
                        return@registerForActivityResult
                    }

                    // 旧格式：单个 JSON（全量设置 / 主题）
                    val string = tmp.readText()
                    tmp.delete()
                    val jsonObj: org.json.JSONObject = try {
                        org.json.JSONObject(string)
                    } catch (_: Exception) {
                        ComposeDialogManager(this, this).showErrorDialog(
                            this,
                            getString(R.string.error_invalid_file_title),
                            getString(R.string.error_invalid_file_message)
                        )
                        return@registerForActivityResult
                    }
                    if (jsonObj.optString("type") == "inkos_theme") {
                        try {
                            prefs.loadThemeFromJson(string)
                            android.widget.Toast.makeText(this, getString(R.string.theme_imported), android.widget.Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Log.e("ThemeImport", "Import failed", e)
                            ComposeDialogManager(this, this).showErrorDialog(this, "Import failed", e.message ?: "Unknown error")
                            return@registerForActivityResult
                        }
                    } else {
                        prefs.clear()
                        prefs.loadFromString(string)
                    }
                    startActivity(Intent.makeRestartActivityTask(this.intent?.component))
                }
            }
        }

    val themeBackupWriteLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.also { uri ->
                    try {
                        val prefs = Prefs(applicationContext)
                        val json = prefs.saveThemeToJson()
                        applicationContext.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                        }
                        android.widget.Toast.makeText(this, getString(R.string.theme_exported), android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("ThemeExport", "Export failed", e)
                        ComposeDialogManager(this, this).showErrorDialog(this, "Export failed", e.message ?: "Unknown error")
                    }
                }
            }
        }

    val themeBackupReadLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.also { uri ->
                    try {
                        val prefs = Prefs(applicationContext)
                        val json = applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                            BufferedReader(InputStreamReader(input)).use { it.readText() }
                        } ?: throw IllegalArgumentException("Cannot read file")
                        prefs.loadThemeFromJson(json)
                        android.widget.Toast.makeText(this, getString(R.string.theme_imported), android.widget.Toast.LENGTH_SHORT).show()
                        startActivity(Intent.makeRestartActivityTask(this.intent?.component))
                    } catch (e: Exception) {
                        Log.e("ThemeImport", "Import failed", e)
                        ComposeDialogManager(this, this).showErrorDialog(this, "Import failed", e.message ?: "Unknown error")
                    }
                }
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
     *
     * @return true 表示这条 intent 已被自动化入口处理，调用方不必再走普通分支。
     */
    private fun handleAutomationIntent(intent: Intent?): Boolean {
        intent ?: return false
        if (!::navController.isInitialized) return false

        if (intent.getBooleanExtra(EXTRA_AUTOMATION_SKIP_ONBOARDING, false)) {
            prefs.firstOpen = false
        }

        val dest = intent.getStringExtra(EXTRA_AUTOMATION_DEST)?.trim()?.lowercase()
        Log.i(TAG, "automation: dest=$dest action=${intent.action}")
        if (!dest.isNullOrEmpty()) {
            try {
                when (dest) {
                    "home" -> navController.popBackStack(R.id.mainFragment, false)
                    "drawer", "apps", "applist" -> navController.navigate(R.id.appListFragment)
                    "recents" -> navController.navigate(R.id.recentsFragment)
                    "tray", "simpletray" -> navController.navigate(R.id.simpleTrayFragment)
                    "hub" -> navController.navigate(R.id.hubFragment)
                    "letters", "notifications" -> navController.navigate(R.id.lettersFragment)
                    "settings" -> navController.navigate(R.id.settingsFragment)
                    "lookfeel" -> navController.navigate(
                        R.id.settingsFragment,
                        androidx.core.os.bundleOf("initialRoute" to "LookFeel")
                    )
                    "about", "support" -> navController.navigate(
                        R.id.settingsFragment,
                        androidx.core.os.bundleOf("initialRoute" to "Support")
                    )
                    "wallpaper" -> navController.navigate(R.id.wallpaperFragment)
                    "colors", "coloreditor" -> navController.navigate(R.id.colorEditorFragment)
                    "themes", "themepresets" -> navController.navigate(R.id.themePresetsFragment)
                    "guide" -> navController.navigate(R.id.guideFragment)
                    "onboarding" -> navController.navigate(R.id.onboardingFragment)
                    "editmode", "edit_mode" -> com.github.gezimos.inkos.helper.EditModeHelper.toggleEditMode()
                    "shortswipe", "short_swipe", "zone" ->
                        com.github.gezimos.inkos.ui.compose.showShortSwipeZoneEditor(this, prefs) {}
                    "editfavorites", "edit_favorites" -> navController.navigate(
                        R.id.appsFragment,
                        androidx.core.os.bundleOf("flag" to "EditFavorites", "n" to 0)
                    )
                    else -> Log.w(TAG, "automation: unknown dest '$dest'")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "automation: navigate '$dest' failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        if (intent.getBooleanExtra(EXTRA_AUTOMATION_REFRESH, false)) {
            try {
                com.github.gezimos.inkos.helper.utils.EinkRefreshHelper.refreshEinkForced(
                    this, prefs, null, 100, useActivityRoot = true
                )
            } catch (_: Throwable) {
            }
        }

        // 自动化的 extras 用过即清：Activity 因语言切换等重建时会复用同一个 launch intent，
        // 不清掉会在重建后把对话框/编辑器再弹一次。
        try {
            intent.removeExtra(EXTRA_AUTOMATION_DEST)
            intent.removeExtra(EXTRA_AUTOMATION_SKIP_ONBOARDING)
            intent.removeExtra(EXTRA_AUTOMATION_REFRESH)
            setIntent(intent)
        } catch (_: Throwable) {
        }

        return !dest.isNullOrEmpty()
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
        if (!::navController.isInitialized) return
        // Home 手势/按键回主页时，先关掉仍打开的底部弹窗（如 App Shortcuts 选择器）
        try {
            com.github.gezimos.inkos.ui.dialogs.ComposeBottomSheetHost.dismissAllSheets()
        } catch (_: Exception) {}
        navController.popBackStack(R.id.mainFragment, false)
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

    /** 当前界面的「打开方向」，关闭时取它的反向。 */
    private var currentScreenOpenDirection: BigmeEink.Direction = BigmeEink.Direction.B2T

    /**
     * 打开 / 关闭「由首页长滑绑定」的界面时播放对应方向的大我动画：
     * 打开用该功能绑定的手势方向，关闭用它的反向（也就是关闭手势的方向）。
     * 其他界面（壁纸、引导等）不触发。
     */
    private fun handleScreenAnimation(currentId: Int, previousId: Int) {
        if (!prefs.einkPageTurnAnimation) return
        val currentAction = gestureActionFor(currentId)
        val previousAction = gestureActionFor(previousId)
        when {
            currentAction != null -> {
                currentScreenOpenDirection = GestureBindings.openDirection(currentAction, prefs)
                if (previousAction == null) {
                    BigmeEink.onScreenOpened(this, currentScreenOpenDirection)
                }
            }
            previousAction != null -> {
                BigmeEink.onScreenClosed(this, GestureBindings.opposite(currentScreenOpenDirection))
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
