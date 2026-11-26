package com.github.gezimos.inkos.ui.compose

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
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

/** 短滑区域编辑器的「交互极速刷新」token。 */
private const val TOKEN_ZONE_EDITOR = "short_swipe_zone"

/**
 * 短滑（翻页）生效区域编辑器。
 *
 * 视觉上与编辑模式里时钟 / 底部组件的手柄一致（细胶囊条、实心、无描边）：
 *   - 上下边界各一根可拖动的条，取代原来的粗虚线；
 *   - 拖动时在条的正下方 / 正上方显示当前 dp（水平居中）；
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
        EditModeHelper.zoneEditorCloseHook = null
        com.github.gezimos.inkos.helper.EinkInteractionMode.end(TOKEN_ZONE_EDITOR)
        try { backCallback?.remove() } catch (_: Exception) {}
        backCallback = null
        try { root.removeView(overlay) } catch (_: Exception) {}
        try { overlay.disposeComposition() } catch (_: Exception) {}
    }

    // 打开期间隐藏首页时钟 / 底部组件等其它拖动条；
    // 同时注册关闭回调，退出编辑模式时这一层覆盖会一起被收掉。
    EditModeHelper.zoneEditorVisible.value = true
    EditModeHelper.zoneEditorCloseHook = { close() }

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

    // 关键：这个编辑器是挂在 decorView 上的 ComposeView，和主页在**同一个窗口**里。
    // 面板本身是 Dialog 的 sheet 没这个问题（Dialog 有自己的窗口焦点），所以这里先尝试
    // 接管焦点。**2026-11-26 实测：接管没有生效**——按键仍然由主页的 ComposeView 收到，
    // DPAD_LEFT 照样触发 swipeLeftAction 打开最近任务，± 按钮收不到 CENTER。
    //
    // 这件事**已决定不继续修**（Merc 拍板）：短滑区配置的是触摸手势的生效范围，纯按键设备
    // 用不到这个界面，默认值就是最终值；为它引入 Dialog 窗口要连带承担墨水屏刷新/过渡的
    // 风险，不划算。± 步进保留，因为它让触摸也能把边界设准。
    // 重新评估的触发条件与回归断言见 docs/testing-playbook.md 键盘章节第 4 条。
    //
    // 下面这组调用是必要的但不充分：真要键盘可用，必须把编辑器改成 Dialog 窗口，
    // 不能只靠 requestFocus。
    overlay.isFocusable = true
    overlay.isFocusableInTouchMode = true
    overlay.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
    overlay.requestFocus()

    // 拖动手柄需要跟手：开着「交互时极速刷新」时切到极速波形，关闭时回到 Regal。
    com.github.gezimos.inkos.helper.EinkInteractionMode.begin(TOKEN_ZONE_EDITOR)

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
    val maxDp = Constants.MAX_SHORT_SWIPE_ZONE_DP
    // 顶部坐标从 30dp 起算：显示 0dp 对应屏幕顶部往下 30dp 的位置
    val topBase = Constants.SHORT_SWIPE_ZONE_TOP_BASE_DP
    var top by remember { mutableIntStateOf((initialTop - topBase).coerceAtLeast(0)) }
    var bottom by remember { mutableIntStateOf(initialBottom) }

    val textColor = Theme.colors.text
    val bgColor = Theme.colors.background
    val topPx = with(density) { (top + topBase).dp.toPx() }
    val bottomPx = with(density) { bottom.dp.toPx() }
    val topHandleOffset = (with(density) { topPx.toDp() } - HANDLE_HALF).coerceAtLeast(0.dp)
    // 底部区域为 0 时，「贴底对齐 + 上移 bottom」会把 32dp 高的手柄移出屏幕一半，
    // 只剩 2dp 的细线看着像不见了；这里保证对齐后不再往下移，整条始终可见。
    val bottomHandleOffset = (HANDLE_HALF - with(density) { bottomPx.toDp() }).coerceAtMost(0.dp)

    // 墨水屏上纯黑半透明最稳：无论浅色/深色主题，被压深的区域都明显暗一档
    val dimColor = Color.Black.copy(alpha = 0.55f)

    BoxWithConstraints(modifier = Modifier
        .fillMaxSize()
        // 编辑器开着的时候，按键不该再落到下层主页的手势上（实测 DPAD_LEFT 会打开最近任务）。
        // BACK 例外：它由 OnBackPressedCallback 负责关闭编辑器。
        .onKeyEvent { event ->
            event.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK
        }) {
        // 真实可用高度：LocalConfiguration 在挂到 decorView 的 ComposeView 上不可靠
        val screenHeightDp = maxHeight.value.roundToInt()

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

        val topMaxDp = (screenHeightDp - bottom - topBase - MIN_ZONE_DP).coerceIn(0, maxDp)
        val bottomMaxDp = (screenHeightDp - (top + topBase) - MIN_ZONE_DP).coerceIn(0, maxDp)

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
            maxValue = topMaxDp + topBase,
            invertDirection = false,
            labelBelow = true,
            onDrag = { dragPx ->
                val deltaDp = (dragPx / density.density).roundToInt()
                applyTop((top + deltaDp).coerceIn(0, topMaxDp))
            }
        )

        // 下边界拖动条
        ZoneBoundaryHandle(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = bottomHandleOffset),
            barColor = textColor,
            value = bottom,
            maxValue = bottomMaxDp,
            invertDirection = true,
            labelBelow = false,
            onDrag = { dragPx ->
                val deltaDp = (dragPx / density.density).roundToInt()
                applyBottom((bottom - deltaDp).coerceIn(0, bottomMaxDp))
            }
        )

        // 居中的说明面板：标题 + 说明 + 两行 ± 步进
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
            // 键盘取证：这个编辑器以前只认拖动，方向键不但调不了边界，
            // 还会穿透到下层主页触发滑动动作（DPAD_LEFT 会打开最近任务）。
            // 现在给两行 ± 步进，触摸和键盘都能用同一套值。
            ZoneStepperRow(
                label = stringResource(R.string.zone_top_boundary),
                value = top + topBase,
                canDecrease = (top + topBase) > topBase,
                canIncrease = top < topMaxDp,
                onStep = { delta -> applyTop((top + delta).coerceIn(0, topMaxDp)) },
                textColor = textColor,
                bgColor = bgColor,
                firstRow = true
            )
            ZoneStepperRow(
                label = stringResource(R.string.zone_bottom_boundary),
                value = bottom,
                canDecrease = bottom > 0,
                canIncrease = bottom < bottomMaxDp,
                onStep = { delta -> applyBottom((bottom + delta).coerceIn(0, bottomMaxDp)) },
                textColor = textColor,
                bgColor = bgColor
            )
            Text(
                text = stringResource(R.string.zone_stepper_hint),
                color = textColor,
                style = SettingsTheme.typography.header,
                fontSize = (SettingsTheme.typography.header.fontSize.value * 0.75f).sp
            )
        }
    }
}

/** 边界步进的一行：标题 + 当前 dp + − / +。− / + 是 clickable，方向键能选中、CENTER 能按。 */
@Composable
private fun ZoneStepperRow(
    label: String,
    value: Int,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onStep: (Int) -> Unit,
    textColor: Color,
    bgColor: Color,
    firstRow: Boolean = false
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            style = SettingsTheme.typography.header,
            fontSize = (SettingsTheme.typography.header.fontSize.value * 0.8f).sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value.toString(),
            color = textColor,
            style = SettingsTheme.typography.header,
            fontSize = (SettingsTheme.typography.header.fontSize.value * 0.8f).sp
        )
        ZoneStepButton(text = "−", enabled = canDecrease, onClick = { onStep(-ZONE_STEP_DP) }, textColor, bgColor, shape)
        ZoneStepButton(
            text = "+",
            enabled = canIncrease,
            onClick = { onStep(ZONE_STEP_DP) },
            textColor = textColor,
            bgColor = bgColor,
            shape = shape,
            bringFocusIntoEditor = firstRow
        )
    }
}

