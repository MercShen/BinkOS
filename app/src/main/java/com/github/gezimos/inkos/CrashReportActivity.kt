package com.github.gezimos.inkos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.gezimos.common.CrashHandler
import com.github.gezimos.inkos.helper.utils.CrashIssueLink
import com.github.gezimos.inkos.style.SettingsTheme
import com.github.gezimos.inkos.style.Theme
import com.github.gezimos.inkos.ui.dialogs.ComposeBottomSheetHost
import com.github.gezimos.inkos.ui.dialogs.SheetTitle

class CrashReportActivity : AppCompatActivity() {
    private var pkgName: String = ""
    private var pkgVersion: String = ""
    private var crashSheet: ComposeBottomSheetHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContentView(FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        })

        pkgName = getString(R.string.app_name)
        pkgVersion = this.packageManager.getPackageInfo(this.packageName, 0).versionName.toString()

        crashSheet = ComposeBottomSheetHost(this)
        crashSheet?.show {
            val prefs = com.github.gezimos.inkos.data.Prefs(this@CrashReportActivity)
            val isDark = prefs.appTheme == com.github.gezimos.inkos.data.Constants.Theme.Dark
            SettingsTheme(isDark) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SheetTitle(getString(R.string.acra_crash))
                    Text(
                        text = getString(R.string.acra_dialog_text).format(pkgName),
                        style = SettingsTheme.typography.item,
                        color = Theme.colors.text,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { restartApp(); crashSheet?.dismiss() }) {
                            Text(getString(R.string.acra_dont_send), color = Theme.colors.text)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { reportOnGitHub(this@CrashReportActivity); crashSheet?.dismiss() }) {
                            Text(getString(R.string.acra_send_report), color = Theme.colors.text)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        crashSheet?.dismiss()
        super.onDestroy()
    }

    /**
     * Opens a prefilled issue on this fork's tracker. Reports used to be mailed to the
     * upstream author, which is neither the maintainer of BinkOS nor an accountable
     * destination for user logs.
     */
    private fun reportOnGitHub(context: Context) {
        val url = CrashIssueLink.build(
            appName = pkgName,
            versionName = pkgVersion,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            crashLog = CrashHandler.crashLogText(applicationContext)
        )
        Log.i(TAG, "Opening crash issue: $url")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.e(TAG, "No activity could open the issue page", e)
            Toast.makeText(context, R.string.acra_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun restartApp() {
        try {
            getSharedPreferences(CrashHandler.CRASH_LOOP_PREFS, MODE_PRIVATE)
                .edit().clear().apply()
        } catch (_: Exception) {}

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
    }

    private companion object {
        const val TAG = "CrashReport"
    }
}
