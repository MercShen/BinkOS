package com.github.gezimos.inkos.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.github.gezimos.inkos.style.Theme
import com.github.gezimos.inkos.style.rememberScreenScale
import com.github.gezimos.inkos.style.scaled
import kotlinx.coroutines.delay

/**
 * 设置类弹窗里的药丸搜索框（应用抽屉顶部那行搜索**不用**它，抽屉保持上游的"极简行"实现）。
 *
 * 视觉：
 *   ┌────────────────────────────────────────┐
 *   │              搜索                       │   ← 空态：半透明"搜索"居中
 *   └────────────────────────────────────────┘
 *   ┌────────────────────────────────────────┐
 *   │ we                                    ✕ │   ← 有内容：左对齐文本 + 右侧清除
 *   └────────────────────────────────────────┘
 *
 * 交互约定（都是踩过坑的）：
 * 1. 整颗药丸可点，点击即聚焦；内边距区域也算命中。
 * 2. 聚焦之后延迟一帧再调 keyboardController.show()。同一帧 `requestFocus(); show()`
 *    时输入会话还没建立，show() 会被丢掉。
 * 3. FocusRequester 必须在函数体里 remember。写成默认参数里的
 *    `remember { FocusRequester() }` 会拿到不稳定实例，requestFocus() 抛
 *    IllegalStateException 被 catch 吞掉 —— 现象是"点空白处没反应"。
 * 4. 光标闪烁关闭：墨水屏上闪烁只会留残影。
 */
@Composable
fun InkSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    fontSize: TextUnit = TextUnit.Unspecified,
    fontFamily: FontFamily? = null,
    imeAction: ImeAction = ImeAction.Search,
    onSearch: (() -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    onFocusedChange: ((Boolean) -> Unit)? = null
) {
    val screenScale = rememberScreenScale()
    val keyboard = LocalSoftwareKeyboardController.current
    val internalFocusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val clearInteractionSource = remember { MutableInteractionSource() }

    // 内部保持 TextFieldValue（光标/组词状态），对外只暴露纯文本。
    val state = remember { mutableStateOf(TextFieldValue(value)) }
    LaunchedEffect(value) {
        if (state.value.text != value) state.value = TextFieldValue(value)
    }
    val latestOnValueChange = rememberUpdatedState(onValueChange)

    LaunchedEffect(isFocused) {
        if (isFocused) {
            delay(40)
            try { keyboard?.show() } catch (_: Exception) {}
        }
    }

    val textColor = Theme.colors.text
    val basicTextStyle = TextStyle(
        color = textColor,
        fontSize = fontSize,
        fontFamily = fontFamily,
        textAlign = TextAlign.Start
    )

    CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(
                    top = 14.dp.scaled(screenScale),
                    bottom = 10.dp.scaled(screenScale)
                )
                .height(48.dp.scaled(screenScale))
                .border(
                    1.5.dp,
                    textColor,
                    androidx.compose.foundation.shape.RoundedCornerShape(percent = 50)
                )
                .clickable(
                    indication = null,
                    interactionSource = interactionSource
                ) {
                    onTap?.invoke()
                    try { internalFocusRequester.requestFocus() } catch (_: Exception) {}
                    if (isFocused) {
                        try { keyboard?.show() } catch (_: Exception) {}
                    }
                }
                .padding(horizontal = 18.dp.scaled(screenScale)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                // 空态占位：居中、半透明；用户键入任何内容后消失（不是聚焦后消失）。
                if (state.value.text.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        color = textColor.copy(alpha = 0.45f),
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                BasicTextField(
                    value = state.value,
                    onValueChange = { newValue ->
                        state.value = newValue
                        latestOnValueChange.value(newValue.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(internalFocusRequester)
                        .onFocusChanged {
                            if (it.isFocused != isFocused) {
                                isFocused = it.isFocused
                                onFocusedChange?.invoke(it.isFocused)
                            }
                        },
                    textStyle = basicTextStyle,
                    singleLine = true,
                    cursorBrush = SolidColor(textColor),
                    keyboardOptions = KeyboardOptions(imeAction = imeAction),
                    keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() })
                )
            }
            // 有内容时右侧出现小叉：点一下清空（保持聚焦，键盘不收起）。
            if (state.value.text.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(32.dp.scaled(screenScale))
                        .clickable(
                            indication = null,
                            interactionSource = clearInteractionSource
                        ) {
                            state.value = TextFieldValue("")
                            latestOnValueChange.value("")
                            try { internalFocusRequester.requestFocus() } catch (_: Exception) {}
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(18.dp.scaled(screenScale))
                    )
                }
            }
        }
    }
}
