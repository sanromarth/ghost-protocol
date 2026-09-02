package com.ghostprotocol.ui

import androidx.compose.ui.graphics.Color
import java.security.MessageDigest

data class AvatarStyle(val backgroundColor: Color, val textColor: Color, val initial: Char)

object AvatarGenerator {
    fun fromPubkey(pubkey: ByteArray, username: String): AvatarStyle {
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
