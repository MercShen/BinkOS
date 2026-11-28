package com.github.gezimos.inkos.ui.compose


import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import com.github.gezimos.inkos.helper.utils.VibrationHelper
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.github.gezimos.inkos.helper.ShapeHelper
import com.github.gezimos.inkos.style.LocalPrefs
import com.github.gezimos.inkos.style.SettingsTheme
import com.github.gezimos.inkos.style.Theme
import com.github.gezimos.inkos.style.rememberScreenScale
import com.github.gezimos.inkos.style.scaled

object SettingsComposable {

    @Composable
    fun tuToDp(textUnit: TextUnit): Dp {
        val density = LocalDensity.current.density
        val scaledDensity = LocalDensity.current.fontScale
        val dpValue = textUnit.value * (density / scaledDensity)
        return dpValue.dp
    }

    @Composable
    fun Modifier.pillHighlight(
        isHighlighted: Boolean,
        inset: Dp = 6.dp,
        color: Color,
        outerHorizontal: Dp = 16.dp,
        outerVertical: Dp = 16.dp,
        textIslandsShape: Int = LocalPrefs.current?.textIslandsShape ?: 0
    ): Modifier {
        val density = LocalDensity.current

        val insetPx = with(density) { inset.toPx() }
        val outerPxH = with(density) { outerHorizontal.toPx() }
        val outerPxV = with(density) { outerVertical.toPx() }
        return this
            .graphicsLayer(clip = false)
            .drawBehind {
                if (isHighlighted) {
                    val drawH = (size.height + outerPxV * 2 - insetPx * 2).coerceAtLeast(0f)
                    val drawW = (size.width + outerPxH * 2 - insetPx * 2).coerceAtLeast(0f)
                    val topLeft = Offset(-outerPxH + insetPx, -outerPxV + insetPx)
                    
                    val cornerRadius = ShapeHelper.getCornerRadius(
                        textIslandsShape = textIslandsShape,
                        height = drawH,
                        density = density
                    )
                    
                    drawRoundRect(
                        color = color,
                        topLeft = topLeft,
                        size = Size(drawW, drawH),
                        cornerRadius = cornerRadius
                    )
                }
            }
    }

