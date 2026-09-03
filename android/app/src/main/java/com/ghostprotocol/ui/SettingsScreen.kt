package com.ghostprotocol.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ghostprotocol.IdentityManager
import com.ghostprotocol.BatteryMonitor
import com.ghostprotocol.data.GhostDatabase
import com.ghostprotocol.power.BatteryTelemetry
import com.ghostprotocol.power.PowerMode
import com.ghostprotocol.power.PowerPolicyEngine
import com.ghostprotocol.power.TelemetrySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(IdentityManager.getDisplayName()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // v0.2: Power management state
    var lastSnapshot by remember { mutableStateOf<TelemetrySnapshot?>(null) }
    var batteryPercent by remember { mutableIntStateOf(-1) }
    var isExporting by remember { mutableStateOf(false) }

    // Load latest telemetry snapshot
    LaunchedEffect(Unit) {
        val telemetry = BatteryTelemetry(context)
        val report = telemetry.getReport()
        if (report.isNotEmpty()) {
            lastSnapshot = report.last()
        }
        // Read battery level
        val batteryIntent = context.registerReceiver(null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        batteryPercent = if (scale > 0) (level * 100) / scale else -1

        // Auto-refresh every 30s
        while (true) {
            delay(30_000L)
            val refreshed = telemetry.getReport()
            if (refreshed.isNotEmpty()) lastSnapshot = refreshed.last()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            item {
                Text("Identity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(onClick = {
                    val trimmed = username.trim()
                    if (trimmed.isEmpty() || trimmed.length > 20 || !trimmed.matches(Regex("^[A-Za-z0-9 _-]+$"))) {
                        Toast.makeText(context, "Invalid username (1-20 chars, letters/numbers/spaces/-/_)", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    IdentityManager.setDisplayName(trimmed)
                    username = trimmed
                    // Broadcast name update to all contacts via BLE
                    val intent = android.content.Intent(context, com.ghostprotocol.GhostService::class.java).apply {
                        action = "ACTION_BROADCAST_NAME"
                    }
                    context.startService(intent)
                    Toast.makeText(context, "Saved — broadcasting to contacts", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.fillMaxWidth(),
                   enabled = username.isNotBlank()
                ) {
                    Text("Save Username")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(onClick = { navController.navigate("qr_show") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Show My QR")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Handle", IdentityManager.getContactId())
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied handle to clipboard", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Copy Handle")
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            // ===== v0.2: POWER MANAGEMENT SECTION =====
            item {
                Text("Power Management", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                // Current mode display
                val currentMode = lastSnapshot?.currentMode ?: PowerMode.ECO
                val modeColor = when (currentMode) {
                    PowerMode.ACTIVE -> Color(0xFF4CAF50)     // Green
                    PowerMode.ECO -> Color(0xFF2196F3)        // Blue
                    PowerMode.CRITICAL -> Color(0xFFFF9800)   // Orange
                    PowerMode.DEEP_SLEEP -> Color(0xFF9E9E9E) // Gray
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Current Mode: ", style = MaterialTheme.typography.bodyLarge)
                    SuggestionChip(
                        onClick = {},
                        label = { Text(currentMode.name, fontWeight = FontWeight.Bold) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = modeColor.copy(alpha = 0.2f),
                            labelColor = modeColor
                        )
                    )
                }

                if (batteryPercent >= 0) {
                    Text("Battery: $batteryPercent%", style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Mode override buttons
                Text("Force Mode (1 hour override):", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (mode in PowerMode.entries) {
                        val btnColor = when (mode) {
                            PowerMode.ACTIVE -> Color(0xFF4CAF50)
                            PowerMode.ECO -> Color(0xFF2196F3)
                            PowerMode.CRITICAL -> Color(0xFFFF9800)
                            PowerMode.DEEP_SLEEP -> Color(0xFF9E9E9E)
                        }
                        val isCurrentMode = currentMode == mode
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(context, com.ghostprotocol.GhostService::class.java).apply {
                                    action = "ACTION_CYCLE_MODE"
                                }
                                context.startService(intent)
                                Toast.makeText(context, "Mode cycling — tap again to advance", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = if (isCurrentMode) {
                                ButtonDefaults.outlinedButtonColors(containerColor = btnColor.copy(alpha = 0.15f))
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            }
                        ) {
                            Text(
                                text = when (mode) {
                                    PowerMode.ACTIVE -> "ACT"
                                    PowerMode.ECO -> "ECO"
                                    PowerMode.CRITICAL -> "CRIT"
                                    PowerMode.DEEP_SLEEP -> "SLEEP"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isCurrentMode) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Last telemetry summary
                lastSnapshot?.let { snap ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Last Telemetry", fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Peers: ${snap.peerCount}  |  Forwarded: ${snap.messagesForwarded}  |  Delivered: ${snap.messagesDelivered}")
                            Text("GATT connections: ${snap.gattConnections}  |  TX: ${snap.gattBytesTx / 1024}KB")
                            Text("Scan time: ${snap.bleScanTimeMs / 1000}s  |  Adv time: ${snap.bleAdvertiseTimeMs / 1000}s")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Export battery report
                Button(
                    onClick = {
                        isExporting = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val telemetry = BatteryTelemetry(context)
                                val csv = telemetry.exportCsv()
                                val file = File(context.cacheDir, "ghost_battery_report.csv")
                                file.writeText(csv)
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Export Battery Report"))
                            } catch (e: Exception) {
                                // FileProvider may not be configured — fall back to clipboard
                                val telemetry = BatteryTelemetry(context)
                                val csv = telemetry.exportCsv()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Battery Report", csv))
                            } finally {
                                isExporting = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isExporting
                ) {
                    Text(if (isExporting) "Exporting..." else "Export Battery Report")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }
            
            item {
                Text("Danger Zone", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Delete All Data", color = MaterialTheme.colorScheme.error)
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }
            
            item {
                Text("About", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("GHOST v0.2")
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }
            
            item {
                Text("Debug", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Battery Stats:", fontWeight = FontWeight.Bold)
            }
            
            val readings = BatteryMonitor.readings.takeLast(20)
            items(readings) { reading ->
                Text("Level: ${reading.level}%  |  Elapsed: ${reading.elapsedMinutes}min  |  Drain: ${reading.drainPercent}%")
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete All Data") },
                text = { Text("Delete all contacts and messages? Your identity keys will be preserved. This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        val db = GhostDatabase.getInstance(context)
                        scope.launch(Dispatchers.IO) {
                            db.messageDao().deleteAll()
                            db.contactDao().deleteAll()
                        }
                        showDeleteConfirm = false
                        navController.popBackStack("contacts", inclusive = false)
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
