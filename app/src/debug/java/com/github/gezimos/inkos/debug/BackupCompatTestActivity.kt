package com.github.gezimos.inkos.debug

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import com.github.gezimos.inkos.data.Prefs
import com.github.gezimos.inkos.helper.WallpaperUtility
import java.io.File

/** 调试用：备份导入 / 壁纸恢复 / 字体恢复，验证格式兼容与灾难恢复流程。 */
class BackupCompatTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            intent.getStringExtra("alias")?.let { pkg ->
                Prefs(applicationContext).setAppAlias(pkg, "测试别名")
                Log.d("BackupTest", "alias=" + pkg)
                return finish()
            }
            if (intent.getBooleanExtra("params", false)) {
                val pr = Prefs(applicationContext)
                Log.d("BackupTest", "params clock=" + pr.homeClockAlignment + " date=" + pr.homeDateAlignment + " apps=" + pr.homeAlignment + " quote=" + pr.homeQuoteAlignment + " insets=" + pr.homeAppsInsetTop + "/" + pr.homeAppsInsetBottom + "/" + pr.homeAppsInsetStart + "/" + pr.homeAppsInsetEnd + " topM=" + pr.topWidgetMargin + " botM=" + pr.bottomWidgetMargin + " pager=" + pr.homePager + "/" + pr.homePagerAlign + " clockStyle=" + pr.clockStyle)
                return finish()
            }
            if (intent.getBooleanExtra("gestures", false)) {
                val pr = Prefs(applicationContext)
                Log.d("BackupTest", "gestures up=" + pr.swipeUpAction + " down=" + pr.swipeDownAction + " right=" + pr.swipeRightAction + " short=" + pr.shortSwipeThresholdRatio + " long=" + pr.longSwipeThresholdRatio + " edgeBack=" + pr.edgeSwipeBackEnabled)
                return finish()
            }
            if (intent.getBooleanExtra("checkwall", false)) {
                val pr = Prefs(applicationContext)
                val wp = pr.inkosWallpaperPath
                val f = wp?.let { File(it) }
                val bmp = wp?.let { BitmapFactory.decodeFile(it) }
                Log.d("BackupTest", "checkwall path=" + wp + " exists=" + (f?.exists()) + " size=" + (f?.length()) + " bmp=" + (bmp != null) + " opacity=" + pr.backgroundOpacity)
                return finish()
            }
            if (intent.getBooleanExtra("zipbackup", false)) {
                val zip = com.github.gezimos.inkos.helper.BackupZipHelper.createBackupZip(
                    applicationContext, Prefs(applicationContext)
                )
                val dest = File(filesDir, "export.zip")
                zip.copyTo(dest, overwrite = true)
                zip.delete()
                Log.d("BackupTest", "zipbackup=" + dest.length())
                return finish()
            }
            intent.getStringExtra("ziprestore")?.let { zp ->
                val ok = com.github.gezimos.inkos.helper.BackupZipHelper.restore(
                    applicationContext, Prefs(applicationContext), File(zp)
                )
                Log.d("BackupTest", "ziprestore=" + ok)
                return finish()
            }
            if (intent.getBooleanExtra("dump", false)) {
                val s = Prefs(applicationContext).saveToString()
                File(filesDir, "dump.json").writeText(s)
                Log.d("BackupTest", "dump=" + s.take(80) + " len=" + s.length + " aliasCount=" + Regex("app_alias_").findAll(s).count() + " hasSettingsAlias=" + s.contains("app_alias_com.android.settings") + " hasDialerAlias=" + s.contains("app_alias_com.android.dialer"))
                return finish()
            }
            intent.getStringExtra("wallpaper")?.let { wp ->
                val bmp = BitmapFactory.decodeFile(wp) ?: throw IllegalArgumentException("decode failed: " + wp)
                val wu = WallpaperUtility(applicationContext)
                val fitted = wu.createFittedBitmap(bmp, wu.screenWidth, wu.screenHeight)
                val saved = wu.saveBitmapToInternalStorage(fitted, "inkos_wallpaper.png")
                Prefs(applicationContext).inkosWallpaperPath = saved
                Log.d("BackupTest", "wallpaper=" + saved)
                return finish()
            }
            intent.getStringExtra("font")?.let { fp ->
                val src = File(fp)
                val dest = File(filesDir, src.name)
                if (src.absolutePath != dest.absolutePath) {
                    src.copyTo(dest, overwrite = true)
                }
                val p = Prefs(applicationContext)
                p.customFontPaths = (p.customFontPaths + dest.absolutePath).toMutableSet()
                Log.d("BackupTest", "font=" + dest.absolutePath)
                return finish()
            }
            val path = intent.getStringExtra("file") ?: return finish()
            val clear = intent.getBooleanExtra("clear", false)
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
