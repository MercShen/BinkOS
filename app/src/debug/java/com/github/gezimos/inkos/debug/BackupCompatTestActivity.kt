package com.github.gezimos.inkos.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.github.gezimos.inkos.data.Prefs
import java.io.File

/** 调试用：从本机文件导入备份 JSON，验证新旧格式兼容。 */
class BackupCompatTestActivity : Activity() {
    private fun prefsForDump() = Prefs(applicationContext)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (intent.getBooleanExtra("dump", false)) {
                Log.d("BackupTest", "dump=" + Prefs(applicationContext).saveToString().take(200))
                return finish()
            }
            val path = intent.getStringExtra("file") ?: return finish()
            val clear = intent.getBooleanExtra("clear", false)
            if (intent.getBooleanExtra("dump", false)) {
                Log.d("BackupTest", "dump=" + prefsForDump().saveToString().take(200))
                return finish()
            }
            val json = File(path).readText()
            val prefs = Prefs(applicationContext)
            if (clear) prefs.clear()
            prefs.loadFromString(json)
            val sp = applicationContext.getSharedPreferences("com.github.gezimos.inkos", 0)
            Log.d(
                "BackupTest",
                "ok file=" + File(path).name +
                    " keys=" + sp.all.size +
                    " theme=" + sp.getString("APP_THEME", "?") +
                    " font=" + sp.getString("LAUNCHER_FONT", "?")
            )
        } catch (e: Exception) {
            Log.e("BackupTest", "fail", e)
        }
        finish()
    }
}
