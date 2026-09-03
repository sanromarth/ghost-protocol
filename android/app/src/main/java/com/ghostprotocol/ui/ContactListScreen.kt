package com.ghostprotocol.ui

import android.app.Application
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ghostprotocol.data.Contact
import com.ghostprotocol.data.GhostDatabase
import com.ghostprotocol.data.MessageEntity
import com.ghostprotocol.ble.BleManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine

data class ContactWithPreview(
    val contact: Contact,
    val lastMessage: String? = null,
    val lastMessageTime: Long = 0,
    val unreadCount: Int = 0
)

class ContactListViewModel(application: Application) : AndroidViewModel(application) {
    private val db = GhostDatabase.getInstance(application)
    private val contactDao = db.contactDao()
    private val messageDao = db.messageDao()

    val contacts: StateFlow<List<Contact>> = contactDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(navController: NavController, viewModel: ContactListViewModel = viewModel()) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle(initialValue = emptyList())
    val blePeers by BleManager.peers.collectAsStateWithLifecycle(initialValue = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            currentTime = System.currentTimeMillis()
        }
    }
    val T = GhostTheme

    val context = LocalContext.current
    val currentPowerPolicy by com.ghostprotocol.GhostService.currentPowerPolicy.collectAsStateWithLifecycle()
    val batteryPercent = remember {
        val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (scale > 0) (level * 100) / scale else -1
    }

    Scaffold(
        containerColor = T.Surface0,
        topBar = {
            Surface(color = T.Surface0) {
                Column(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "GHOST",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = T.TextPrimary,
                            letterSpacing = 2.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Survival HUD 1-Tap Toggle
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (T.isSurvivalHudEnabled) T.SurvivalPhosphor.copy(alpha = 0.2f) else T.Surface2)
                                    .border(
                                        1.dp,
                                        if (T.isSurvivalHudEnabled) T.SurvivalPhosphor else Color.Transparent,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        T.toggleSurvivalHud(context)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = if (T.isSurvivalHudEnabled) "⚡ HUD ON" else "⚡ HUD",
                                    color = if (T.isSurvivalHudEnabled) T.SurvivalPhosphor else T.TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(onClick = { navController.navigate("settings") }) {
                                Icon(Icons.Default.Settings, "Settings", tint = T.TextSecondary)
                            }
                        }
                    }

                    // Persistent Tactical Survival HUD strip when enabled
                    SurvivalHudStrip(
                        batteryPercent = batteryPercent,
                        powerPolicy = currentPowerPolicy,
                        peerCount = blePeers.size,
                        onToggle = { T.toggleSurvivalHud(context) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Search bar
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(T.RadiusInput)),
                        placeholder = {
                            Text(
                                "Search ${contacts.size} contacts...",
                                color = T.TextMuted,
                                fontSize = 14.sp
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = T.TextMuted) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = T.Surface2,
                            unfocusedContainerColor = T.Surface1,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = T.Purple,
                            focusedTextColor = T.TextPrimary,
                            unfocusedTextColor = T.TextPrimary
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )
                }
            }
        },
        floatingActionButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End
            ) {
                // Show My QR
                SmallFloatingActionButton(
                    onClick = { navController.navigate("qr_show") },
                    containerColor = T.Surface2,
                    contentColor = T.TextPrimary
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = "My QR", modifier = Modifier.size(20.dp))
                }
                // Scan QR — primary action
                FloatingActionButton(
                    onClick = { navController.navigate("qr_scan") },
                    containerColor = T.Purple,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR")
                }
            }
        }
    ) { padding ->
        val filtered = if (searchQuery.isBlank()) contacts else contacts.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.id.contains(searchQuery, ignoreCase = true)
        }

        when {
            contacts.isEmpty() -> {
                // Premium empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(T.Surface0),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 48.dp)
                    ) {
                        Text("👻", fontSize = 72.sp)
                        Spacer(modifier = Modifier.height(T.SpaceLg))
                        Text(
                            "Your mesh is empty",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = T.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(T.SpaceSm))
                        Text(
                            "Scan a friend's QR code to start\nmessaging securely",
                            fontSize = 14.sp,
                            color = T.TextMuted,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(T.SpaceLg))
                        Button(
                            onClick = { navController.navigate("qr_scan") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = T.Purple,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(T.RadiusInput),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text("Scan QR Code", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        }
                    }
                }
            }
            filtered.isEmpty() && searchQuery.isNotBlank() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(T.Surface0),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔍", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(T.SpaceMd))
                        Text(
                            "No contacts found",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = T.TextSecondary
                        )
                        Spacer(modifier = Modifier.height(T.SpaceXs))
                        Text(
                            "Try a different name or handle",
                            fontSize = 13.sp,
                            color = T.TextMuted
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(T.Surface0)
                ) {
                    items(filtered) { contact ->
                        val matchedPeer = blePeers.find { peer ->
                            val matchesAddress = contact.bleAddress != null && peer.address == contact.bleAddress
                            val matchesFp = peer.fingerprint != null && try {
                                val contactFp = java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(Base64.decode(contact.ed25519PubKey, Base64.NO_WRAP))
                                    .copyOfRange(0, 4)
                                peer.fingerprint.contentEquals(contactFp)
                            } catch (_: Exception) { false }
                            (matchesAddress || matchesFp) && (currentTime - peer.lastSeen < 120_000L)
                        }
                        val isOnline = matchedPeer != null
                        PremiumContactRow(
                            contact = contact,
                            matchedPeer = matchedPeer,
                            isOnline = isOnline,
                            onClick = { navController.navigate("chat/${contact.id}") { launchSingleTop = true } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PremiumContactRow(
    contact: Contact,
    matchedPeer: com.ghostprotocol.ble.DiscoveredPeer?,
    isOnline: Boolean,
    onClick: () -> Unit
) {
    val T = GhostTheme
    val ed25519Bytes = remember(contact.ed25519PubKey) {
        try { Base64.decode(contact.ed25519PubKey, Base64.NO_WRAP) } catch (_: Exception) { null }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Signature Ghost Avatar with Ethereal Ring (animated violet for verified, slate for unverified)
        GhostAvatar(
            pubkey = ed25519Bytes,
            name = contact.name,
            size = T.AvatarMedium,
            isMutuallyVerified = contact.isVerified
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = T.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${contact.id.take(6)}",
                    fontSize = 12.sp,
                    color = T.TextMuted,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                // Live Radio RF Proximity Wave
                RadioProximityWave(
                    rssi = matchedPeer?.rssi,
                    isOnline = isOnline
                )
            }
        }
    }
}