    /**
     * 翻页导航条：页码点 + 左右箭头。
     *
     * 约定（弹窗里弹出键盘后可视高度变小、页数会暴涨，不能把每页都画成一个点）：
     * - 页码点最多画 [maxDots] 个，当前页始终在窗口内，窗口随当前页滑动。
     * - 箭头**只在真的有对应方向的页面时才出现**：第一页没有 ‹，最后一页没有 ›。
     *   用"箭头在不在"表达边界；把箭头画成灰色会让墨水屏上看起来仍然可翻。
     */
    @Composable
    fun PagedNavRow(
        currentPage: Int,
        pageCount: Int,
        onPrev: () -> Unit,
        onNext: () -> Unit,
        modifier: Modifier = Modifier,
        maxDots: Int = 8,
        prevLabel: String? = null,
        nextLabel: String? = null
    ) {
        if (pageCount <= 1) return
        val tintColor = SettingsTheme.color.settings
        val screenScale = rememberScreenScale()
        val islandsShape = LocalPrefs.current?.textIslandsShape ?: 0
        val dotCount = minOf(pageCount, maxDots.coerceAtLeast(1))
        val windowStart = pagedDotWindowStart(currentPage, pageCount, dotCount)
        val slot = 12.dp.scaled(screenScale)

        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // 左右箭头各占一个**固定槽位**（没箭头时留空）：
            // 否则箭头出现/消失会让整行重新居中，页码点看起来"整体挪了一下"。
            val arrowSlot = 28.dp.scaled(screenScale)
            val arrowGap = 8.dp.scaled(screenScale)
            Box(modifier = Modifier.size(arrowSlot), contentAlignment = Alignment.Center) {
                if (currentPage > 0) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronLeft,
                        contentDescription = prevLabel,
                        tint = tintColor,
                        modifier = Modifier
                            .size(arrowSlot)
                            .clickable { onPrev() }
                    )
                }
            }
            Spacer(Modifier.width(arrowGap))
            for (i in 0 until dotCount) {
                val page = windowStart + i
                val selected = page == currentPage
                val dotSize = if (selected) slot else slot * (10f / 12f)
                val dotShape = ShapeHelper.getPageDotShape(islandsShape, dotSize)
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp.scaled(screenScale))
                        .size(slot),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(dotSize)
                            .background(if (selected) tintColor else Color.Transparent, dotShape)
                            .then(
                                if (selected) Modifier
                                else Modifier.border(dotSize * (1f / 6f), tintColor, dotShape)
                            )
                    )
                }
            }
            Spacer(Modifier.width(arrowGap))
            Box(modifier = Modifier.size(arrowSlot), contentAlignment = Alignment.Center) {
                if (currentPage < pageCount - 1) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = nextLabel,
                        tint = tintColor,
                        modifier = Modifier
                            .size(arrowSlot)
                            .clickable { onNext() }
                    )
                }
            }
        }
    }

    @Composable
    fun PageIndicator(
        currentPage: Int,
        pageCount: Int,
        modifier: Modifier = Modifier
    ) {
        // 只有一页时页码点没有信息量，直接不显示（与 PagedNavRow 的约定一致）
        if (pageCount <= 1) return
        val tintColor = SettingsTheme.color.settings
        val screenScale = rememberScreenScale()
        val islandsShape = LocalPrefs.current?.textIslandsShape ?: 0

        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            for (i in 0 until pageCount) {
                val selected = i == currentPage
                val slotSize = 12.dp.scaled(screenScale)
                val dotSize = if (selected) slotSize else slotSize * (10f / 12f)
                val dotShape = ShapeHelper.getPageDotShape(islandsShape, dotSize)
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp.scaled(screenScale))
                        .size(slotSize),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(dotSize)
                            .background(if (selected) tintColor else Color.Transparent, dotShape)
                            .then(
                                if (selected) Modifier
                                else Modifier.border(dotSize * (1f / 6f), tintColor, dotShape)
                            )
                    )
                }
            }
        }
    }

    @Composable
    fun PageHeader(
        @DrawableRes iconRes: Int,
        title: String,
        iconSize: Dp = 24.dp,
        onClick: () -> Unit = {},
        showStatusBar: Boolean = false,
        modifier: Modifier = Modifier,
        pageIndicator: (@Composable () -> Unit)? = null,
        titleFontSize: TextUnit = TextUnit.Unspecified,
        backHighlighted: Boolean = false,
        /**
         * 键盘 PageUp/PageDown 翻页。页头进页就会主动抢焦点（好让 OK 直接返回上级），
         * 这时正文那层的按键处理器收不到键——实测「刚进设置页按 PAGE_DOWN 没反应，
         * 先按一次 DPAD_DOWN 才生效」。所以页头也要挂同一套键，共用正文的翻页回调。
         */
        onPageMove: ((Int) -> Unit)? = null,
        /**
         * 「在列表顶端按 UP」该做的事：跳到本页最后一条。
         * Compose 只在向前遍历（DOWN/Next）时给初始焦点，向后没有「从末尾进入」这条，
         * 所以刚进页面按 UP 原本是彻底没反应。页头持有焦点时键落在页头这边，
         * 必须由页头把 UP 转交给正文，否则键盘用户永远够不到列表末尾。
         */
        onMoveToEnd: (() -> Unit)? = null
    ) {
        val prefTextColor = Theme.colors.text
        val prefBackgroundColor = Theme.colors.background
        val screenScale = rememberScreenScale()
        Row(
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (onPageMove != null) {
                        Modifier.onPageKeys(
                            onPrevious = { onPageMove(-1) },
                            onNext = { onPageMove(1) }
                        )
                    } else Modifier
                )
                .then(
                    if (onMoveToEnd != null) {
                        Modifier.onPreviewKeyEvent { event ->
                            if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                                event.type == KeyEventType.KeyDown
                            ) {
                                onMoveToEnd()
                                true
                            } else {
                                false
                            }
                        }
                    } else Modifier
                )
                .padding(top = 12.dp)
                .padding(horizontal = SettingsTheme.color.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val backFocusRequester = remember { FocusRequester() }
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = title,
                colorFilter = ColorFilter.tint(if (backHighlighted) prefBackgroundColor else SettingsTheme.color.image),
                modifier = Modifier
                    .focusRequester(backFocusRequester)
                    .focusable()
                    .pillHighlight(
                        isHighlighted = backHighlighted,
                        color = prefTextColor,
                        outerHorizontal = 8.dp,
                        outerVertical = 8.dp
                    )
                    .clickable(onClick = {
                        try { VibrationHelper.trigger(VibrationHelper.Effect.CLICK) } catch (_: Exception) {}
                        onClick()
                    })
                    .size(iconSize.scaled(screenScale))
            )
            LaunchedEffect(Unit) {
                backFocusRequester.requestFocus()
            }

            Spacer(modifier = Modifier.width(16.dp.scaled(screenScale)))

            // Title left-aligned to match setting items
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                SettingsHeaderTitle(
                    text = title,
                    fontSize = titleFontSize,
                )
            }

            // Page indicator right-aligned, no extra padding
            if (pageIndicator != null) {
                pageIndicator()
            }
        }
    }

    @Composable
    fun SettingsHomeItem(
        title: String,
        imageVector: ImageVector? = null,
        @DrawableRes iconRes: Int? = null,
        description: String? = null,
        value: String? = null,
        valueFontSize: TextUnit = TextUnit.Unspecified,
        onClick: () -> Unit = {},
        titleFontSize: TextUnit = TextUnit.Unspecified,
        descriptionColor: Color? = null,
        modifier: Modifier = Modifier,
        fillWidth: Boolean = true,
        horizontalPadding: Dp = SettingsTheme.color.horizontalPadding,
        verticalPadding: Dp = 10.dp.scaled(rememberScreenScale()),
        focusVerticalPadding: Dp = 12.dp.scaled(rememberScreenScale()),
        iconPadding: Dp = 0.dp
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isFocused = interactionSource.collectIsFocusedAsState().value
        val isHighlighted = isFocused
        val prefTextColor = Theme.colors.text
        val prefBackgroundColor = Theme.colors.background
        val screenScale = rememberScreenScale()
        Row(
            modifier = modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .padding(horizontal = horizontalPadding)
                .padding(vertical = verticalPadding)
                .pageEdge()   // 登记进本页焦点边界：上下键的首尾回环靠它，回报由同一节点同步给出
                .pillHighlight(isHighlighted, 6.dp, prefTextColor, outerVertical = focusVerticalPadding)
                .clickable(
                    onClick = {
                        try { VibrationHelper.trigger(VibrationHelper.Effect.CLICK) } catch (_: Exception) {}
                        onClick()
                    },
                    interactionSource = interactionSource,
                    indication = null
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val iconBoxSize = 24.dp.scaled(screenScale)
            if (imageVector != null) {
                Image(
                    imageVector,
                    contentDescription = title,
                    colorFilter = ColorFilter.tint(if (isHighlighted) prefBackgroundColor else SettingsTheme.color.image),
                    modifier = Modifier
                        .size(iconBoxSize)
                        .padding(iconPadding)
                )
                Spacer(
                    modifier = Modifier
                        .width(16.dp.scaled(screenScale))
                )
            } else if (iconRes != null) {
                Image(
                    painter = painterResource(id = iconRes),
                    contentDescription = title,
                    colorFilter = ColorFilter.tint(if (isHighlighted) prefBackgroundColor else SettingsTheme.color.image),
                    modifier = Modifier
                        .size(iconBoxSize)
                        .padding(iconPadding)
                )
                Spacer(modifier = Modifier.width(16.dp.scaled(screenScale)))
            }
            Column(modifier = if (fillWidth) Modifier.weight(1f) else Modifier) {
                val descBaseFontSize = if (titleFontSize.isSpecified) {
                    (titleFontSize.value * 0.65f).sp
                } else {
                    (SettingsTheme.typography.title.fontSize.value * 0.65f).sp
                }
                Text(
                    text = title,
                    style = SettingsTheme.typography.title,
                    fontSize = titleFontSize,
                    color = if (isHighlighted) prefBackgroundColor else SettingsTheme.typography.title.color
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = SettingsTheme.typography.title,
                        fontSize = descBaseFontSize,
                        color = if (isHighlighted) {
                            prefBackgroundColor
                        } else {
                            descriptionColor ?: SettingsTheme.typography.title.color
                        }
                    )
                }
            }

            if (value != null) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = value,
                    style = SettingsTheme.typography.item,
                    fontSize = if (valueFontSize.isSpecified) valueFontSize else titleFontSize,
                    color = if (isHighlighted) prefBackgroundColor else SettingsTheme.typography.item.color
                )
            }
        }
    }

    @Composable
    fun SettingsTitle(
        text: String,
        modifier: Modifier = Modifier,
        fontSize: TextUnit = TextUnit.Unspecified,
        horizontalPadding: Dp = SettingsTheme.color.horizontalPadding,
        verticalPadding: Dp = 8.dp.scaled(rememberScreenScale())
    ) {
        val effectiveFontSize = if (fontSize.isSpecified) {
            (fontSize.value * 0.8).sp
        } else {
            SettingsTheme.typography.header.fontSize
        }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "• ${text.uppercase()}",
                style = SettingsTheme.typography.header,
                fontSize = effectiveFontSize,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = horizontalPadding)
            )

            Box(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                DashedSeparator()
            }
        }
    }

    @Composable
    fun SettingsHeaderTitle(
        text: String,
        modifier: Modifier = Modifier,
        fontSize: TextUnit = TextUnit.Unspecified
    ) {
        val screenScale = rememberScreenScale()
        val effectiveFontSize = if (fontSize.isSpecified) {
            (fontSize.value * 0.8).sp
        } else {
            14.sp.scaled(screenScale)
        }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp.scaled(screenScale)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text.uppercase(),
                style = SettingsTheme.typography.header,
                fontSize = effectiveFontSize,
                fontWeight = FontWeight.Bold,
            )
        }
    }


    @Composable
    fun CustomToggleSwitch(
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        tint: Color = SettingsTheme.typography.title.color
    ) {
        val screenScale = rememberScreenScale()
        val circleDiameter = 12.dp.scaled(screenScale)
        val circleBorder = 2.5.dp.scaled(screenScale)
        val lineWidth = 12.dp.scaled(screenScale)
        val lineHeight = 2.5.dp.scaled(screenScale)

        val switchColor = tint

        Row(
            modifier = Modifier
                .clickable {
                    try { VibrationHelper.trigger(VibrationHelper.Effect.CLICK) } catch (_: Exception) {}
                    onCheckedChange(!checked)
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!checked) {
                Box(
                    modifier = Modifier
                        .size(circleDiameter)
                        .border(circleBorder, switchColor, CircleShape)
                )
                Box(
                    modifier = Modifier
                        .width(lineWidth)
                        .height(lineHeight)
                        .background(switchColor)
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(lineWidth)
                        .height(lineHeight)
                        .background(switchColor)
                )
                Box(
                    modifier = Modifier
                        .size(circleDiameter)
                        .background(switchColor, CircleShape)
                )
            }
        }
    }
    @Composable
    fun AlignmentIcon(alignment: Int, tint: Color = Theme.colors.text, size: Dp = 18.dp) {
        val screenScale = rememberScreenScale()
        val s = size.scaled(screenScale)
        val lineH = 2.5.dp.scaled(screenScale)
        val gap = 3.dp.scaled(screenScale)
        val widths = listOf(1f, 0.5f, 0.85f)
        val hAlign = when (alignment) {
            0 -> Alignment.Start
            2 -> Alignment.End
            else -> Alignment.CenterHorizontally
        }
        Box(modifier = Modifier.size(s), contentAlignment = Alignment.Center) {
            Column(
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalAlignment = hAlign
            ) {
                widths.forEach { fraction ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(lineH)
                            .background(tint)
                    )
                }
            }
        }
    }

    @Composable
    fun SettingsSwitch(
        text: String,
        fontSize: TextUnit = TextUnit.Unspecified,
        defaultState: Boolean = false,
        enabled: Boolean = true,
        modifier: Modifier = Modifier,
        description: String? = null,
        horizontalPadding: Dp = SettingsTheme.color.horizontalPadding,
        verticalPadding: Dp = 10.dp.scaled(rememberScreenScale()),
        onCheckedChange: (Boolean) -> Unit
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isFocused = interactionSource.collectIsFocusedAsState().value
        val isHighlighted = isFocused
        val prefTextColor = Theme.colors.text
        val prefBackgroundColor = Theme.colors.background
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .padding(vertical = verticalPadding)
                .pageEdge(canFocus = enabled)   // 禁用行不参与上下键遍历，否则焦点会停在灰行上
                .pillHighlight(isHighlighted, 6.dp, prefTextColor)
                .clickable(
                    enabled = enabled,
                    onClick = {
                        try { VibrationHelper.trigger(VibrationHelper.Effect.SELECT) } catch (_: Exception) {}
                        onCheckedChange(!defaultState)
                    },
                    interactionSource = interactionSource,
                    indication = null
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = text,
                        style = if (fontSize.isSpecified) {
                            SettingsTheme.typography.title.copy(fontSize = fontSize)
                        } else SettingsTheme.typography.title,
                        color = if (!enabled) prefTextColor.copy(alpha = 0.4f) else if (isHighlighted) prefBackgroundColor else SettingsTheme.typography.title.color
                    )
                    if (description != null) {
                        val descFontSize = if (fontSize.isSpecified) (fontSize.value * 0.65f).sp else (SettingsTheme.typography.title.fontSize.value * 0.65f).sp
                        Text(
                            text = description,
                            style = SettingsTheme.typography.title,
                            fontSize = descFontSize,
                            color = if (!enabled) prefTextColor.copy(alpha = 0.4f) else if (isHighlighted) prefBackgroundColor else SettingsTheme.typography.title.color
                        )
                    }
                }
                CustomToggleSwitch(
                    checked = defaultState,
                    onCheckedChange = { if (enabled) onCheckedChange(it) },
                    tint = if (!enabled) prefTextColor.copy(alpha = 0.4f) else if (isHighlighted) prefBackgroundColor else SettingsTheme.typography.title.color
                )
            }
        }
    }

    @Composable
    fun SettingsSelect(
        title: String,
        option: String,
        fontSize: TextUnit = TextUnit.Unspecified, // Default font size for the title
        optionFontSize: TextUnit = TextUnit.Unspecified, // Optional override for the value on the right
        fontColor: Color = SettingsTheme.typography.title.color,
        enabled: Boolean = true,
        description: String? = null,
        optionAlignment: Int? = null,
        horizontalPadding: Dp = SettingsTheme.color.horizontalPadding,
        verticalPadding: Dp = 10.dp.scaled(rememberScreenScale()),
        onClick: () -> Unit = {},
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isFocused = interactionSource.collectIsFocusedAsState().value
        val isHighlighted = isFocused
        val prefTextColor = Theme.colors.text
        val prefBackgroundColor = Theme.colors.background
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .padding(vertical = verticalPadding)
                .pageEdge(canFocus = enabled)   // 禁用行不参与上下键遍历，否则焦点会停在灰行上
                .pillHighlight(isHighlighted, 6.dp, prefTextColor)
                .clickable(
                    enabled = enabled,
                    onClick = {
                        try { VibrationHelper.trigger(VibrationHelper.Effect.CLICK) } catch (_: Exception) {}
                        onClick()
                    },
                    interactionSource = interactionSource,
                    indication = null
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val textColor = if (!enabled) prefTextColor.copy(alpha = 0.4f) else if (isHighlighted) prefBackgroundColor else SettingsTheme.typography.title.color
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = SettingsTheme.typography.title,
                        fontSize = fontSize,
                        color = textColor
                    )
                    if (description != null) {
                        val descFontSize = if (fontSize.isSpecified) (fontSize.value * 0.65f).sp else (SettingsTheme.typography.title.fontSize.value * 0.65f).sp
                        Text(
                            text = description,
                            style = SettingsTheme.typography.title,
                            fontSize = descFontSize,
                            color = textColor
                        )
                    }
                }

                if (optionAlignment != null) {
                    AlignmentIcon(
                        alignment = optionAlignment,
                        tint = if (!enabled) prefTextColor.copy(alpha = 0.4f) else if (isHighlighted) prefBackgroundColor else prefTextColor
                    )
                } else {
                    Text(
                        text = option,
                        style = SettingsTheme.typography.item,
                        fontSize = if (optionFontSize.isSpecified) optionFontSize else fontSize,
                        color = if (!enabled) prefTextColor.copy(alpha = 0.4f) else if (isHighlighted) prefBackgroundColor else fontColor
                    )
                }
            }
        }
    }

    @Composable
    fun SettingsStepper(
        title: String,
        value: Int,
        minValue: Int,
        maxValue: Int,
        fontSize: TextUnit = TextUnit.Unspecified,
        description: String? = null,
        horizontalPadding: Dp = SettingsTheme.color.horizontalPadding,
        verticalPadding: Dp = 10.dp.scaled(rememberScreenScale()),
        onValueChange: (Int) -> Unit
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isFocused = interactionSource.collectIsFocusedAsState().value
        val isHighlighted = isFocused
        val prefTextColor = Theme.colors.text
        val prefBackgroundColor = Theme.colors.background
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .padding(vertical = verticalPadding)
                .pageEdge()   // 登记进本页焦点边界：上下键的首尾回环靠它，回报由同一节点同步给出
                .pillHighlight(isHighlighted, 6.dp, prefTextColor)
                .clickable(
                    onClick = {},
                    interactionSource = interactionSource,
                    indication = null
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val textColor = if (isHighlighted) prefBackgroundColor else SettingsTheme.typography.title.color
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = SettingsTheme.typography.title,
                        fontSize = fontSize,
                        color = textColor
                    )
                    if (description != null) {
                        val descFontSize = if (fontSize.isSpecified) (fontSize.value * 0.65f).sp else (SettingsTheme.typography.title.fontSize.value * 0.65f).sp
                        Text(
                            text = description,
                            style = SettingsTheme.typography.title,
                            fontSize = descFontSize,
                            color = textColor
                        )
                    }
                }
                // 右侧 − 数值 +：+ 与其它设置项的右侧控件右对齐
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.widthIn(min = 108.dp)
                ) {
                    val activeColor = if (isHighlighted) prefBackgroundColor else prefTextColor
                    Text(
                        text = "−",
                        style = SettingsTheme.typography.item,
                        fontSize = fontSize,
                        color = if (value > minValue) activeColor else activeColor.copy(alpha = 0.3f),
                        modifier = Modifier
                            .clickable(enabled = value > minValue) { onValueChange(value - 1) }
                            .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                    )
                    Text(
                        text = value.toString(),
                        style = SettingsTheme.typography.item,
                        fontSize = fontSize,
                        color = activeColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 28.dp)
                    )
                    Text(
                        text = "+",
                        style = SettingsTheme.typography.item,
                        fontSize = fontSize,
                        color = if (value < maxValue) activeColor else activeColor.copy(alpha = 0.3f),
                        modifier = Modifier
                            .clickable(enabled = value < maxValue) { onValueChange(value + 1) }
                            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun SettingsSelectWithDualColorPreview(
        title: String,
        color1: Color,
        color2: Color,
        fontSize: TextUnit = TextUnit.Unspecified,
        enabled: Boolean = true,
        description: String? = null,
        horizontalPadding: Dp = SettingsTheme.color.horizontalPadding,
        verticalPadding: Dp = 10.dp.scaled(rememberScreenScale()),
        onClick: () -> Unit = {},
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isFocused = interactionSource.collectIsFocusedAsState().value
        val isHighlighted = isFocused
        val prefTextColor = Theme.colors.text
        val prefBackgroundColor = Theme.colors.background
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .padding(vertical = verticalPadding)
                .pageEdge(canFocus = enabled)   // 禁用行不参与上下键遍历，否则焦点会停在灰行上
                .pillHighlight(isHighlighted, 6.dp, prefTextColor)
                .clickable(
                    enabled = enabled,
                    onClick = {
                        try { VibrationHelper.trigger(VibrationHelper.Effect.CLICK) } catch (_: Exception) {}
                        onClick()
                    },
                    interactionSource = interactionSource,
                    indication = null
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = SettingsTheme.typography.title,
                        fontSize = fontSize,
                        color = if (!enabled) prefTextColor.copy(alpha = 0.4f) else if (isHighlighted) prefBackgroundColor else SettingsTheme.typography.title.color
                    )
                    if (description != null) {
                        val descFontSize = if (fontSize.isSpecified) (fontSize.value * 0.65f).sp else (SettingsTheme.typography.title.fontSize.value * 0.65f).sp
                        Text(
                            text = description,
                            style = SettingsTheme.typography.title,
                            fontSize = descFontSize,
                            color = if (isHighlighted) prefBackgroundColor else SettingsTheme.typography.title.color
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .border(1.5.dp, if (isHighlighted) prefBackgroundColor else prefTextColor, CircleShape)
                            .padding(1.5.dp)
                            .border(1.dp, Color.White, CircleShape)
                            .background(color1, CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .border(1.5.dp, if (isHighlighted) prefBackgroundColor else prefTextColor, CircleShape)
                            .padding(1.5.dp)
                            .border(1.dp, Color.White, CircleShape)
                            .background(color2, CircleShape)
                    )
                }
            }
        }
    }

    @Composable
    fun DashedSeparator() {
        val borderColor = SettingsTheme.color.border
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .alpha(0.85f)
                .padding(horizontal = SettingsTheme.color.horizontalPadding) // already present
        ) {
            val dashWidth = 4f
            val gapWidth = 4f
            var x = 0f
            val y = size.height / 2
            while (x < size.width) {
                drawLine(
                    color = borderColor,
                    start = Offset(x, y),
                    end = Offset((x + dashWidth).coerceAtMost(size.width), y),
                    strokeWidth = size.height
                )
                x += dashWidth + gapWidth
            }
        }
    }
}
