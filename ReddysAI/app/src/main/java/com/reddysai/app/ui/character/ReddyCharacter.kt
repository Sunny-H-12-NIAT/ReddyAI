package com.reddysai.app.ui.character

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reddysai.app.R
import com.reddysai.app.ui.overlay.OverlayState
import com.reddysai.app.ui.theme.Accent
import kotlin.math.cos
import kotlin.math.sin

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  ReddyCharacter — Butter-smooth, alive, animated AI companion
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Designed with ChatGPT-style matte dark aesthetic & fluid 60/120fps physics.
 *
 * States:
 *   • Idle      → Organic floating hover (10dp bob) + 3D ground shadow + subtle body sway
 *   • Listening → Attentive lean-in + dual expanding sonar/radar rings in ChatGPT teal
 *   • Thinking  → Inquisitive head tilt + 3-orb celestial orbital trail
 *   • Executing → Compact task badge + spinning energetic arc
 *   • Result    → Joyful celebration bounce with spring physics
 */
@Composable
fun ReddyCharacter(
    state: OverlayState,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp
) {
    val density = LocalDensity.current

    // ── 1. Floating Hover (Idle & Thinking) ───────────────────────────
    val hoverTransition = rememberInfiniteTransition(label = "hoverTransition")

    // Vertical bob in dp converted to pixels for exact cross-device scale
    val bobProgress by hoverTransition.animateFloat(
        initialValue = -1f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bobProgress"
    )

    // Gentle side-to-side body sway
    val swayProgress by hoverTransition.animateFloat(
        initialValue = -1f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "swayProgress"
    )

    // Breathing squash & stretch
    val breathScale by hoverTransition.animateFloat(
        initialValue = 0.97f,
        targetValue  = 1.03f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathScale"
    )

    // ── 2. State-driven Transitions ──────────────────────────────────
    // Head tilt angle
    val tiltAngle by animateFloatAsState(
        targetValue = when (state) {
            OverlayState.Thinking  -> 8.5f
            OverlayState.Listening -> -6.0f
            OverlayState.Idle      -> swayProgress * 2.5f
            else                   -> 0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessMediumLow
        ),
        label = "tiltAngle"
    )

    // Base character scale
    val targetScale = when (state) {
        OverlayState.Executing -> 0.72f
        OverlayState.Listening -> 1.06f
        OverlayState.Result    -> 1.10f
        else                   -> breathScale
    }
    val characterScale by animateFloatAsState(
        targetValue   = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessMedium
        ),
        label = "characterScale"
    )

    // Celebration jump (Result state)
    val resultBounce by hoverTransition.animateFloat(
        initialValue = 0f,
        targetValue  = if (state == OverlayState.Result) -12f else 0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(380, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "resultBounce"
    )

    // ── 3. Radar / Sonar Pulse Waves (Listening) ─────────────────────
    val pulseTransition = rememberInfiniteTransition(label = "pulseTransition")
    val pulseProgress1 by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseProgress1"
    )
    val pulseProgress2 by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, delayMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseProgress2"
    )

    // ── 4. Thinking Orbit & Executing Spin ───────────────────────────
    val continuousSpin by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "continuousSpin"
    )

    // Calculate actual pixel translations
    val maxBobPx = with(density) { 9.dp.toPx() }
    val maxSwayPx = with(density) { 2.5.dp.toPx() }
    val idleBobY = if (state == OverlayState.Idle || state == OverlayState.Thinking) {
        bobProgress * maxBobPx
    } else {
        0f
    }
    val idleSwayX = if (state == OverlayState.Idle) swayProgress * maxSwayPx else 0f
    val totalTransY = idleBobY + with(density) { resultBounce.dp.toPx() }

    Box(
        modifier         = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // ── Ground 3D Floating Shadow (Idle & Thinking) ───────────────
        if (state == OverlayState.Idle || state == OverlayState.Thinking) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.BottomCenter)
            ) {
                // When mascot bobs up, shadow expands and lightens; dips down, shrinks & darkens
                val shadowFactor = (1f - (bobProgress * 0.28f)).coerceIn(0.6f, 1.3f)
                val shadowWidth  = size.toPx() * 0.44f * shadowFactor
                val shadowHeight = size.toPx() * 0.12f * shadowFactor
                val shadowAlpha  = (0.24f / shadowFactor).coerceIn(0.10f, 0.40f)

                drawOval(
                    color   = Color.Black.copy(alpha = shadowAlpha),
                    topLeft = Offset(
                        x = (size.toPx() - shadowWidth) / 2f,
                        y = size.toPx() * 0.88f
                    ),
                    size    = Size(shadowWidth, shadowHeight)
                )
            }
        }

        // ── Sonar Pulse Rings (Listening State) ───────────────────────
        if (state == OverlayState.Listening) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.toPx() / 2f, size.toPx() / 2f)
                val baseRadius = size.toPx() * 0.45f

                listOf(pulseProgress1, pulseProgress2).forEach { p ->
                    val r = baseRadius + (p * size.toPx() * 0.40f)
                    val alpha = ((1f - p) * 0.55f).coerceIn(0f, 1f)
                    drawCircle(
                        color  = Accent.copy(alpha = alpha),
                        radius = r,
                        center = center,
                        style  = Stroke(width = with(density) { 1.5.dp.toPx() })
                    )
                }
            }
        }

        // ── Executing Energetic Arc Ring ─────────────────────────────
        if (state == OverlayState.Executing) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.toPx() / 2f, size.toPx() / 2f)
                val r = size.toPx() * 0.46f
                drawArc(
                    brush       = Brush.sweepGradient(
                        colors = listOf(Color.Transparent, Accent, AccentSoft, Color.White)
                    ),
                    startAngle  = continuousSpin,
                    sweepAngle  = 260f,
                    useCenter   = false,
                    style       = Stroke(width = with(density) { 2.5.dp.toPx() }),
                    topLeft     = Offset(center.x - r, center.y - r),
                    size        = Size(r * 2f, r * 2f)
                )
            }
        }

        // ── Mascot Core Image ─────────────────────────────────────────
        Image(
            painter            = painterResource(id = R.drawable.reddy_mascot),
            contentDescription = "Reddy AI Mascot",
            modifier           = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = totalTransY
                    translationX = idleSwayX
                    rotationZ    = tiltAngle
                    scaleX       = characterScale
                    scaleY       = characterScale
                },
            contentScale = ContentScale.Fit
        )

        // ── Thinking 3-Orb Celestial Orbit ────────────────────────────
        if (state == OverlayState.Thinking) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx   = size.toPx() / 2f
                val headY = size.toPx() * 0.16f + idleBobY

                val orbitRadiiX = size.toPx() * 0.42f
                val orbitRadiiY = size.toPx() * 0.16f

                val colors = listOf(Accent, Color(0xFFECECEC), AccentSoft)
                val dotRadii = listOf(
                    with(density) { 3.5.dp.toPx() },
                    with(density) { 2.8.dp.toPx() },
                    with(density) { 3.2.dp.toPx() }
                )

                for (i in 0 until 3) {
                    val angleDeg = continuousSpin + (i * 120.0)
                    val rad = Math.toRadians(angleDeg)
                    val ox = cx + (cos(rad).toFloat() * orbitRadiiX)
                    val oy = headY + (sin(rad).toFloat() * orbitRadiiY)

                    // Slight depth scaling based on Y position (front/back orbit effect)
                    val depthScale = 0.8f + (sin(rad).toFloat() + 1f) * 0.25f

                    drawCircle(
                        color  = colors[i].copy(alpha = 0.90f),
                        radius = dotRadii[i] * depthScale,
                        center = Offset(ox, oy)
                    )
                }
            }
        }
    }
}

private val AccentSoft = Color(0xFF19C37D)
private val EaseInOutCubic = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val EaseInOutSine  = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
