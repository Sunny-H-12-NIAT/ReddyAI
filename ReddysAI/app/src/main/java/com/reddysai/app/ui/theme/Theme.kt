package com.reddysai.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Reddy's AI Design System — Black Matte / ChatGPT-style ──────────────
//
//  Background   #0A0A0A  Pure matte black
//  Surface      #141414  Cards / panels
//  Surface2     #1E1E1E  Input fields / elevated panels
//  Accent       #10A37F  ChatGPT-style teal/green — crisp, professional
//  AccentSoft   #19C37D  Lighter accent for highlights
//  Border       #2D2D2D  Subtle dividers
//  TextPrimary  #ECECEC  Near-white readable text
//  TextSecond   #8E8EA0  Muted label text (ChatGPT grey)

val MatteBlack   = Color(0xFF0A0A0A)
val MatteSurface = Color(0xFF141414)
val Accent       = Color(0xFF10A37F)   // ChatGPT teal
val AccentLight  = Color(0xFF19C37D)
val TextPrimary  = Color(0xFFECECEC)
val TextSecond   = Color(0xFF8E8EA0)
val BorderColor  = Color(0xFF2D2D2D)

private val DarkColorScheme = darkColorScheme(
    primary          = Accent,
    secondary        = AccentLight,
    tertiary         = Color(0xFF19C37D),
    background       = MatteBlack,
    surface          = MatteSurface,
    surfaceVariant   = Color(0xFF1E1E1E),
    onPrimary        = Color.White,
    onSecondary      = Color.White,
    onBackground     = TextPrimary,
    onSurface        = TextPrimary,
    outline          = BorderColor,
)

@Composable
fun ReddysAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
