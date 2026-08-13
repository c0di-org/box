package dev.localagent.workstation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.localagent.workstation.agent.HarnessDescriptor
import dev.localagent.workstation.agent.HarnessMarkKind
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Box cube: the app icon, drawn at whatever size the screen wants it.
 *
 * The artwork is three lit faces with a green seam between them, and the seam glows. A
 * vector cannot blur, so the bloom is the same stack of round-joined strokes the launcher
 * icon carries — laid down widest and faintest first — and both come out of
 * [BoxMarkArt], so the mark on a Box screen cannot drift away from the one on the home
 * screen.
 */
@Composable
fun BoxMark(size: Dp, modifier: Modifier = Modifier) {
    val cube = remember { PathParser().parsePathString(BoxMarkArt.PATH).toPath() }
    Canvas(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.235f))
            .semantics { contentDescription = "Box" },
    ) {
        // The tile is the 72-unit window a launcher mask shows of the icon's 108-unit grid,
        // so the cube sits at the same size in its plate here as it does on the home screen.
        val scale = this.size.width / BoxMarkArt.WINDOW
        drawRect(
            Brush.radialGradient(
                0f to Color(BoxMarkArt.HAZE),
                0.42f to Color(BoxMarkArt.HAZE_MID),
                0.78f to BoxVoid,
                1f to BoxVoid,
                center = center,
                radius = this.size.width * 0.875f,
            ),
        )
        withTransform({
            translate(-BoxMarkArt.INSET * scale, -BoxMarkArt.INSET * scale)
            scale(scale, scale, Offset.Zero)
        }) {
            BoxMarkArt.GLOW.forEach { (width, alpha) ->
                drawPath(
                    cube,
                    BoxGreen,
                    alpha = alpha,
                    style = Stroke(width = width, join = StrokeJoin.Round),
                )
            }
            drawPath(cube, Color(BoxMarkArt.FACE))
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
    else -> BoxGreenLight
}

/** A small filled dot, used for live-status indicators. */
@Composable
fun StatusDot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}
