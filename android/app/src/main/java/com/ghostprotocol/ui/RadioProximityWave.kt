package com.ghostprotocol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Live Radio Proximity Wave:
 * Renders physical BLE RF signal awareness instead of a generic internet dot.
 */
@Composable
fun RadioProximityWave(
    rssi: Int?,
    isOnline: Boolean,
    modifier: Modifier = Modifier
) {
    val T = GhostTheme

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        if (isOnline) {
            val (bars, estDist) = when {
                rssi != null && rssi >= -65 -> "∿∿∿" to "~3m"
                rssi != null && rssi >= -80 -> "∿∿" to "~8m"
                else -> "∿" to "~15m"
            }
            val signalColor = if (T.isSurvivalHudEnabled) T.SurvivalPhosphor else T.Online

            Text(
                text = bars,
                color = signalColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (rssi != null) "$estDist ($rssi dBm)" else "$estDist Direct",
                color = signalColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        } else {
            Text(
                text = "📡 Relayed (Mesh Ready)",
                color = if (T.isSurvivalHudEnabled) T.SurvivalAmber else T.TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
