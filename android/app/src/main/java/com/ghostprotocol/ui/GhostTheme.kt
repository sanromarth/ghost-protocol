package com.ghostprotocol.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * GHOST Protocol Design System
 * Dark theme only — premium encrypted messenger visual identity.
 */
object GhostTheme {

    // ── Brand ──
    val Purple = Color(0xFF7C3AED)
    val PurpleDark = Color(0xFF5B21B6)
    val PurpleLight = Color(0xFFA78BFA)
    val PurpleSubtle = Color(0xFF7C3AED).copy(alpha = 0.15f)

    // ── Surfaces (dark) ──
    val Surface0 = Color(0xFF0F0F0F)    // App background
    val Surface1 = Color(0xFF1A1A1A)    // Cards, list items
    val Surface2 = Color(0xFF252525)    // Received bubbles, elevated
    val Surface3 = Color(0xFF303030)    // Input fields
    val SurfaceOverlay = Color(0xFF1E1E1E) // Bottom sheets, modals

    // ── Text ──
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFF9CA3AF)
    val TextMuted = Color(0xFF6B7280)
    val TextOnPurple = Color.White

    // ── Status ──
    val Online = Color(0xFF10B981)
    val SentCheck = Color(0xFFA78BFA)
    val Failed = Color(0xFFEF4444)
    val Sprayed = Color(0xFF3B82F6)

    // ── Spacing (8dp grid) ──
    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 16.dp
    val SpaceLg = 24.dp
    val SpaceXl = 32.dp
    val Space2xl = 48.dp

    // ── Radii ──
    val RadiusBubble = 18.dp
    val RadiusBubbleSharp = 4.dp
    val RadiusCard = 12.dp
    val RadiusInput = 24.dp
    val RadiusFull = 999.dp

    // ── Sizes ──
    val AvatarSmall = 36.dp
    val AvatarMedium = 48.dp
    val AvatarLarge = 80.dp
    val BubbleMaxWidth = 300.dp
    val InputMinHeight = 48.dp
    val SendButtonSize = 40.dp
    val OnlineDotSize = 12.dp
    val UnreadBadgeSize = 20.dp
}
