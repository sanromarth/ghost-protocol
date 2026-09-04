package com.ghostprotocol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostprotocol.GhostService
import com.ghostprotocol.data.Contact
import kotlinx.coroutines.launch
import android.util.Base64

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntroductionReviewBottomSheet(
    introducedContactId: String,
    onDismiss: () -> Unit,
    onContactAdded: (() -> Unit)? = null
) {
    val T = GhostTheme
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val introductionHandler = GhostService.activeIntroductionHandler
    val pending = remember(introducedContactId) {
        introductionHandler?.getPendingIntroduction(introducedContactId)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = T.SurfaceOverlay,
        dragHandle = { SheetHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Cryptographic Introduction",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = T.PurpleLight,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (pending == null) {
                Text(
                    text = "This introduction has expired or is no longer valid. Ask the voucher to send it again.",
                    fontSize = 14.sp,
                    color = T.TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 24.dp)
                )

                Button(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = T.Surface2),
                    shape = RoundedCornerShape(T.RadiusInput),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Close", color = T.TextPrimary)
                }
                return@ModalBottomSheet
            }

            val envelope = pending.envelope
            val voucher = pending.voucherContact
            val voucherPub = remember(voucher.ed25519PubKey) {
                try { Base64.decode(voucher.ed25519PubKey, Base64.NO_WRAP) } catch (_: Exception) { null }
            }

            // Voucher attribution row with GhostAura
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(T.Surface1, RoundedCornerShape(12.dp))
                    .border(1.dp, T.Surface2, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GhostAvatar(
                    pubkey = voucherPub,
                    name = voucher.name,
                    size = T.AvatarSmall,
                    isMutuallyVerified = true
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Vouched by ${voucher.name}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = T.TextPrimary
                    )
                    Text(
                        text = "Mutually Verified Peer • #${voucher.id.take(6)}",
                        fontSize = 11.sp,
                        color = T.PurpleLight
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Introduced contact card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(T.Surface1, RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF3F3F46), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Slate-bordered avatar (isMutuallyVerified = false)
                GhostAvatar(
                    pubkey = envelope.introducedEd25519Pub,
                    name = envelope.introducedName,
                    size = T.AvatarLarge,
                    isMutuallyVerified = false
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = envelope.introducedName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = T.TextPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .border(1.dp, Color(0xFF3F3F46), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "INTRODUCED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFA1A1AA),
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "#${introducedContactId.take(6)}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = T.TextSecondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                val fpHex = envelope.introducedEd25519Pub.take(8).joinToString("") { "%02x".format(it) }
                Text(
                    text = "Ed: $fpHex...",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = T.TextMuted
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "This identity was cryptographically signed by ${voucher.name}. It has not been mutually verified.",
                fontSize = 12.sp,
                color = T.TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        introductionHandler?.declineIntroduction(introducedContactId)
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(T.RadiusInput),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3F3F46)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = T.TextSecondary)
                ) {
                    Text("Decline", fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        scope.launch {
                            val success = introductionHandler?.acceptIntroduction(introducedContactId) ?: false
                            if (success) {
                                onContactAdded?.invoke()
                            }
                            sheetState.hide()
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(T.RadiusInput),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = T.Purple,
                        contentColor = Color.White
                    )
                ) {
                    Text("Add Contact", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
