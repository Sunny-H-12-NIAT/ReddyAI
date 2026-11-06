package com.reddysai.app.ui.overlay

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.reddysai.app.ui.character.ReddyCharacter
import com.reddysai.app.ui.theme.*
import kotlin.math.abs

/**
 * States the overlay character/widget can be in.
 */
enum class OverlayState {
    Idle,       // Floating character, breathing & hovering
    Expanded,   // Character + mic + text input dialog
    Listening,  // Active voice capture with pulse rings
    Thinking,   // Processing / waiting for model with orbital rings
    Executing,  // Automation in-flight (shrunken badge)
    Result      // Response displayed in clean message card
}

// ── ChatGPT-inspired Matte Dark Palette ─────────────────────────────────
private val BG        = Color(0xFF0A0A0A)
private val Panel     = Color(0xFF141414)
private val Panel2    = Color(0xFF1E1E1E)
private val Divider   = Color(0xFF282828)
private val TealAcct  = Color(0xFF10A37F)   // ChatGPT signature teal
private val TealLight = Color(0xFF19C37D)
private val TextHi    = Color(0xFFECECEC)
private val TextLo    = Color(0xFF8E8EA0)   // ChatGPT muted label grey
private val IconGrey  = Color(0xFF565869)

/**
 * OverlayContent — Floating UI rendered inside OverlayService.
 *
 * Professional ChatGPT matte black design language.
 * Butter-smooth 60/120fps physics and gesture responsiveness.
 */
@Composable
fun OverlayContent(
    state: OverlayState,
    responseText: String,
    userInput: String,
    onUserInputChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    onMicClick: () -> Unit,
    onMascotTap: () -> Unit = {},
    onDragDelta: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit
) {
    var userExpanded by remember { mutableStateOf(false) }

    val actualState = when {
        !userExpanded && state == OverlayState.Idle -> OverlayState.Idle
        !userExpanded && state == OverlayState.Executing -> OverlayState.Executing
        state == OverlayState.Listening -> OverlayState.Listening
        state == OverlayState.Thinking  -> OverlayState.Thinking
        state == OverlayState.Executing -> OverlayState.Executing
        state == OverlayState.Result    -> OverlayState.Result
        userExpanded -> OverlayState.Expanded
        else -> OverlayState.Idle
    }

    // Auto-expand when woken by voice or result is ready
    LaunchedEffect(state) {
        if (state == OverlayState.Listening ||
            state == OverlayState.Thinking  ||
            state == OverlayState.Result) {
            userExpanded = true
        }
    }

    val targetWidth = when (actualState) {
        OverlayState.Idle      -> 76
        OverlayState.Executing -> 56
        else                   -> 320
    }

    val widthDp by animateDpAsState(
        targetValue   = targetWidth.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessMediumLow
        ),
        label = "panelWidth"
    )

    Box(
        modifier = Modifier
            .width(widthDp)
            .wrapContentHeight()
    ) {
        AnimatedContent(
            targetState  = actualState,
            transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.92f))
                    .togetherWith(fadeOut(tween(160)) + scaleOut(tween(180), targetScale = 0.94f))
            },
            label = "overlayContentTransition"
        ) { s ->
            when (s) {
                OverlayState.Idle -> {
                    IdleBubble(
                        onTap       = {
                            userExpanded = true
                            onMascotTap()
                        },
                        onDragDelta = onDragDelta,
                        onDragEnd   = onDragEnd
                    )
                }
                OverlayState.Executing -> {
                    ExecutingBadge(
                        onDragDelta = onDragDelta,
                        onDragEnd   = onDragEnd
                    )
                }
                OverlayState.Result -> {
                    ResultWidget(
                        text        = responseText,
                        onDismiss   = {
                            userExpanded = false
                            onDismiss()
                        },
                        onDragDelta = onDragDelta,
                        onDragEnd   = onDragEnd
                    )
                }
                else -> {
                    ExpandedWidget(
                        state             = s,
                        userInput         = userInput,
                        onUserInputChange = onUserInputChange,
                        onSubmit          = onSubmit,
                        onMicClick        = onMicClick,
                        onCollapse        = { userExpanded = false },
                        onDragDelta       = onDragDelta,
                        onDragEnd         = onDragEnd
                    )
                }
            }
        }
    }
}

// ─── Idle Bubble (Floating Character) ───────────────────────────────────

@Composable
private fun IdleBubble(
    onTap: () -> Unit,
    onDragDelta: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit
) {
    var totalDragDist by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .size(76.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { totalDragDist = 0f },
                    onDrag = { change, drag ->
                        change.consume()
                        totalDragDist += abs(drag.x) + abs(drag.y)
                        onDragDelta(drag.x, drag.y)
                    },
                    onDragEnd = {
                        if (totalDragDist < 10f) {
                            onTap()
                        }
                        onDragEnd()
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
        contentAlignment = Alignment.Center
    ) {
        // Floating pill backdrop — subtle matte glass finish
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(Panel.copy(alpha = 0.82f))
                .border(1.dp, Divider, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            ReddyCharacter(state = OverlayState.Idle, size = 62.dp)
        }
    }
}

// ─── Executing Badge ─────────────────────────────────────────────────────

@Composable
private fun ExecutingBadge(
    onDragDelta: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        onDragDelta(drag.x, drag.y)
                    },
                    onDragEnd = onDragEnd
                )
            }
            .clip(CircleShape)
            .background(Panel)
            .border(1.5.dp, TealAcct.copy(alpha = 0.7f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        ReddyCharacter(state = OverlayState.Executing, size = 44.dp)
    }
}

