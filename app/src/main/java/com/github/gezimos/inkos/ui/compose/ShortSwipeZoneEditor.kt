package com.github.gezimos.inkos.ui.compose

import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.roundToInt

/**
 * 短滑（翻页）生效区域编辑器：
 * 全屏覆盖层，左右两侧各一根竖向滑杆（左=顶部边界，右=底部边界），
 * 用虚线 + 半透明遮罩实时预览真正会响应短滑的区域，避免底部弹窗遮挡画面。
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
    var top by remember { mutableIntStateOf(initialTop) }
    var bottom by remember { mutableIntStateOf(initialBottom) }

    val textColor = Theme.colors.text
    val bgColor = Theme.colors.background
    val topPx = with(density) { top.dp.toPx() }
    val bottomPx = with(density) { bottom.dp.toPx() }
    val screenHeightPx = with(density) { screenHeightDp.dp.toPx() }
    val bottomLinePx = screenHeightPx - bottomPx

    Box(modifier = Modifier.fillMaxSize()) {
        // 两条边界线：实心虚线，无灰底，墨水屏上最清晰
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(24f, 16f), 0f)
            drawLine(textColor, Offset(0f, topPx), Offset(size.width, topPx), 3.dp.toPx(), pathEffect = dash)
            drawLine(textColor, Offset(0f, bottomLinePx), Offset(size.width, bottomLinePx), 3.dp.toPx(), pathEffect = dash)
        }

        // 顶部居中信息（与屏幕中线对齐）
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
                .background(bgColor, RoundedCornerShape(10.dp))
                .border(2.dp, textColor, RoundedCornerShape(10.dp))
                .padding(horizontal = 22.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.short_swipe_zone_top_fmt, top),
                color = textColor,
                style = SettingsTheme.typography.title
            )
            Text(
                text = stringResource(R.string.short_swipe_zone_bottom_fmt, bottom),
                color = textColor,
                style = SettingsTheme.typography.title
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.short_swipe_zone_hint),
                color = textColor,
                style = SettingsTheme.typography.body
            )
        }

        // 左侧滑杆：顶部边界（镜像对称地贴左边缘）
        ZoneSlider(
            modifier = Modifier
                .fillMaxHeight()
                .width(56.dp)
                .align(Alignment.CenterStart),
            thumbYPx = topPx,
            trackColor = textColor,
            onDrag = { y ->
                val value = (y / density.density).roundToInt().coerceIn(0, maxDp)
                if (screenHeightDp - value - bottom >= 120) {
                    top = value
                    onTopChange(value)
                }
            }
        )

        // 右侧滑杆：底部边界（与左侧同宽、同内缩）
        ZoneSlider(
            modifier = Modifier
                .fillMaxHeight()
                .width(56.dp)
                .align(Alignment.CenterEnd),
            thumbYPx = bottomLinePx,
            trackColor = textColor,
            onDrag = { y ->
                val value = ((screenHeightPx - y) / density.density).roundToInt().coerceIn(0, maxDp)
                if (screenHeightDp - value - top >= 120) {
                    bottom = value
                    onBottomChange(value)
                }
            }
        )

        // 底部居中完成按钮
        Text(
            text = stringResource(R.string.short_swipe_zone_done),
            color = bgColor,
            style = SettingsTheme.typography.title,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .background(textColor, RoundedCornerShape(10.dp))
                .clickable { onDone() }
                .padding(horizontal = 32.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun ZoneSlider(
    modifier: Modifier,
    thumbYPx: Float,
    trackColor: Color,
    onDrag: (Float) -> Unit
) {
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectVerticalDragGestures { change, _ ->
                change.consume()
                onDrag(change.position.y)
            }
        }
    ) {
        val cx = size.width / 2f
        drawLine(
            color = trackColor.copy(alpha = 0.35f),
            start = Offset(cx, 0f),
            end = Offset(cx, size.height),
            strokeWidth = 3.dp.toPx()
        )
        drawCircle(
            color = trackColor,
            radius = 14.dp.toPx(),
            center = Offset(cx, thumbYPx.coerceIn(0f, size.height))
        )
    }
}
