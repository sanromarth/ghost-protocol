package com.ghostprotocol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostprotocol.GhostService
import com.ghostprotocol.IdentityManager
import com.ghostprotocol.data.Contact
import com.ghostprotocol.data.GhostDatabase
import com.ghostprotocol.data.GroupEntity
import com.ghostprotocol.data.GroupMessageEntity
import com.ghostprotocol.group.GroupMessageSender
import com.ghostprotocol.group.GroupProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupCreationScreen(
    onBack: () -> Unit,
    onGroupCreated: (groupId: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { GhostDatabase.getInstance(context) }
    val contacts by db.contactDao().getAll().collectAsState(initial = emptyList())

    val sender = remember {
        GhostService.activeGroupSender ?: GroupMessageSender(
            groupDao = db.groupDao(),
            contactDao = db.contactDao(),
            groupMessageDao = db.groupMessageDao(),
            routerProvider = { null }
        )
    }

    var groupName by remember { mutableStateOf("") }
    val selectedContactIds = remember { mutableStateListOf<String>() }

    // Hard limit: Up to 7 contacts + self = 8 members max. Minimum: 1 contact + self = 2 members.
    val maxSelectable = 7
    val isNameValid = groupName.trim().isNotEmpty()
    val isMemberCountValid = selectedContactIds.size in 1..maxSelectable
    val canCreate = isNameValid && isMemberCountValid

    val verifiedContacts = contacts.filter { it.isVerified }
    val unverifiedContacts = contacts.filter { !it.isVerified }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "CREATE CELL",
                            color = GhostTheme.TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "${selectedContactIds.size + 1} / 8 MEMBERS",
                            color = GhostTheme.PurpleLight,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = GhostTheme.TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = GhostTheme.Surface0)
            )
        },
        containerColor = GhostTheme.Surface0
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = GhostTheme.SpaceMd)
        ) {
            Spacer(modifier = Modifier.height(GhostTheme.SpaceSm))

            // Cell Group Name input
            OutlinedTextField(
                value = groupName,
                onValueChange = { if (it.length <= 32) groupName = it },
                label = { Text("Cell Name", color = GhostTheme.TextSecondary) },
                placeholder = { Text("e.g. Squad Alpha", color = GhostTheme.TextMuted) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GhostTheme.Purple,
                    unfocusedBorderColor = GhostTheme.Surface3,
                    focusedTextColor = GhostTheme.TextPrimary,
                    unfocusedTextColor = GhostTheme.TextPrimary,
                    cursorColor = GhostTheme.Purple
                ),
                shape = RoundedCornerShape(GhostTheme.RadiusCard),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(GhostTheme.SpaceMd))

            Text(
                text = "SELECT VERIFIED MEMBERS",
                color = GhostTheme.TextMuted,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(GhostTheme.SpaceSm))

            // Member selection list
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (verifiedContacts.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = GhostTheme.Surface1),
                            shape = RoundedCornerShape(GhostTheme.RadiusCard),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = GhostTheme.SpaceMd)
                        ) {
                            Column(
                                modifier = Modifier.padding(GhostTheme.SpaceMd),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "NO VERIFIED CONTACTS",
                                    color = GhostTheme.TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(GhostTheme.SpaceXs))
                                Text(
                                    text = "Cells require verified contacts. Verify nearby peers via Discovery or Short Code to add them.",
                                    color = GhostTheme.TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                } else {
                    items(verifiedContacts, key = { it.id }) { contact ->
                        val isSelected = selectedContactIds.contains(contact.id)
                        val canSelectMore = selectedContactIds.size < maxSelectable || isSelected

                        ContactSelectRow(
                            contact = contact,
                            isSelected = isSelected,
                            enabled = canSelectMore,
                            onToggle = {
                                if (isSelected) {
                                    selectedContactIds.remove(contact.id)
                                } else if (selectedContactIds.size < maxSelectable) {
                                    selectedContactIds.add(contact.id)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(GhostTheme.SpaceXs))
                    }
                }

                if (unverifiedContacts.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(GhostTheme.SpaceMd))
                        Text(
                            text = "UNVERIFIED (${unverifiedContacts.size}) - VERIFY TO ADD",
                            color = GhostTheme.TextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                        Spacer(modifier = Modifier.height(GhostTheme.SpaceXs))
                    }

                    items(unverifiedContacts, key = { "unverified_${it.id}" }) { contact ->
                        ContactSelectRow(
                            contact = contact,
                            isSelected = false,
                            enabled = false,
                            onToggle = {}
                        )
                        Spacer(modifier = Modifier.height(GhostTheme.SpaceXs))
                    }
                }
            }

            Spacer(modifier = Modifier.height(GhostTheme.SpaceSm))

            // Create Cell CTA button
            Button(
                onClick = {
                    if (!canCreate) return@Button
                    scope.launch {
                        val myContactId = IdentityManager.getContactId()
                        val myEd25519Pub = IdentityManager.getEd25519PubKey()
                        val timestamp = System.currentTimeMillis()
                        val groupId = GroupProtocol.generateGroupId(myEd25519Pub, timestamp)
                        val allMembers = (selectedContactIds + myContactId).distinct()
                        val json = JSONArray(allMembers).toString()

                        val group = GroupEntity(
                            groupId = groupId,
                            name = groupName.trim(),
                            creatorContactId = myContactId,
                            memberContactIdsJson = json,
                            createdAt = timestamp,
                            isActive = true
                        )

                        withContext(Dispatchers.IO) {
                            db.groupDao().insert(group)

                            val systemNotice = GroupMessageEntity(
                                groupId = groupId,
                                senderContactId = myContactId,
                                text = "* You created cell group \"${group.name}\" *",
                                timestamp = timestamp,
                                status = GroupMessageEntity.STATUS_DELIVERED
                            )
                            db.groupMessageDao().insert(systemNotice)

                            sender.sendGroupInvite(group)
                        }

                        onGroupCreated(groupId)
                    }
                },
                enabled = canCreate,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GhostTheme.Purple,
                    disabledContainerColor = GhostTheme.Surface2
                ),
                shape = RoundedCornerShape(GhostTheme.RadiusCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "CREATE CELL",
                    color = if (canCreate) GhostTheme.TextOnPurple else GhostTheme.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(GhostTheme.SpaceMd))
        }
    }
}

@Composable
private fun ContactSelectRow(
    contact: Contact,
    isSelected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GhostTheme.RadiusCard))
            .background(if (isSelected) GhostTheme.Surface2 else GhostTheme.Surface1)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = GhostTheme.SpaceMd, vertical = GhostTheme.SpaceSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HexagonAvatar(name = contact.name, size = 36.dp)

        Spacer(modifier = Modifier.width(GhostTheme.SpaceSm))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.name,
                color = GhostTheme.TextPrimary.copy(alpha = alpha),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(
                text = "ID: ${contact.id.take(8)}",
                color = GhostTheme.TextMuted.copy(alpha = alpha),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }

        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (isSelected) GhostTheme.Purple
                    else GhostTheme.Surface3.copy(alpha = alpha)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
