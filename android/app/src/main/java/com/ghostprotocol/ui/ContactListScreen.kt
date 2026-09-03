package com.ghostprotocol.ui

import android.app.Application
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
                        IconButton(onClick = { navController.navigate("settings") }) {
                            Icon(Icons.Default.Settings, "Settings", tint = T.TextSecondary)
                        }
                    }

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
                        val isOnline = blePeers.any { peer ->
                            val matchesAddress = contact.bleAddress != null && peer.address == contact.bleAddress
                            val matchesFp = peer.fingerprint != null && contact.ed25519PubKey != null && try {
                                val contactFp = java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(Base64.decode(contact.ed25519PubKey, Base64.NO_WRAP))
                                    .copyOfRange(0, 4)
                                peer.fingerprint.contentEquals(contactFp)
                            } catch (_: Exception) { false }
                            (matchesAddress || matchesFp) && (currentTime - peer.lastSeen < 120_000L)
                        }
                        PremiumContactRow(
                            contact = contact,
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
fun PremiumContactRow(contact: Contact, isOnline: Boolean, onClick: () -> Unit) {
    val T = GhostTheme
    val avatar = AvatarGenerator.fromPubkey(
        Base64.decode(contact.ed25519PubKey, Base64.NO_WRAP),
        contact.name
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with online indicator
        Box(modifier = Modifier.size(T.AvatarMedium)) {
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
                    fontSize = 20.sp
                )
            }
            // Online/offline dot with ring
            if (contact.bleAddress != null) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.BottomEnd)
                        .background(T.Surface0, CircleShape)
                        .padding(2.dp)
                        .background(
                            if (isOnline) T.Online else T.TextMuted,
                            CircleShape
                        )
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = contact.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = T.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "#${contact.id.take(6)}",
                fontSize = 12.sp,
                color = T.TextMuted
            )
        }
    }
}
