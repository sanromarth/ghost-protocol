package com.ghostprotocol.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest

data class AvatarStyle(val backgroundColor: Color, val textColor: Color, val initial: Char)

object AvatarGenerator {
    fun fromPubkey(pubkey: ByteArray?, username: String): AvatarStyle {
        if (pubkey == null || pubkey.isEmpty()) {
            val hash = MessageDigest.getInstance("SHA-256").digest(username.toByteArray(Charsets.UTF_8))
            val hue = (hash[0].toInt() and 0xFF) / 255f * 360f
            val sat = 0.55f + (hash[1].toInt() and 0xFF) / 255f * 0.25f
            val light = 0.50f + (hash[2].toInt() and 0xFF) / 255f * 0.10f
            val bgColor = Color.hsl(hue, sat, light)
            val textColor = if (light > 0.55f) Color.Black else Color.White
            val initial = username.trim().firstOrNull()?.uppercaseChar() ?: '?'
            return AvatarStyle(bgColor, textColor, initial)
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(pubkey)
        val hue = (hash[0].toInt() and 0xFF) / 255f * 360f
        val sat = 0.55f + (hash[1].toInt() and 0xFF) / 255f * 0.25f
        val light = 0.50f + (hash[2].toInt() and 0xFF) / 255f * 0.10f
        val bgColor = Color.hsl(hue, sat, light)
        val textColor = if (light > 0.55f) Color.Black else Color.White
        val initial = username.trim().firstOrNull()?.uppercaseChar() ?: '?'
        return AvatarStyle(bgColor, textColor, initial)
    }
}

/**
 * GHOST Signature Avatar:
 * Features the "Ghost Aura" / Ethereal Ring:
 * - Mutually Verified: High-contrast neon violet sweep gradient rim.
 *   (Static in scrollable lists for 0-overhead smooth scrolling; animated only when animateEtherealRing=true)
 * - In Survival HUD Mode: Crisp phosphor green outline, 0 GPU draw calls to save battery.
 * - Unverified: Minimal dark slate outline (BorderUnverified).
 */
@Composable
fun GhostAvatar(
    pubkey: ByteArray?,
    name: String,
    size: Dp = GhostTheme.AvatarMedium,
    isMutuallyVerified: Boolean = false,
    animateEtherealRing: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val T = GhostTheme
    val avatar = remember(pubkey, name) { AvatarGenerator.fromPubkey(pubkey, name) }
    val isSurvival = T.isSurvivalHudEnabled

    // Rotate ethereal ring only when explicitly requested and not in survival mode
    val rotationAngle = if (!isSurvival && isMutuallyVerified && animateEtherealRing) {
        val infiniteTransition = rememberInfiniteTransition(label = "ethereal_ring")
        val angle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ethereal_spin"
        )
        angle
    } else {
        0f
    }

    val rimThickness = if (isMutuallyVerified) 2.5.dp else 1.dp
    val fontSize = (size.value * 0.42f).sp

    Box(
        modifier = modifier
            .size(size)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (isMutuallyVerified) {
            if (isSurvival) {
                // Static Tactical Phosphor Green ring for Survival OLED mode (0 animation)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(rimThickness, T.SurvivalPhosphor, CircleShape)
                )
            } else {
                // Ethereal Neon Violet Ring (uses hoisted static brush)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (rotationAngle != 0f) Modifier.rotate(rotationAngle) else Modifier)
                        .border(rimThickness, T.EtherealSweepBrush, CircleShape)
                )
            }
        } else {
            // Unverified: Minimal sleek slate rim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(rimThickness, T.BorderUnverified, CircleShape)
            )
        }

        // Inner Avatar Body
        Box(
            modifier = Modifier
                .size(size - (rimThickness * 2 + 2.dp))
                .clip(CircleShape)
                .background(avatar.backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = avatar.initial.toString(),
                color = avatar.textColor,
                fontWeight = FontWeight.Bold,
                fontSize = fontSize
            )
        }
    }
}
