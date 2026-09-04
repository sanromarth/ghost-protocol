package com.ghostprotocol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ghostprotocol.IdentityManager
import com.ghostprotocol.data.Contact
import com.ghostprotocol.data.GhostDatabase
import com.ghostprotocol.data.GroupEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoBottomSheet(
    groupId: String,
    onDismiss: () -> Unit,
    onLeaveOrDelete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { GhostDatabase.getInstance(context) }

    var group by remember { mutableStateOf<GroupEntity?>(null) }
    var members by remember { mutableStateOf<List<Contact>>(emptyList()) }
    val myContactId = remember { IdentityManager.getContactId() }

    LaunchedEffect(groupId) {
        withContext(Dispatchers.IO) {
            val g = db.groupDao().getById(groupId)
            group = g
            if (g != null) {
                val memberIds = try {
                    val arr = JSONArray(g.memberContactIdsJson)
                    List(arr.length()) { arr.getString(it) }
                } catch (_: Exception) {
                    emptyList()
                }

                val list = mutableListOf<Contact>()
                for (id in memberIds) {
                    val c = db.contactDao().getByContactId(id)
                    if (c != null) {
                        list.add(c)
                    } else if (id == myContactId) {
                        list.add(
                            Contact(
                                id = myContactId,
                                name = IdentityManager.getDisplayName(),
                                ed25519PubKey = IdentityManager.getEd25519PubKeyBase64(),
                                x25519PubKey = IdentityManager.getX25519PubKeyBase64(),
                                isVerified = true
                            )
                        )
                    }
                }
                members = list
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = GhostTheme.SurfaceOverlay,
        dragHandle = { BottomSheetDefaults.DragHandle(color = GhostTheme.Surface3) }
    ) {
        val currentGroup = group
        if (currentGroup == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = GhostTheme.Purple)
            }
            return@ModalBottomSheet
        }

        val isCreator = currentGroup.creatorContactId == myContactId
        val dateFormat = remember { SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GhostTheme.SpaceMd)
                .padding(bottom = GhostTheme.SpaceLg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HexagonAvatar(name = currentGroup.name, size = 64.dp)

            Spacer(modifier = Modifier.height(GhostTheme.SpaceSm))

            Text(
                text = currentGroup.name,
                color = GhostTheme.TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )

            Text(
                text = "Created ${dateFormat.format(Date(currentGroup.createdAt))}",
                color = GhostTheme.TextMuted,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(GhostTheme.SpaceMd))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CELL ROSTER (${members.size}/8)",
                    color = GhostTheme.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "PAIRWISE E2E",
                    color = GhostTheme.PurpleLight,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.height(GhostTheme.SpaceSm))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
            ) {
                items(members, key = { it.id }) { member ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = GhostTheme.SpaceXs)
                            .clip(RoundedCornerShape(GhostTheme.RadiusCard))
                            .background(GhostTheme.Surface1)
                            .padding(horizontal = GhostTheme.SpaceSm, vertical = GhostTheme.SpaceSm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HexagonAvatar(name = member.name, size = 32.dp)
                        Spacer(modifier = Modifier.width(GhostTheme.SpaceSm))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = member.name,
                                    color = GhostTheme.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                if (member.id == myContactId) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "YOU",
                                        color = GhostTheme.PurpleLight,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    )
                                }
                                if (member.id == currentGroup.creatorContactId) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "CREATOR",
                                        color = Color(0xFFFFB703),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                            Text(
                                text = "ID: ${member.id.take(8)}",
                                color = GhostTheme.TextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(GhostTheme.SpaceLg))

            // Action Buttons
            if (isCreator) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                db.groupDao().delete(groupId)
                                db.groupMessageDao().deleteForGroup(groupId)
                            }
                            onDismiss()
                            onLeaveOrDelete()
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = GhostTheme.Failed
                    ),
                    shape = RoundedCornerShape(GhostTheme.RadiusCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "DELETE CELL",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                db.groupDao().deactivate(groupId)
                            }
                            onDismiss()
                            onLeaveOrDelete()
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = GhostTheme.Failed
                    ),
                    shape = RoundedCornerShape(GhostTheme.RadiusCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "LEAVE CELL",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
