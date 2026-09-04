package com.ghostprotocol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val HexagonShape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(w * 0.5f, 0f)
    lineTo(w, h * 0.25f)
    lineTo(w, h * 0.75f)
    lineTo(w * 0.5f, h)
    lineTo(0f, h * 0.75f)
    lineTo(0f, h * 0.25f)
    close()
}

@Composable
fun HexagonAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = GhostTheme.AvatarMedium,
    borderColor: Color = GhostTheme.Purple
) {
    val initials = name.trim().take(2).uppercase()
    // Deterministic hue from name
    val hash = name.hashCode()
    val hue = (hash and 0xFFFF) % 360f
    val color1 = Color.hsl(hue, 0.6f, 0.25f)
    val color2 = Color.hsl((hue + 40f) % 360f, 0.7f, 0.4f)

    Box(
        modifier = modifier
            .size(size)
            .clip(HexagonShape)
            .background(Brush.linearGradient(listOf(color1, color2)))
            .border(1.5.dp, borderColor, HexagonShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = if (size <= 36.dp) 12.sp else 16.sp
        )
    }
}
