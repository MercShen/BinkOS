package com.github.gezimos.inkos.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.gezimos.inkos.R
import com.github.gezimos.inkos.style.SettingsTheme
import com.github.gezimos.inkos.style.Theme
import com.github.gezimos.inkos.style.rememberScreenScale
import com.github.gezimos.inkos.style.scaled
import kotlinx.coroutines.delay

/**
 * Manual frame ticker on purpose: the B651 can have animator_duration_scale=0,
 * so Compose's InfiniteTransition stays frozen. The original pinch guide uses
 * two vector drawables swapped on a delay; these previews use the same
 * approach, but draw simple arrows/circle directly.
 */
@Composable
fun RefreshAnimationGuideContent(modifier: Modifier = Modifier) {
    val screenScale = rememberScreenScale()
    val textColor = Theme.colors.text
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60)
            frame++
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp.scaled(screenScale))
    ) {
        PreviewRow(
            label = stringResource(R.string.option_page_turn_directional),
            textColor = textColor,
            screenScale = screenScale,
            preview = { DirectionalPreview(frame, textColor, screenScale) }
        )
        PreviewRow(
            label = stringResource(R.string.option_page_turn_ripple),
            textColor = textColor,
            screenScale = screenScale,
            preview = { RipplePreview(frame, textColor, screenScale) }
        )
    }
}

@Composable
private fun DirectionalPreview(
    frame: Int,
    textColor: androidx.compose.ui.graphics.Color,
    screenScale: Float
) {
    val cycle = (frame % 30) / 30f
    val leftPhase = cycle < 0.5f
    val progress = if (leftPhase) cycle * 2f else (cycle - 0.5f) * 2f
    val move = ((progress - 0.12f) / 0.68f).coerceIn(0f, 1f)
    if (progress < 0.98f) {
        val travel = 34.dp.scaled(screenScale) * move
        Icon(
            imageVector = if (leftPhase) Icons.AutoMirrored.Rounded.ArrowBack
            else Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier
                .size(26.dp.scaled(screenScale))
                .offset(x = if (leftPhase) -travel else travel)
        )
    }
}

@Composable
private fun RipplePreview(
    frame: Int,
    textColor: androidx.compose.ui.graphics.Color,
    screenScale: Float
) {
    val cycle = (frame % 30) / 30f
    val triangle = if (cycle < 0.5f) cycle * 2f else (1f - cycle) * 2f
    val scale = 1f - 0.55f * triangle
    Canvas(modifier = Modifier.size(44.dp.scaled(screenScale))) {
        val stroke = 2.dp.toPx()
        drawCircle(
            color = textColor,
            radius = (size.minDimension / 2f - stroke) * scale,
            style = Stroke(width = stroke)
        )
    }
}

@Composable
private fun PreviewRow(
    label: String,
    textColor: androidx.compose.ui.graphics.Color,
    screenScale: Float,
    preview: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp.scaled(screenScale))
    ) {
        Text(
            text = label,
            style = SettingsTheme.typography.caption,
            color = textColor,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(56.dp.scaled(screenScale)),
            contentAlignment = Alignment.Center
        ) {
            preview()
        }
    }
}
