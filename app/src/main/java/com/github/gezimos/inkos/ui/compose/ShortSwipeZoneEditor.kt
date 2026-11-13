package com.github.gezimos.inkos.ui.compose

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.gezimos.inkos.R
import com.github.gezimos.inkos.data.Constants
import com.github.gezimos.inkos.data.Prefs
import com.github.gezimos.inkos.style.SettingsTheme
import com.github.gezimos.inkos.style.Theme
import com.github.gezimos.inkos.ui.dialogs.StepperSlider

/**
 * 短滑（翻页）生效区域编辑器。
 *
 * 视觉语言与「设置 → margin / offset」那套 StepperSlider 保持一致：
 * 不再画边界虚线、不再用两侧滑杆手柄，改为
 *   1. 把**不响应短滑**的上下区域直接压深（视觉上强调中间生效区）；
 *   2. 屏幕正中给一块标题 + Top / Bottom 两条 −＋ 步进器。
 *
 * 退出：点压深的区域任意位置，或按返回键（不再有 Done 按钮）。
 */
fun showShortSwipeZoneEditor(context: Context, prefs: Prefs, onUpdate: () -> Unit) {
    val activity = context as? androidx.fragment.app.FragmentActivity
        ?: (context as? android.app.Activity)
        ?: return
    val root = activity.window?.decorView?.findViewById<android.view.ViewGroup>(android.R.id.content)
        ?: return
    val lifecycleOwner = activity as androidx.lifecycle.LifecycleOwner

    // 直接挂到 activity 的 content root（和 ComposeBottomSheetHost 相同的做法），
    // 这样 ViewTreeLifecycleOwner 能从 activity 继承，不会像 Dialog 那样崩溃。
    val overlay = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    var backCallback: androidx.activity.OnBackPressedCallback? = null
    fun close() {
        try { backCallback?.remove() } catch (_: Exception) {}
        backCallback = null
        try { root.removeView(overlay) } catch (_: Exception) {}
        try { overlay.disposeComposition() } catch (_: Exception) {}
    }

    overlay.setContent {
        SettingsTheme(isDark = prefs.getResolvedTheme() == Constants.Theme.Dark) {
            ShortSwipeZoneEditor(
                initialTop = prefs.shortSwipeZoneTop,
                initialBottom = prefs.shortSwipeZoneBottom,
                onTopChange = {
                    prefs.shortSwipeZoneTop = it
                    onUpdate()
                },
                onBottomChange = {
                    prefs.shortSwipeZoneBottom = it
                    onUpdate()
                },
                onDone = { close() }
            )
        }
    }

    root.addView(
        overlay,
        android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
    )

    // 返回键也能退出编辑
    try {
        val dispatcher = (activity as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher
        if (dispatcher != null) {
            backCallback = object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { close() }
            }.also { dispatcher.addCallback(lifecycleOwner, it) }
        }
    } catch (_: Exception) {
    }
}

@Composable
private fun ShortSwipeZoneEditor(
    initialTop: Int,
    initialBottom: Int,
    onTopChange: (Int) -> Unit,
    onBottomChange: (Int) -> Unit,
    onDone: () -> Unit
) {
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val maxDp = Constants.MAX_SHORT_SWIPE_ZONE_DP
    // 顶部坐标从 30dp 起算：显示 0dp 对应屏幕顶部往下 30dp 的位置
    val topBase = Constants.SHORT_SWIPE_ZONE_TOP_BASE_DP
    var top by remember { mutableIntStateOf((initialTop - topBase).coerceAtLeast(0)) }
    var bottom by remember { mutableIntStateOf(initialBottom) }

    val textColor = Theme.colors.text
    val bgColor = Theme.colors.background
    val topPx = with(density) { (top + topBase).dp.toPx() }
    val bottomPx = with(density) { bottom.dp.toPx() }

    // 墨水屏上纯黑半透明最稳：无论浅色/深色主题，被压深的区域都明显暗一档
    val dimColor = Color.Black.copy(alpha = 0.55f)

    // 至少留 120dp 的可短滑区，避免两条边撞在一起
    fun applyTop(value: Int) {
        if (screenHeightDp - (value + topBase) - bottom >= MIN_ZONE_DP) {
            top = value
            onTopChange(value + topBase)
        }
    }

    fun applyBottom(value: Int) {
        if (screenHeightDp - value - (top + topBase) >= MIN_ZONE_DP) {
            bottom = value
            onBottomChange(value)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 顶部被屏蔽区（不响应短滑）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { topPx.toDp() })
                .background(dimColor)
                .clickable { onDone() }
        )

        // 底部被屏蔽区
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { bottomPx.toDp() })
                .background(dimColor)
                .clickable { onDone() }
        )

        // 粗虚线分界线：保留原来的视觉锚点，标出「开始响应短滑 / 结束响应短滑」的位置
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(24f, 16f), 0f)
            val stroke = 3.dp.toPx()
            val topY = topPx.coerceIn(0f, size.height)
            val bottomY = (size.height - bottomPx).coerceIn(0f, size.height)
            drawLine(textColor, Offset(0f, topY), Offset(size.width, topY), stroke, pathEffect = dash)
            drawLine(textColor, Offset(0f, bottomY), Offset(size.width, bottomY), stroke, pathEffect = dash)
        }

        // 居中的 −＋ 面板，和设置里的 offset 控件同一套语言
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 28.dp)
                .background(bgColor, RoundedCornerShape(12.dp))
                .border(2.dp, textColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = stringResource(R.string.short_swipe_zone_title),
                color = textColor,
                style = SettingsTheme.typography.title
            )
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.short_swipe_zone_top_fmt, top),
                color = textColor,
                style = SettingsTheme.typography.item
            )
            StepperSlider(
                value = top,
                onValueChange = { applyTop(it) },
                minValue = 0,
                maxValue = maxDp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.short_swipe_zone_bottom_fmt, bottom),
                color = textColor,
                style = SettingsTheme.typography.item
            )
            StepperSlider(
                value = bottom,
                onValueChange = { applyBottom(it) },
                minValue = 0,
                maxValue = maxDp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 可短滑区域的最小高度（dp）。 */
private const val MIN_ZONE_DP = 120
