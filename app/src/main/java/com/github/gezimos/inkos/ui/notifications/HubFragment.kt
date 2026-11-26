package com.github.gezimos.inkos.ui.notifications

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.github.gezimos.inkos.R
import com.github.gezimos.inkos.data.Prefs
import com.github.gezimos.inkos.helper.utils.VibrationHelper
import com.github.gezimos.inkos.style.SettingsTheme
import com.github.gezimos.inkos.style.Theme
import com.github.gezimos.inkos.style.rememberScreenScale
import com.github.gezimos.inkos.style.scaled

@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
class HubFragment : Fragment() {
    companion object {
        fun clearHubCache() { HubLayoutCache.clear() }
    }

    private lateinit var prefs: Prefs
    private var wasPaused = false
    private var focusRestoreTriggerState: androidx.compose.runtime.MutableState<Int>? = null
    private var permissionExplanationSheet: com.github.gezimos.inkos.ui.dialogs.ComposeBottomSheetHost? = null

    private fun showPermissionExplanationDialog(
        title: String,
        message: String,
        onContinue: () -> Unit
    ) {
        permissionExplanationSheet?.dismiss()
        val host = com.github.gezimos.inkos.ui.dialogs.ComposeBottomSheetHost(requireActivity())
        permissionExplanationSheet = host
        host.show {
            val screenScale = rememberScreenScale()
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp.scaled(screenScale))
            ) {
                com.github.gezimos.inkos.ui.dialogs.SheetTitle(title)
                Text(text = message, style = SettingsTheme.typography.item, color = Theme.colors.text)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        try { VibrationHelper.trigger(VibrationHelper.Effect.CLICK) } catch (_: Exception) {}
                        host.dismiss()
                        onContinue()
                    }) {
                        Text(text = getString(android.R.string.ok), style = SettingsTheme.typography.button, color = Theme.colors.text)
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val vm = try { androidx.lifecycle.ViewModelProvider(requireActivity())[com.github.gezimos.inkos.MainViewModel::class.java] } catch (_: Exception) { null }
        prefs = vm?.getPrefs() ?: Prefs(requireContext())
        val composeView = ComposeView(requireContext())
        composeView.setContent {
            val isDark = prefs.appTheme == com.github.gezimos.inkos.data.Constants.Theme.Dark
            val triggerState = remember { mutableStateOf(0) }
            focusRestoreTriggerState = triggerState
            SettingsTheme(isDark = isDark) {
                HubScreen(
                    prefs = prefs,
                    focusRestoreTrigger = triggerState.value,
                    onShowPermissionExplanationDialog = { title, message, onContinue ->
                        showPermissionExplanationDialog(title, message, onContinue)
                    },
                    onEditModeClick = {
                        com.github.gezimos.inkos.helper.EditModeHelper.showHubSettings(
                            requireContext(), this@HubFragment, prefs
                        ) { /* recomposition handles update */ }
                    },
                    onNavigateBack = {
                        try { findNavController().popBackStack() } catch (_: Exception) {}
                    }
                )
            }
        }
        return composeView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.defaultFocusHighlightEnabled = false
        view.foreground = null
        view.background = null
        view.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        val act = activity as? com.github.gezimos.inkos.MainActivity ?: return
        act.pageNavigationHandler = null

        if (wasPaused) {
            wasPaused = false
            view?.postDelayed({
                val currentView = view
                if (isAdded && !isDetached && currentView != null) {
                    try {
                        currentView.isFocusable = true
                        currentView.isFocusableInTouchMode = true
                        currentView.requestFocus()
                    } catch (_: Exception) {}
                }
            }, 350)
            focusRestoreTriggerState?.value = (focusRestoreTriggerState?.value ?: 0) + 1
        }
    }

    override fun onPause() {
        super.onPause()
        wasPaused = true
        val act = activity as? com.github.gezimos.inkos.MainActivity ?: return
        if (act.pageNavigationHandler != null) act.pageNavigationHandler = null
    }
}
