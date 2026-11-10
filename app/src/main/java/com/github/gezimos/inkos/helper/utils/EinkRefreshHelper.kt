package com.github.gezimos.inkos.helper.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.github.gezimos.inkos.data.Constants
import com.github.gezimos.inkos.data.Prefs
import com.github.gezimos.inkos.helper.BigmeEink

object EinkRefreshHelper {

    /** 在大我设备上优先调用系统 GC16 全刷；不可用时返回 false，回退到整屏闪烁。 */
    private fun trySystemFullRefresh(context: Context, prefs: Prefs): Boolean {
        if (!prefs.einkSystemFullRefresh) return false
        return try {
            BigmeEink.forceGlobalRefresh(context, BigmeEink.MODE_GC16)
        } catch (_: Throwable) {
            false
        }
    }

    fun refreshEink(
        context: Context,
        prefs: Prefs,
        rootView: ViewGroup?,
        delayMs: Int = 100,
        useActivityRoot: Boolean = false
    ) {
        if (!prefs.einkRefreshEnabled) return

        if (trySystemFullRefresh(context, prefs)) return

        Handler(Looper.getMainLooper()).post {
            val isDark = prefs.appTheme == Constants.Theme.Dark
            val overlayColor =
                if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            val overlay = View(context)
            overlay.setBackgroundColor(overlayColor)
            overlay.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val parent = if (useActivityRoot) {
                (context as? android.app.Activity)?.window?.decorView as? ViewGroup
            } else {
                rootView
            }
            parent?.addView(overlay)
            overlay.bringToFront()
            Handler(Looper.getMainLooper()).postDelayed({
                parent?.removeView(overlay)
            }, delayMs.toLong())
        }
    }
    fun refreshEinkForced(
        context: Context,
        prefs: Prefs,
        rootView: ViewGroup?,
        delayMs: Int = 100,
        useActivityRoot: Boolean = false
    ) {
        if (trySystemFullRefresh(context, prefs)) return

        Handler(Looper.getMainLooper()).post {
            val isDark = prefs.appTheme == Constants.Theme.Dark
            val overlayColor =
                if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            val overlay = View(context)
            overlay.setBackgroundColor(overlayColor)
            overlay.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            val parent = if (useActivityRoot) {
                (context as? android.app.Activity)?.window?.decorView as? ViewGroup
            } else {
                rootView
            }
            parent?.addView(overlay)
            overlay.bringToFront()
            Handler(Looper.getMainLooper()).postDelayed({
                parent?.removeView(overlay)
            }, delayMs.toLong())
        }
    }
}