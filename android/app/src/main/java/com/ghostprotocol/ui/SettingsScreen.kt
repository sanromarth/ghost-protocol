package com.ghostprotocol.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ghostprotocol.IdentityManager
import com.ghostprotocol.BatteryMonitor
import com.ghostprotocol.data.GhostDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(IdentityManager.getDisplayName()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
                Text("GHOST v0.1")
                
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