@Composable
private fun ZoneStepButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    textColor: Color,
    bgColor: Color,
    shape: androidx.compose.ui.graphics.Shape,
    bringFocusIntoEditor: Boolean = false
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused = interaction.collectIsFocusedAsState().value
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    if (bringFocusIntoEditor) {
        // 编辑器的组合里原本没有任何焦点起点：不主动请求一次的话，
        // 方向键即使在编辑器窗口内也无从「跳到下一个控件」。
        LaunchedEffect(Unit) {
            try { requester.requestFocus() } catch (_: Exception) {}
        }
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(shape)
            .background(if (focused) textColor else bgColor)
            .border(1.5.dp, textColor, shape)
            .focusRequester(requester)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (focused) bgColor else textColor.copy(alpha = if (enabled) 1f else 0.3f),
            style = SettingsTheme.typography.header,
            fontSize = (SettingsTheme.typography.header.fontSize.value * 0.9f).sp
        )
    }
}

/** 步进粒度：8dp 一格，墨水屏上按着连发也不会有太多中间刷新。 */
private const val ZONE_STEP_DP = 8

/**
 * 与首页编辑模式（时钟 / 底部组件）同款细胶囊拖动条，实心、无描边：
 * 深色模式白色、浅色模式黑色。拖动时在条的下方 / 上方正中显示当前 dp。
 */
@Composable
private fun ZoneBoundaryHandle(
    modifier: Modifier,
    barColor: Color,
    value: Int,
    maxValue: Int,
    invertDirection: Boolean = false,
    labelBelow: Boolean = true,
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
                        accumulatedPx += dragAmount
                        val candidate = (dragStartValue +
                            (accumulatedPx * directionMultiplier / density.density).toInt())
                            .coerceIn(0, maxValue)
                        // 到底后不再重复下发，数字也不会继续跑
                        if (candidate != dragValue) {
                            dragValue = candidate
                            latestOnDrag(dragAmount)
                        }
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
                text = "$dragValue",
                color = barColor,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = if (labelBelow) 28.dp else (-28).dp)
            )
        }
    }
}

/** 可短滑区域的最小高度（dp）。 */
private const val MIN_ZONE_DP = 120
private val HANDLE_TOUCH_WIDTH = 80.dp
private val HANDLE_TOUCH_HEIGHT = 32.dp
private val HANDLE_HALF = 16.dp
