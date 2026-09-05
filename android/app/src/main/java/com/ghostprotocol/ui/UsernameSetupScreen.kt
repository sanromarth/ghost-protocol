package com.ghostprotocol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostprotocol.IdentityManager

@Composable
fun UsernameSetupScreen(onUsernameSet: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val myFingerprint = remember {
        try {
            IdentityManager.getContactId().take(8)
        } catch (_: Exception) {
            "GHOST-ID"
        }
    }

    val validateUsername = { name: String ->
        when {
            name.length !in 1..20 -> "Must be 1-20 characters"
            name.startsWith(" ") || name.endsWith(" ") -> "Cannot have leading or trailing spaces"
            !name.matches(Regex("^[A-Za-z0-9 _-]+$")) -> "Only letters, numbers, spaces, hyphens, underscores allowed"
            else -> null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = GhostTheme.Surface0
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Real-time dynamic GhostAvatar preview
            GhostAvatar(
                pubkey = null,
                name = if (username.isNotBlank()) username else "Ghost",
                size = 80.dp,
                isMutuallyVerified = false,
                animateEtherealRing = false
            )

            Spacer(modifier = Modifier.height(GhostTheme.SpaceMd))

            Text(
                text = "INITIALIZE IDENTITY",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = GhostTheme.PurpleLight,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(GhostTheme.SpaceXs))

            Text(
                text = "Choose Mesh Call-sign",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = GhostTheme.TextPrimary
            )

            Spacer(modifier = Modifier.height(GhostTheme.SpaceSm))

            Text(
                text = "Your call-sign is broadcast locally to nearby mesh peers. End-to-end cryptographic keys are generated offline on this device.",
                fontSize = 13.sp,
                color = GhostTheme.TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(GhostTheme.SpaceLg))

            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    error = validateUsername(it)
                },
                placeholder = { Text("e.g. GhostOperator", color = GhostTheme.TextMuted) },
                label = { Text("Call-sign / Username", color = GhostTheme.TextSecondary) },
                isError = error != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = GhostTheme.Surface1,
                    unfocusedContainerColor = GhostTheme.Surface1,
                    focusedBorderColor = GhostTheme.Purple,
                    unfocusedBorderColor = GhostTheme.BorderLight,
                    focusedTextColor = GhostTheme.TextPrimary,
                    unfocusedTextColor = GhostTheme.TextPrimary,
                    cursorColor = GhostTheme.Purple
                ),
                shape = RoundedCornerShape(GhostTheme.RadiusInput)
            )

            if (error != null) {
                Text(
                    text = error!!,
                    color = GhostTheme.Failed,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(GhostTheme.SpaceSm))

            Text(
                text = "Cryptographic Device ID: #$myFingerprint",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = GhostTheme.TextMuted
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    IdentityManager.setDisplayName(username.trim())
                    onUsernameSet()
                },
                enabled = username.isNotBlank() && error == null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GhostTheme.Purple,
                    disabledContainerColor = GhostTheme.Surface2,
                    contentColor = GhostTheme.TextOnPurple,
                    disabledContentColor = GhostTheme.TextMuted
                ),
                shape = RoundedCornerShape(GhostTheme.RadiusInput),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GhostTheme.MinTouchTarget)
            ) {
                Text(
                    text = "ENTER MESH",
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
