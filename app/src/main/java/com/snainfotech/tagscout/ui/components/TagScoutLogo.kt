package com.snainfotech.tagscout.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.theme.Amber
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.Primary

/**
 * TagScout brand mark: a navy circle with "TS", a small red locate dot, and
 * three concentric amber "signal wave" arcs radiating from the right.
 *
 * Geometry is a direct port of the design handoff (viewBox 0 0 42 36):
 *   - navy circle at (15, 18) r13, "TS" centered inside
 *   - red dot at (28, 18) r1.6
 *   - three amber arcs at radii 6 / 9 / 12, fading out
 *
 * [size] is the height; width is derived from the original 42:36 aspect ratio.
 */
@Composable
fun TagScoutLogo(
    size: Dp,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val aspect = 42f / 36f

    Canvas(modifier = modifier.size(width = size * aspect, height = size)) {
        // Scale factor from the 42x36 design space to actual pixels.
        val s = this.size.height / 36f
        fun pt(x: Float, y: Float) = Offset(x * s, y * s)

        // Navy circle
        drawCircle(color = Primary, radius = 13f * s, center = pt(15f, 18f))

        // Amber signal-wave arcs (stroked arc segments, centered on the dot)
        fun signalArc(radius: Float, alpha: Float) {
            val d = radius * 2f
            drawArc(
                color = Amber.copy(alpha = alpha),
                startAngle = -60f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = pt(28f - radius, 18f - radius),
                size = Size(d * s, d * s),
                style = Stroke(width = 1.8f * s)
            )
        }
        signalArc(6f, 1.0f)
        signalArc(9f, 0.75f)
        signalArc(12f, 0.5f)

        // Red locate dot
        drawCircle(color = ErrorRed, radius = 1.6f * s, center = pt(28f, 18f))

        // "TS" text centered in the navy circle
        val fontSizeSp = (13f * s / density).sp
        val textLayout = textMeasurer.measure(
            text = "TS",
            style = TextStyle(
                color = Color.White,
                fontSize = fontSizeSp,
                fontWeight = FontWeight.Bold
            )
        )
        val c = pt(15f, 18f)
        drawText(
            textLayoutResult = textLayout,
            topLeft = Offset(
                c.x - textLayout.size.width / 2f,
                c.y - textLayout.size.height / 2f
            )
        )
    }
}