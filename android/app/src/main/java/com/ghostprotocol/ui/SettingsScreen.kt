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

import com.ghostprotocol.ble.BleManager
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.ghostprotocol.security.SecurityPosture
import com.ghostprotocol.security.SecurityPostureManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(IdentityManager.getDisplayName()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // v0.3: Security Posture state
    val postureManager = remember { SecurityPostureManager.getInstance(context) }
    val currentPosture by postureManager.postureFlow.collectAsState()
    var pendingPosture by remember { mutableStateOf<SecurityPosture?>(null) }
    var showPostureConfirmDialog by remember { mutableStateOf(false) }

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

    if (showPostureConfirmDialog && pendingPosture != null) {
        val target = pendingPosture!!
        val isEmergency = target == SecurityPosture.EMERGENCY
        AlertDialog(
            onDismissRequest = {
                showPostureConfirmDialog = false
                pendingPosture = null
            },
            title = {
                Text(
                    if (isEmergency) "Enable EMERGENCY MODE?" else "Enable PROTEST MODE?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (isEmergency) {
                            "Maximum radio duty cycle (300ms scan window/interval) and permanent wakelock. Relay willingness set to 1.0."
                        } else {
                            "Enable PROTEST MODE? This increases BLE scan rate (300ms window / 600ms interval) and may drain battery faster."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Parameters:\n• Scan: ${if (isEmergency) "300ms/300ms" else "300ms/600ms"}\n• Advertise: 100ms HIGH TX\n• Auto-reverts to STEALTH below 20% battery",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        postureManager.setPosture(target)
                        val intent = Intent(context, com.ghostprotocol.GhostService::class.java).apply {
                            action = "ACTION_SET_POSTURE"
                            putExtra("EXTRA_POSTURE", target.name)
                        }
                        context.startService(intent)
                        showPostureConfirmDialog = false
                        pendingPosture = null
                        Toast.makeText(context, "Security posture set to ${target.name}", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SecurityPostureManager.postureColor(target)
                    )
                ) {
                    Text("Confirm", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPostureConfirmDialog = false
                        pendingPosture = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
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
                Text("Power & Display", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                // Tactical Survival HUD Mode Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Tactical Survival HUD", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "True #000000 OLED black, high-contrast monochrome, zero animations to maximize battery in survival/protest scenarios.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GhostTheme.TextSecondary
                        )
                    }
                    Switch(
                        checked = GhostTheme.isSurvivalHudEnabled,
                        onCheckedChange = { GhostTheme.setSurvivalHud(context, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GhostTheme.SurvivalPhosphor,
                            checkedTrackColor = GhostTheme.SurvivalPhosphor.copy(alpha = 0.3f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // v0.3: Security Posture Section
                Text("Security Posture", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Governs BLE scan aggressiveness, unknown peer discovery alerts, and relay willingness.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (posture in SecurityPosture.entries) {
                        val isSelected = currentPosture == posture
                        val chipColor = SecurityPostureManager.postureColor(posture)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clickable {
                                    if (posture == SecurityPosture.STEALTH) {
                                        postureManager.setPosture(SecurityPosture.STEALTH)
                                        val intent = Intent(context, com.ghostprotocol.GhostService::class.java).apply {
                                            action = "ACTION_SET_POSTURE"
                                            putExtra("EXTRA_POSTURE", SecurityPosture.STEALTH.name)
                                        }
                                        context.startService(intent)
                                        Toast.makeText(context, "Security posture: STEALTH", Toast.LENGTH_SHORT).show()
                                    } else if (posture != currentPosture) {
                                        pendingPosture = posture
                                        showPostureConfirmDialog = true
                                    }
                                },
                            shape = RoundedCornerShape(50),
                            color = if (isSelected) chipColor.copy(alpha = 0.25f) else Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) chipColor else Color(0xFF3F3F46)
                            )
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 4.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = posture.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) chipColor else MaterialTheme.colorScheme.onSurface,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Phase 2: Allow Nearby Discovery (read-only state reflection of Security Posture)
                val isDiscoveryActive = currentPosture != SecurityPosture.STEALTH
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!isDiscoveryActive) {
                                Toast.makeText(context, "Switch to PROTEST mode to enable nearby discovery.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Nearby discovery active (${currentPosture.name} mode)", Toast.LENGTH_SHORT).show()
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Allow Nearby Discovery", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (isDiscoveryActive) "Active in ${currentPosture.name} posture" else "Disabled in STEALTH posture",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDiscoveryActive) SecurityPostureManager.postureColor(currentPosture) else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = isDiscoveryActive,
                        onCheckedChange = {
                            if (!isDiscoveryActive) {
                                Toast.makeText(context, "Switch to PROTEST mode to enable nearby discovery.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Nearby discovery active (${currentPosture.name} mode)", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GhostTheme.Purple,
                            checkedTrackColor = GhostTheme.Purple.copy(alpha = 0.3f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Phase 3: Allow Short Code Resolution (read-only switch mirroring posture)
                val isShortCodeActive = currentPosture != SecurityPosture.STEALTH
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!isShortCodeActive) {
                                Toast.makeText(context, "Switch to PROTEST mode to enable short codes.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Short code resolution active (${currentPosture.name} mode)", Toast.LENGTH_SHORT).show()
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Allow Short Code Resolution", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (isShortCodeActive) "Active in ${currentPosture.name} posture" else "Disabled in STEALTH posture",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isShortCodeActive) SecurityPostureManager.postureColor(currentPosture) else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Switch(
                        checked = isShortCodeActive,
                        onCheckedChange = {
                            if (!isShortCodeActive) {
                                Toast.makeText(context, "Switch to PROTEST mode to enable short codes.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Short code resolution active (${currentPosture.name} mode)", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GhostTheme.Purple,
                            checkedTrackColor = GhostTheme.Purple.copy(alpha = 0.3f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Phase 3: My Short Code Card
                val shortCodeManager = BleManager.shortCodeManager
                val currentShortCode = shortCodeManager?.currentCode?.collectAsState()?.value
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = GhostTheme.Surface1),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GhostTheme.Surface3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate("short_code") }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("My Short Code", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = GhostTheme.TextPrimary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isShortCodeActive) (currentShortCode?.toDisplayString() ?: "Generating...") else "Disabled in STEALTH mode",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (isShortCodeActive) GhostTheme.Purple else GhostTheme.TextSecondary
                            )
                        }
                        Icon(Icons.Default.QrCode, contentDescription = "View Code", tint = GhostTheme.TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Current mode & posture display — observed live from GhostService
                val activePolicy by com.ghostprotocol.GhostService.currentPowerPolicy.collectAsState()
                val currentMode = activePolicy.mode
                val modeColor = when (currentMode) {
                    PowerMode.ACTIVE -> Color(0xFF4CAF50)     // Green
                    PowerMode.ECO -> Color(0xFF2196F3)        // Blue
                    PowerMode.CRITICAL -> Color(0xFFFF9800)   // Orange
                    PowerMode.DEEP_SLEEP -> Color(0xFF9E9E9E) // Gray
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Posture: ", style = MaterialTheme.typography.bodyMedium)
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    currentPosture.name,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = SecurityPostureManager.postureColor(currentPosture).copy(alpha = 0.2f),
                                labelColor = SecurityPostureManager.postureColor(currentPosture)
                            )
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mode: ", style = MaterialTheme.typography.bodyMedium)
                        SuggestionChip(
                            onClick = {},
                            label = { Text(currentMode.name, fontWeight = FontWeight.Bold) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = modeColor.copy(alpha = 0.2f),
                                labelColor = modeColor
                            )
                        )
                    }
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
                                    action = "ACTION_SET_POWER_MODE"
                                    putExtra("EXTRA_MODE", mode.name)
                                }
                                context.startService(intent)
                                Toast.makeText(context, "Power mode: ${mode.name} (1h override)", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = if (isCurrentMode) {
                                ButtonDefaults.outlinedButtonColors(containerColor = btnColor.copy(alpha = 0.25f))
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
                                fontWeight = if (isCurrentMode) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrentMode) btnColor else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = {
                            val intent = Intent(context, com.ghostprotocol.GhostService::class.java).apply {
                                action = "ACTION_SET_POWER_MODE"
                                putExtra("EXTRA_MODE", "AUTO")
                            }
                            context.startService(intent)
                            Toast.makeText(context, "Reverted to dynamic AUTO power mode", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Revert to Auto Mode", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

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
                                val chooser = Intent.createChooser(shareIntent, "Export Battery Report").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(chooser)
                            } catch (e: Exception) {
                                android.util.Log.e("GHOST", "Export report error: ${e.message}", e)
                                // FileProvider may fail on some ROMs — fall back to clipboard
                                val telemetry = BatteryTelemetry(context)
                                val csv = telemetry.exportCsv()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Battery Report", csv))
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Battery report copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
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
                Text(
                    text = "GHOST Protocol v0.3.8",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = GhostTheme.PurpleLight
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Decentralized, sovereign off-grid mesh communications. Zero servers, zero internet, zero central authority.",
                    fontSize = 12.sp,
                    color = GhostTheme.TextMuted,
                    lineHeight = 16.sp
                )
                
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
