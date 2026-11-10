package com.github.gezimos.inkos.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.github.gezimos.inkos.helper.EditModeHelper

/**
 * Debug-only：通过 adb 切换首页编辑模式（正常入口是首页捏合打开快捷菜单）。
 *
 *   adb shell am start -n app.inkos.debug/com.github.gezimos.inkos.debug.EditModeTestActivity
 */
class EditModeTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val active = EditModeHelper.toggleEditMode()
        Log.i("EditModeTest", "toggleEditMode -> $active")
        finish()
    }
}