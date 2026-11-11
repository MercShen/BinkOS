package com.github.gezimos.inkos.ui.compose

import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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

    Box(modifier = Modifier.fillMaxSize()) {
        // 生效区域预览：区域外压暗，上下边界画虚线
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dimColor = Color.Black.copy(alpha = 0.30f)
            val topLine = topPx.coerceIn(0f, size.height)
            val bottomLine = (size.height - bottomPx).coerceIn(0f, size.height)
            drawRect(dimColor, Offset.Zero, Size(size.width, topLine))
            drawRect(dimColor, Offset(0f, bottomLine), Size(size.width, size.height - bottomLine))

            val dash = PathEffect.dashPathEffect(floatArrayOf(20f, 14f), 0f)
            drawLine(Color.Black, Offset(0f, topLine), Offset(size.width, topLine), 2.dp.toPx(), pathEffect = dash)
            drawLine(Color.Black, Offset(0f, bottomLine), Offset(size.width, bottomLine), 2.dp.toPx(), pathEffect = dash)
        }

        // 左侧滑杆：顶部边界
        ZoneSlider(
            modifier = Modifier
                .fillMaxHeight()
                .width(72.dp)
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

        // 右侧滑杆：底部边界（手柄位置=底部边界线）
        ZoneSlider(
            modifier = Modifier
                .fillMaxHeight()
                .width(72.dp)
                .align(Alignment.CenterEnd),
            thumbYPx = (screenHeightPx - bottomPx),
            trackColor = textColor,
            onDrag = { y ->
                val value = ((screenHeightPx - y) / density.density).roundToInt().coerceIn(0, maxDp)
                if (screenHeightDp - value - top >= 120) {
                    bottom = value
                    onBottomChange(value)
                }
            }
        )

        // 中间的说明卡片
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 104.dp)
                .background(bgColor, RoundedCornerShape(10.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.short_swipe_zone_title),
                color = textColor,
                style = SettingsTheme.typography.title
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.short_swipe_zone_values_fmt, top, bottom),
                color = textColor,
                style = SettingsTheme.typography.item
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.short_swipe_zone_hint),
                color = textColor.copy(alpha = 0.7f),
                style = SettingsTheme.typography.body
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.short_swipe_zone_done),
                color = bgColor,
                style = SettingsTheme.typography.button,
                modifier = Modifier
                    .background(textColor, RoundedCornerShape(6.dp))
                    .clickable { onDone() }
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }
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