// ─── Expanded Widget (Chat / Command Panel) ─────────────────────────────

@Composable
private fun ExpandedWidget(
    state: OverlayState,
    userInput: String,
    onUserInputChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onMicClick: () -> Unit,
    onCollapse: () -> Unit,
    onDragDelta: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val isListening = state == OverlayState.Listening

    Column(
        modifier = Modifier
            .width(320.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Panel)
            .border(1.dp, Divider, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Top Draggable Handle & Title Bar ──────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, drag ->
                            change.consume()
                            onDragDelta(drag.x, drag.y)
                        },
                        onDragEnd = onDragEnd
                    )
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Living state indicator dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isListening) TealLight else IconGrey)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text       = "Reddy's AI",
                    color      = TextHi,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 13.sp,
                    fontFamily = FontFamily.SansSerif
                )
            }

            // Top drag grip indicator
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(IconGrey.copy(alpha = 0.45f))
            )

            // Collapse icon button
            IconButton(
                onClick  = onCollapse,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Collapse",
                    tint               = IconGrey,
                    modifier           = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Divider, thickness = 0.5.dp)
        Spacer(Modifier.height(10.dp))

        // ── Animated Mascot ───────────────────────────────────────────
        ReddyCharacter(state = state, size = 84.dp)

        Spacer(Modifier.height(8.dp))

        // ── Status Label ──────────────────────────────────────────────
        val statusText = when (state) {
            OverlayState.Listening -> "Listening to your voice…"
            OverlayState.Thinking  -> "Thinking & analyzing…"
            else                   -> "How can I help you?"
        }
        Text(
            text       = statusText,
            color      = if (isListening) TealLight else TextLo,
            fontSize   = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.SansSerif
        )

        Spacer(Modifier.height(12.dp))

        // ── Input & Voice Row ─────────────────────────────────────────
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value         = userInput,
                onValueChange = onUserInputChange,
                placeholder   = {
                    Text(
                        text     = if (isListening) "Listening…" else "Ask Reddy anything…",
                        color    = IconGrey,
                        fontSize = 13.sp
                    )
                },
                modifier = Modifier.weight(1f),
                shape    = RoundedCornerShape(12.dp),
                colors   = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor      = TealAcct,
                    unfocusedBorderColor    = Divider,
                    focusedTextColor        = TextHi,
                    unfocusedTextColor      = TextHi,
                    cursorColor             = TealAcct,
                    focusedContainerColor   = Panel2,
                    unfocusedContainerColor = Panel2,
                ),
                singleLine      = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (userInput.isNotBlank()) onSubmit(userInput)
                }),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )

            Spacer(Modifier.width(6.dp))

            // Mic toggle button
            FilledIconButton(
                onClick  = onMicClick,
                modifier = Modifier.size(42.dp),
                colors   = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isListening) TealAcct else Panel2
                )
            ) {
                Icon(
                    imageVector        = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (isListening) "Stop voice" else "Voice input",
                    tint               = if (isListening) Color.White else TextLo,
                    modifier           = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.width(6.dp))

            // Send button
            val canSend = userInput.isNotBlank()
            FilledIconButton(
                onClick  = { if (canSend) onSubmit(userInput) },
                enabled  = canSend,
                modifier = Modifier.size(42.dp),
                colors   = IconButtonDefaults.filledIconButtonColors(
                    containerColor         = TealAcct,
                    disabledContainerColor = Panel2
                )
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Send",
                    tint               = if (canSend) Color.White else IconGrey,
                    modifier           = Modifier.size(17.dp)
                )
            }
        }
    }
}

// ─── Result Widget ────────────────────────────────────────────────────────

@Composable
private fun ResultWidget(
    text: String,
    onDismiss: () -> Unit,
    onDragDelta: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(320.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Panel)
            .border(1.dp, Divider, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Draggable Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, drag ->
                            change.consume()
                            onDragDelta(drag.x, drag.y)
                        },
                        onDragEnd = onDragEnd
                    )
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(TealLight)
                )
                Spacer(Modifier.width(8.dp))
                Text("Reddy's AI", color = TextHi, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }

            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = IconGrey, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Divider, thickness = 0.5.dp)
        Spacer(Modifier.height(10.dp))

        ReddyCharacter(state = OverlayState.Result, size = 68.dp)
        Spacer(Modifier.height(10.dp))

        // ChatGPT-style response bubble
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Panel2)
                .border(0.5.dp, Divider, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text(
                text       = text,
                color      = TextHi,
                fontSize   = 13.sp,
                lineHeight = 20.sp,
                textAlign  = TextAlign.Start,
                maxLines   = 8,
                overflow   = TextOverflow.Ellipsis,
                fontFamily = FontFamily.SansSerif
            )
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick  = onDismiss,
            modifier = Modifier.fillMaxWidth().height(40.dp),
            shape    = RoundedCornerShape(10.dp),
            border   = BorderStroke(1.dp, Divider)
        ) {
            Text("Done", color = TextLo, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
