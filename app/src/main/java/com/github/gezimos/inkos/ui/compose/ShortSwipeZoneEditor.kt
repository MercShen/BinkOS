package com.github.gezimos.inkos.ui.compose

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.gezimos.inkos.R
import com.github.gezimos.inkos.data.Constants
import com.github.gezimos.inkos.data.Prefs
import com.github.gezimos.inkos.helper.EditModeHelper
import com.github.gezimos.inkos.style.SettingsTheme
import com.github.gezimos.inkos.style.Theme
import kotlin.math.roundToInt

/**
 * 短滑（翻页）生效区域编辑器。
 *
 * 视觉上与编辑模式里时钟 / 底部组件的手柄保持统一（同一套「细胶囊条」）：
 *   - 上下边界各一根可上下拖动的胶囊条，取代原来的粗虚线；
 *   - 不显示 dp 数值，靠压深区域实时反馈；
 *   - 中间只保留标题 + 一行小字说明。
 *
 * 退出：点被压深（不响应短滑）的区域，或按返回键。
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
        // 让首页恢复自己的拖动条
        EditModeHelper.zoneEditorVisible.value = false
        try { backCallback?.remove() } catch (_: Exception) {}
        backCallback = null
        try { root.removeView(overlay) } catch (_: Exception) {}
        try { overlay.disposeComposition() } catch (_: Exception) {}
    }

    // 打开期间隐藏首页时钟 / 底部组件等其它拖动条
    EditModeHelper.zoneEditorVisible.value = true

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
    val topHandleOffset = with(density) { topPx.toDp() } - HANDLE_HALF
    val bottomHandleOffset = with(density) { bottomPx.toDp() } - HANDLE_HALF

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

        // 上边界拖动条
        ZoneBoundaryHandle(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = topHandleOffset),
            barColor = textColor,
            value = top + topBase,
            invertDirection = false,
            onDrag = { dragPx ->
                val deltaDp = (dragPx / density.density).roundToInt()
                applyTop((top + deltaDp).coerceIn(0, maxDp))
            }
        )

        // 下边界拖动条
        ZoneBoundaryHandle(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -bottomHandleOffset),
            barColor = textColor,
            value = bottom,
            invertDirection = true,
            onDrag = { dragPx ->
                val deltaDp = (dragPx / density.density).roundToInt()
                applyBottom((bottom - deltaDp).coerceIn(0, maxDp))
            }
        )

        // 居中的说明面板：标题 + 一行小字
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .background(bgColor, RoundedCornerShape(12.dp))
                .border(2.dp, textColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = stringResource(R.string.short_swipe_zone_title),
                color = textColor,
                style = SettingsTheme.typography.header
            )
            Text(
                text = stringResource(R.string.short_swipe_zone_caption),
                color = textColor,
                style = SettingsTheme.typography.header,
                fontSize = (SettingsTheme.typography.header.fontSize.value * 0.9f).sp
            )
        }
    }
}

/**
 * 与首页编辑模式（时钟 / 底部组件）同款细胶囊拖动条，实心、无描边：
 * 深色模式白色、浅色模式黑色，在压深区和普通背景上都清晰。
 * 拖动过程中按 DragCueHandle 的做法，左右两侧显示「起始值 / 当前值」。
 */
@Composable
private fun ZoneBoundaryHandle(
    modifier: Modifier,
    barColor: Color,
    value: Int,
    invertDirection: Boolean = false,
    onDrag: (Float) -> Unit
) {
    val latestOnDrag by androidx.compose.runtime.rememberUpdatedState(onDrag)
    val latestValue by androidx.compose.runtime.rememberUpdatedState(value)
    val density = LocalDensity.current
    var isDragging by remember { mutableStateOf(false) }
    var dragStartValue by remember { mutableIntStateOf(value) }
    var dragValue by remember { mutableIntStateOf(value) }
    var accumulatedPx by remember { mutableFloatStateOf(0f) }
    val directionMultiplier = if (invertDirection) -1f else 1f

    Box(
        modifier = modifier
            .width(HANDLE_TOUCH_WIDTH)
            .height(HANDLE_TOUCH_HEIGHT)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        isDragging = true
                        dragStartValue = latestValue
                        dragValue = latestValue
                        accumulatedPx = 0f
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        latestOnDrag(dragAmount)
                        accumulatedPx += dragAmount
                        dragValue = dragStartValue +
                            (accumulatedPx * directionMultiplier / density.density).toInt()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(60.dp)
                .height(4.dp)
                .background(barColor, RoundedCornerShape(2.dp))
        )
        if (isDragging) {
            Text(
                text = "$dragStartValue",
                color = barColor.copy(alpha = 0.5f),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-16).dp)
            )
            Text(
                text = "$dragValue",
                color = barColor.copy(alpha = 0.8f),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 16.dp)
            )
        }
    }
}

/** 可短滑区域的最小高度（dp）。 */
private const val MIN_ZONE_DP = 120
private val HANDLE_TOUCH_WIDTH = 80.dp
private val HANDLE_TOUCH_HEIGHT = 32.dp
private val HANDLE_HALF = 16.dp
