package com.ghostprotocol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostprotocol.power.PowerMode
import com.ghostprotocol.power.PowerPolicy

/**
 * Tactical Survival HUD Strip:
 * High-contrast, zero-animation status monitor for survival / blackout scenarios.
 */
@Composable
fun SurvivalHudStrip(
    batteryPercent: Int,
    powerPolicy: PowerPolicy,
    peerCount: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val T = GhostTheme
    if (!T.isSurvivalHudEnabled) return

    val modeColor = when (powerPolicy.mode) {
        PowerMode.ACTIVE -> Color(0xFF52B788)
        PowerMode.ECO -> Color(0xFF74C69D)
        PowerMode.CRITICAL -> Color(0xFFEF4444)
        PowerMode.DEEP_SLEEP -> Color(0xFFFFB703)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0A0A0A))
            .border(1.dp, Color(0xFF2D6A4F), RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "⚡ HUD [OLED BLACK]",
                    color = T.SurvivalPhosphor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (batteryPercent >= 0) "$batteryPercent%" else "--%",
                    color = if (batteryPercent < 20) Color(0xFFEF4444) else T.SurvivalPhosphor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                text = "${powerPolicy.mode.name} | ${powerPolicy.scanIntervalMs}ms | $peerCount PEERS",
                color = modeColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
