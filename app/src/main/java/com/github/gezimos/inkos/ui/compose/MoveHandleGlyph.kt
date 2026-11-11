package com.github.gezimos.inkos.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 编辑模式 App 区域拖动十字手柄的不同画法。
 * style: 0 原样式 / 1 实心箭头 / 2 十字准星 / 3 四角框 / 4 四向握把 / 5 圆环十字
 */
@Composable
fun MoveHandleGlyph(
    style: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        // 线宽按尺寸等比缩放：36dp 实际尺寸下仍是 2.5dp
        val stroke = size.minDimension * 0.0694f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f
        val head = size.minDimension * 0.16f
        val pad = size.minDimension * 0.06f
        val round = StrokeCap.Round
        when (style) {
            1 -> {
                val arm = r - pad - head * 0.4f
                drawLine(color, Offset(cx - arm, cy), Offset(cx + arm, cy), stroke * 1.4f, round)
                drawLine(color, Offset(cx, cy - arm), Offset(cx, cy + arm), stroke * 1.4f, round)
                fun arrow(tip: Offset, d1: Offset, d2: Offset) {
                    val path = Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(d1.x, d1.y)
                        lineTo(d2.x, d2.y)
                        close()
                    }
                    drawPath(path, color)
                }
                val half = head * 0.95f
                arrow(Offset(cx, pad), Offset(cx - half, pad + head), Offset(cx + half, pad + head))
                arrow(Offset(cx, size.height - pad), Offset(cx - half, size.height - pad - head), Offset(cx + half, size.height - pad - head))
                arrow(Offset(pad, cy), Offset(pad + head, cy - half), Offset(pad + head, cy + half))
                arrow(Offset(size.width - pad, cy), Offset(size.width - pad - head, cy - half), Offset(size.width - pad - head, cy + half))
            }
            2 -> {
                val inset = pad + head * 0.6f
                drawLine(color, Offset(cx, pad), Offset(cx, size.height - pad), stroke * 0.8f, round)
                drawLine(color, Offset(pad, cy), Offset(size.width - pad, cy), stroke * 0.8f, round)
                drawCircle(color, inset * 0.45f, Offset(cx, cy))
            }
            3 -> {
                val arm = r * 0.62f
                val corner = pad + head * 0.3f
                val corners = listOf(
                    Offset(corner, corner) to Offset(1f, 1f),
                    Offset(size.width - corner, corner) to Offset(-1f, 1f),
                    Offset(corner, size.height - corner) to Offset(1f, -1f),
                    Offset(size.width - corner, size.height - corner) to Offset(-1f, -1f)
                )
                corners.forEach { (p, dir) ->
                    drawLine(color, p, Offset(p.x + dir.x * arm, p.y), stroke, round)
                    drawLine(color, p, Offset(p.x, p.y + dir.y * arm), stroke, round)
                }
            }
            4 -> {
                val bar = r * 0.5f
                val gap = r * 0.14f
                val barStroke = stroke * 1.8f
                drawLine(color, Offset(cx - gap - bar, cy), Offset(cx - gap, cy), barStroke, round)
                drawLine(color, Offset(cx + gap, cy), Offset(cx + gap + bar, cy), barStroke, round)
                drawLine(color, Offset(cx, cy - gap - bar), Offset(cx, cy - gap), barStroke, round)
                drawLine(color, Offset(cx, cy + gap), Offset(cx, cy + gap + bar), barStroke, round)
            }
            5 -> {
                drawCircle(color, radius = r - pad - stroke, style = Stroke(width = stroke))
                drawLine(color, Offset(cx, cy - r * 0.42f), Offset(cx, cy + r * 0.42f), stroke * 0.9f, round)
                drawLine(color, Offset(cx - r * 0.42f, cy), Offset(cx + r * 0.42f, cy), stroke * 0.9f, round)
            }
            else -> {
                val lineStart = pad + head
                drawLine(color, Offset(pad + lineStart, cy), Offset(size.width - pad - lineStart, cy), stroke, round)
                drawLine(color, Offset(cx, pad + lineStart), Offset(cx, size.height - pad - lineStart), stroke, round)
                drawLine(color, Offset(cx, pad), Offset(cx - head, pad + head), stroke, round)
                drawLine(color, Offset(cx, pad), Offset(cx + head, pad + head), stroke, round)
                drawLine(color, Offset(cx, size.height - pad), Offset(cx - head, size.height - pad - head), stroke, round)
                drawLine(color, Offset(cx, size.height - pad), Offset(cx + head, size.height - pad - head), stroke, round)
                drawLine(color, Offset(pad, cy), Offset(pad + head, cy - head), stroke, round)
                drawLine(color, Offset(pad, cy), Offset(pad + head, cy + head), stroke, round)
                drawLine(color, Offset(size.width - pad, cy), Offset(size.width - pad - head, cy - head), stroke, round)
                drawLine(color, Offset(size.width - pad, cy), Offset(size.width - pad - head, cy + head), stroke, round)
            }
        }
    }
}
