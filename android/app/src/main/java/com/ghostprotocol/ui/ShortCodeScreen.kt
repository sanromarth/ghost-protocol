package com.ghostprotocol.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ghostprotocol.ble.BleManager
import com.ghostprotocol.security.SecurityPosture
import com.ghostprotocol.security.SecurityPostureManager
import com.ghostprotocol.ui.GhostTheme as T

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortCodeScreen(navController: NavController) {
    val context = LocalContext.current
    val postureManager = remember { SecurityPostureManager.getInstance(context) }
    val currentPosture by postureManager.postureFlow.collectAsState()

    val shortCodeManager = BleManager.shortCodeManager
    val currentCode = shortCodeManager?.currentCode?.collectAsState()?.value
    val timeRemaining = shortCodeManager?.timeRemaining?.collectAsState()?.value ?: ""

    var isExplanationExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Short Code", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = T.Surface0,
                    titleContentColor = T.TextPrimary,
                    navigationIconContentColor = T.TextPrimary
                )
            )
        },
        containerColor = T.Surface0
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            if (currentPosture == SecurityPosture.STEALTH) {
                // STEALTH disabled state
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(T.RadiusCard),
                        colors = CardDefaults.cardColors(containerColor = T.Surface1),
                        border = androidx.compose.foundation.BorderStroke(1.dp, T.Surface3),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Short codes disabled in STEALTH mode",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = T.TextPrimary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Switch to PROTEST or EMERGENCY mode to generate and broadcast your 24-hour verification code.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = T.TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                // Active Short Code display
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Your 24-hour verification code",
                        style = MaterialTheme.typography.bodyMedium,
                        color = T.TextSecondary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    val displayStr = currentCode?.toDisplayString() ?: "GENERATING..."
                    Surface(
                        shape = RoundedCornerShape(T.RadiusCard),
                        color = T.Surface1,
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, T.Purple.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 32.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = displayStr,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 22.sp,
                                    lineHeight = 32.sp
                                ),
                                fontWeight = FontWeight.Bold,
                                color = T.Purple,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Live countdown timer in amber
                    Text(
                        text = timeRemaining,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFFFB703),
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    // Copy and Share Action Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                currentCode?.let { code ->
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("GHOST Short Code", code.toCompactString())
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Copied: ${code.toCompactString()}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = T.Surface2, contentColor = T.TextPrimary),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy Code", fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                currentCode?.let { code ->
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, "Connect with me on GHOST: ${code.toCompactString()}")
                                        type = "text/plain"
                                    }
                                    val shareIntent = Intent.createChooser(sendIntent, "Share GHOST Short Code")
                                    context.startActivity(shareIntent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = T.Purple, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Share", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // "How this works" expandable card
            Card(
                shape = RoundedCornerShape(T.RadiusCard),
                colors = CardDefaults.cardColors(containerColor = T.Surface1),
                border = androidx.compose.foundation.BorderStroke(1.dp, T.Surface3),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExplanationExpanded = !isExplanationExpanded }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = "Info", tint = T.TextSecondary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("How this works", style = MaterialTheme.typography.titleSmall, color = T.TextPrimary)
                        }
                        Text(if (isExplanationExpanded) "Hide" else "Show", style = MaterialTheme.typography.labelSmall, color = T.Purple)
                    }

                    AnimatedVisibility(visible = isExplanationExpanded) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Text(
                                text = "This code changes every midnight UTC. Anyone who knows it can request your contact via the mesh without cameras or BLE proximity. It expires automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = T.TextSecondary,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
