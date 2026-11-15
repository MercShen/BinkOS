package com.github.gezimos.inkos

import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.gezimos.common.showShortToast
import com.github.gezimos.inkos.data.Constants
import com.github.gezimos.inkos.data.Prefs
import com.github.gezimos.inkos.helper.PinnedShortcutPolicy
import com.github.gezimos.inkos.helper.PinnedShortcutUtility
import com.github.gezimos.inkos.style.SettingsTheme
import com.github.gezimos.inkos.style.Theme

/**
 * 处理系统/旧版固定快捷方式请求。
 *
 * 产品边界：
 * - 只接受系统 PinItemRequest（API 26+），且必须先通过 isValid、并让用户确认后才 accept + 存储；
 * - legacy ACTION_CREATE_SHORTCUT / INSTALL_SHORTCUT 明确拒绝并提示改用系统 pin API，不再静默注入本地数据；
 * - 取消时 reject() 并以 RESULT_CANCELED 结束。
 */
class PinShortcutActivity : AppCompatActivity() {

    private var pendingRequest: LauncherApps.PinItemRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A newer system request replaces the confirmation without accepting the old one.
        pendingRequest = null
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            finish()
            return
        }
        Log.d(TAG, "Received intent: ${intent.action}")

        when {
            PinnedShortcutPolicy.isLegacyAction(intent.action) -> refuseLegacy()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                intent.action == LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT -> handlePinShortcutRequest()
            else -> {
                Log.w(TAG, "Unknown intent action: ${intent.action}")
                finish()
            }
        }
    }

    private fun refuseLegacy() {
        // 不保存本地 intent，也不落 PinnedShortcutData；只给调用方明确反馈
        Log.w(TAG, "Legacy shortcut request refused")
        showShortToast(LEGACY_UNSUPPORTED)

        // 老式请求方通常等待 RESULT_OK；明确回 CANCELED，避免把它当成成功
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun handlePinShortcutRequest() {
        val request = try {
            val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps.getPinItemRequest(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading pin request", e)
            null
        }

        if (request == null) {
            showShortToast(PIN_INVALID)
            finish()
            return
        }

        val shortcutInfo = request.shortcutInfo
        val decision = PinnedShortcutPolicy.acceptDecision(
            isValid = request.isValid,
            hasShortcutInfo = shortcutInfo != null,
            alreadyStored = false
        )
        if (decision == PinnedShortcutPolicy.AcceptDecision.REJECT) {
            showShortToast(PIN_INVALID)
            // PinItemRequest has no reject API; declining means finishing without accept().
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        when (request.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> {
                val info = shortcutInfo ?: run {
                    showShortToast(PIN_INVALID)
                    finish()
                    return
                }
                val label = info.shortLabel?.toString()
                    ?: info.longLabel?.toString()
                    ?: info.id
                val appLabel = try {
                    val pm = packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(info.`package`, 0)).toString()
                } catch (_: Exception) {
                    info.`package`
                }
                pendingRequest = request
                showConfirmUi(title = appLabel, shortcutLabel = label)
            }
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> {
                showShortToast(PIN_UNSUPPORTED)
                // PinItemRequest has no reject API; declining means finishing without accept().
                setResult(RESULT_CANCELED)
                finish()
            }
            else -> {
                showShortToast(PIN_INVALID)
                // PinItemRequest has no reject API; declining means finishing without accept().
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    private fun showConfirmUi(title: String, shortcutLabel: String) {
        val dark = try {
            Prefs(applicationContext).appTheme == Constants.Theme.Dark
        } catch (_: Exception) {
            false
        }
        val view = ComposeView(this).apply {
            setContent {
                SettingsTheme(dark) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Theme.colors.background)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = title,
                            color = Theme.colors.text,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "\"$shortcutLabel\" will be pinned to the home screen.",
                            color = Theme.colors.text
                        )
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ActionButton("Cancel") { cancelPin() }
                            ActionButton("Pin") { confirmPin() }
                        }
                    }
                }
            }
        }
        val container = android.widget.FrameLayout(this).apply {
            setBackgroundColor(if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            addView(
                view,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(container)
    }

    @Composable
    private fun ActionButton(text: String, onClick: () -> Unit) {
        // 墨屏：无 ripple/hover 层
        Box(
            modifier = Modifier
                .border(1.dp, Theme.colors.text, RoundedCornerShape(6.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(text = text, color = Theme.colors.text)
        }
    }

    private fun confirmPin() {
        val request = pendingRequest ?: run {
            finish()
            return
        }
        val accepted = try {
            PinnedShortcutUtility.acceptPinRequest(this, request)
        } catch (e: Exception) {
            Log.e(TAG, "Error accepting pin request", e)
            false
        }
        if (accepted) {
            showShortToast("Shortcut pinned")
            refreshApps()
            setResult(RESULT_OK)
        } else {
            showShortToast(PIN_FAILED)
            // PinItemRequest has no reject API; declining means finishing without accept().
            setResult(RESULT_CANCELED)
        }
        pendingRequest = null
        finish()
    }

    private fun cancelPin() {
        // Decline without ever calling accept().
        pendingRequest = null
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun refreshApps() {
        try {
            com.github.gezimos.inkos.data.repository.AppsRepository.getInstance(application).onPackageChanged()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh app list", e)
        }
    }

    companion object {
        private const val TAG = "PinShortcutActivity"
        private const val LEGACY_UNSUPPORTED =
            "Legacy shortcut requests aren't supported. This app should use the system shortcut pin API (Android 8+)."
        private const val PIN_INVALID = "Invalid shortcut pin request"
        private const val PIN_UNSUPPORTED = "This pin request type isn't supported"
        private const val PIN_FAILED = "Failed to pin shortcut"
    }
}
