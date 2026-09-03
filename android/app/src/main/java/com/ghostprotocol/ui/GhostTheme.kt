package com.ghostprotocol.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * GHOST Protocol Design System
 * Dark theme & Tactical OLED Survival HUD.
 */
object GhostTheme {

    var isSurvivalHudEnabled by mutableStateOf(false)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("ghost_ui_prefs", Context.MODE_PRIVATE)
        isSurvivalHudEnabled = prefs.getBoolean("survival_hud_enabled", false)
    }

    fun setSurvivalHud(context: Context, enabled: Boolean) {
        isSurvivalHudEnabled = enabled
        val prefs = context.getSharedPreferences("ghost_ui_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("survival_hud_enabled", enabled).apply()
    }

    fun toggleSurvivalHud(context: Context) {
        setSurvivalHud(context, !isSurvivalHudEnabled)
    }

    // ── Brand ──
    val Purple: Color
        get() = if (isSurvivalHudEnabled) Color(0xFF52B788) else Color(0xFF7C3AED)
    val PurpleDark: Color
        get() = if (isSurvivalHudEnabled) Color(0xFF2D6A4F) else Color(0xFF5B21B6)
    val PurpleLight: Color
        get() = if (isSurvivalHudEnabled) Color(0xFF74C69D) else Color(0xFFA78BFA)
    val PurpleSubtle: Color
        get() = if (isSurvivalHudEnabled) Color(0xFF52B788).copy(alpha = 0.15f) else Color(0xFF7C3AED).copy(alpha = 0.15f)

    // Cyberpunk Ethereal Ring colors (Neon Violet)
    val NeonViolet1 = Color(0xFF9D4EDD)
    val NeonViolet2 = Color(0xFFC77DFF)
    val NeonViolet3 = Color(0xFF7B2CBF)

    // Survival Tactical colors
    val SurvivalPhosphor = Color(0xFF52B788)
    val SurvivalAmber = Color(0xFFFFB703)

    // ── Surfaces (dark) ──
    val Surface0: Color
        get() = if (isSurvivalHudEnabled) Color(0xFF000000) else Color(0xFF0F0F0F)    // True OLED Black vs Obsidian
    val Surface1: Color
        get() = if (isSurvivalHudEnabled) Color(0xFF0A0A0A) else Color(0xFF1A1A1A)    // Cards, list items
    val Surface2: Color
        get() = if (isSurvivalHudEnabled) Color(0xFF141414) else Color(0xFF252525)    // Received bubbles, elevated
    val Surface3: Color
        get() = if (isSurvivalHudEnabled) Color(0xFF1E1E1E) else Color(0xFF303030)    // Input fields
    val SurfaceOverlay: Color
        get() = if (isSurvivalHudEnabled) Color(0xFF0E0E0E) else Color(0xFF1E1E1E)    // Bottom sheets, modals

    // ── Text ──
    val TextPrimary: Color
        get() = if (isSurvivalHudEnabled) Color(0xFFFFFFFF) else Color(0xFFF5F5F5)
    val TextSecondary: Color
        get() = if (isSurvivalHudEnabled) Color(0xFFA0A0A0) else Color(0xFF9CA3AF)
    val TextMuted: Color
        get() = if (isSurvivalHudEnabled) Color(0xFF707070) else Color(0xFF6B7280)
    val TextOnPurple: Color
        get() = if (isSurvivalHudEnabled) Color(0xFF000000) else Color.White

    // ── Status ──
    val Online: Color
        get() = if (isSurvivalHudEnabled) Color(0xFF52B788) else Color(0xFF10B981)
    val SentCheck = Color(0xFFA78BFA)
    val Failed = Color(0xFFEF4444)
    val Sprayed: Color
        get() = if (isSurvivalHudEnabled) Color(0xFFFFB703) else Color(0xFF3B82F6)

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
