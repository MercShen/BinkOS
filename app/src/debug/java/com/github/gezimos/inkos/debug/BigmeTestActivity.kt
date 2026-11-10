package com.github.gezimos.inkos.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.github.gezimos.inkos.helper.BigmeEink

/**
 * Debug-only 探针：通过 adb 触发一次大我「全刷」，验证 forceGlobalRefresh 的调用方式。
 *
 *   adb shell am start -n app.inkos.debug/com.github.gezimos.inkos.debug.BigmeTestActivity --ei mode 177
 */
class BigmeTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent?.getIntExtra("mode", BigmeEink.MODE_HD) ?: BigmeEink.MODE_HD
        val ok = BigmeEink.forceGlobalRefresh(applicationContext, mode)
        Log.i("BigmeTest", "forceGlobalRefresh(mode=$mode) -> $ok")
        finish()
    }
}
