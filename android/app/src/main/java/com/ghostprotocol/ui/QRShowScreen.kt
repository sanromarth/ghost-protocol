package com.ghostprotocol.ui

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ghostprotocol.IdentityManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRShowScreen(navController: NavController) {
    val T = GhostTheme
    val bitmap = remember { generateQrCode() }
    val displayName = remember { IdentityManager.getDisplayName() }
    val handle = remember {
        val pubKey = IdentityManager.getEd25519PubKey()
        val hash = MessageDigest.getInstance("SHA-256").digest(pubKey)
        "#" + hash.take(3).joinToString("") { "%02x".format(it) }
    }
    val avatar = remember {
        AvatarGenerator.fromPubkey(IdentityManager.getEd25519PubKey(), displayName)
    }

    // Pulsing glow animation for QR border
    val infiniteTransition = rememberInfiniteTransition(label = "qr_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Scaffold(
        containerColor = T.Surface0,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = T.TextPrimary)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        navController.navigate("qr_scan") {
                            popUpTo("qr_show") { inclusive = true }
                        }
                    }) {
                        Text("Scan QR", color = T.PurpleLight, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Avatar
            Box(
                modifier = Modifier.size(80.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(avatar.backgroundColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = avatar.initial.toString(),
                        color = avatar.textColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Name
            Text(
                text = displayName,
                color = T.TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            )

            // Handle
            Text(
                text = handle,
                color = T.TextMuted,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // QR Code with animated glow border
            if (bitmap != null) {
                Box(
                    modifier = Modifier
                        .shadow(
                            elevation = 24.dp,
                            shape = RoundedCornerShape(24.dp),
                            ambientColor = T.Purple.copy(alpha = glowAlpha),
                            spotColor = T.Purple.copy(alpha = glowAlpha)
                        )
                        .border(
                            width = 2.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    T.Purple.copy(alpha = glowAlpha),
                                    T.PurpleLight.copy(alpha = glowAlpha * 0.7f),
                                    T.Purple.copy(alpha = glowAlpha)
                                )
                            ),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White)
                        .padding(20.dp)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.size(240.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Instruction pill
            Box(
                modifier = Modifier
                    .background(T.Surface2, RoundedCornerShape(20.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "📱  Ask them to scan this code",
                    color = T.TextSecondary,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    navController.navigate("qr_scan") {
                        popUpTo("qr_show") { inclusive = true }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, T.Purple.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = T.PurpleLight),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text("📷  Scan Their QR Code", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Subtitle
            Text(
                text = "Your cryptographic identity is embedded in this QR.\nNo servers involved — pure peer-to-peer.",
                color = T.TextMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        }
    }
}

private fun generateQrCode(): Bitmap? {
    try {
        val ed25519Pub = IdentityManager.getEd25519PubKey()
        val x25519Pub = IdentityManager.getX25519PubKey()
        val nameBytes = IdentityManager.getDisplayName().toByteArray(Charsets.UTF_8)
        
        val payload = ed25519Pub + x25519Pub + nameBytes
        val base64Data = Base64.encodeToString(payload, Base64.NO_WRAP)
        val content = "GHOST:$base64Data"
        
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        // Inverted colors for dark theme: white QR modules on black for better scanning
        for (x in 0 until 512) {
            for (y in 0 until 512) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bitmap
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}
