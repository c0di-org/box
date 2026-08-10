package dev.localagent.workstation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.localagent.workstation.agent.HarnessDescriptor
import dev.localagent.workstation.agent.HarnessMarkKind
import kotlin.math.cos
import kotlin.math.sin

/** The Box cube. */
@Composable
fun BoxMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = "Box" },
    ) {
        drawRoundRect(color = BoxInk, cornerRadius = CornerRadius(9.dp.toPx()))
        val stroke = 1.8.dp.toPx()
        val left = Offset(this.size.width * 0.26f, this.size.height * 0.36f)
        val top = Offset(this.size.width * 0.50f, this.size.height * 0.23f)
        val right = Offset(this.size.width * 0.74f, this.size.height * 0.36f)
        val center = Offset(this.size.width * 0.50f, this.size.height * 0.50f)
        val bottomLeft = Offset(this.size.width * 0.26f, this.size.height * 0.63f)
        val bottom = Offset(this.size.width * 0.50f, this.size.height * 0.77f)
        val bottomRight = Offset(this.size.width * 0.74f, this.size.height * 0.63f)
        val ink = BoxGreenLight
        listOf(
            left to top,
            top to right,
            left to center,
            right to center,
            center to bottom,
            left to bottomLeft,
            bottomLeft to bottom,
            right to bottomRight,
            bottomRight to bottom,
        ).forEach { (from, to) ->
            drawLine(ink, from, to, strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

/**
 * A geometric mark per harness. Deliberately abstract shapes rather than vendor artwork — Box
 * wraps whatever CLI the user installed and does not ship anyone's trademark.
 */
@Composable
fun HarnessMark(
    harness: HarnessDescriptor,
    size: Dp = 30.dp,
    modifier: Modifier = Modifier,
) {
    val accent = harnessAccent(harness.id)
    Surface(
        modifier = modifier.size(size).semantics { contentDescription = harness.name },
        shape = RoundedCornerShape(size / 3.4f),
        color = accent.copy(alpha = 0.16f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size * 0.58f)) {
                val radius = this.size.minDimension / 2f
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val stroke = (size.toPx() * 0.055f).coerceAtLeast(1.2f)
                when (harness.mark) {
                    HarnessMarkKind.Burst -> repeat(10) { index ->
                        val angle = (Math.PI * 2 * index / 10).toFloat()
                        val inner = 0.30f
                        drawLine(
                            color = accent,
                            start = center + Offset(cos(angle) * radius * inner, sin(angle) * radius * inner),
                            end = center + Offset(cos(angle) * radius, sin(angle) * radius),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }

                    HarnessMarkKind.Knot -> {
                        repeat(3) { index ->
                            val angle = (Math.PI * 2 * index / 3).toFloat()
                            val offset = Offset(cos(angle) * radius * 0.26f, sin(angle) * radius * 0.26f)
                            drawCircle(
                                color = accent,
                                radius = radius * 0.66f,
                                center = center + offset,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                            )
                        }
                    }

                    HarnessMarkKind.Prism -> {
                        val top = center + Offset(0f, -radius)
                        val bottomLeft = center + Offset(-radius * 0.88f, radius * 0.62f)
                        val bottomRight = center + Offset(radius * 0.88f, radius * 0.62f)
                        listOf(
                            top to bottomLeft,
                            top to bottomRight,
                            bottomLeft to bottomRight,
                            top to center,
                        ).forEach { (from, to) ->
                            drawLine(accent, from, to, strokeWidth = stroke, cap = StrokeCap.Round)
                        }
                    }

                    HarnessMarkKind.Generic -> drawCircle(
                        color = accent,
                        radius = radius * 0.8f,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                    )
                }
            }
        }
    }
}

/** Accent per harness id, used for the mark and nothing else. */
fun harnessAccent(harnessId: String): Color = when (harnessId) {
    "claude" -> Color(0xFFD97757)
    "chatgpt" -> Color(0xFF56C08A)
    "cursor" -> Color(0xFFB9C2BC)
    else -> Color(0xFF8CE3B5)
}

/** A small filled dot, used for live-status indicators. */
@Composable
fun StatusDot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}
