package com.ghostprotocol.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ghostprotocol.ble.BleManager
import com.ghostprotocol.crypto.ShortCode
import com.ghostprotocol.discovery.ShortCodeSearchResult
import com.ghostprotocol.discovery.ShortCodeSearchStatus
import com.ghostprotocol.ui.GhostTheme as T

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortCodeInputScreen(navController: NavController) {
    var word1 by remember { mutableStateOf("") }
    var word2 by remember { mutableStateOf("") }
    var word3 by remember { mutableStateOf("") }
    var numberStr by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val discoveryManager = BleManager.discoveryManager
    val shortCodeManager = BleManager.shortCodeManager
    val searchResult by (discoveryManager?.shortCodeSearchState?.collectAsState()
        ?: remember { mutableStateOf(ShortCodeSearchResult(ShortCodeSearchStatus.IDLE)) })

    val isSearchEnabled = word1.isNotBlank() && word2.isNotBlank() && word3.isNotBlank() && numberStr.length == 4

    // Clean search state when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            discoveryManager?.clearShortCodeSearch()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find by Short Code", fontWeight = FontWeight.Bold) },
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Enter a peer's 24-hour verification code to connect across the mesh.",
                style = MaterialTheme.typography.bodySmall,
                color = T.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            // 4 Input fields row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = word1,
                    onValueChange = { if (it.length <= 12) { word1 = it.trim().uppercase(); errorMessage = null } },
                    label = { Text("Word 1", fontSize = 11.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Next
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = T.Purple,
                        unfocusedBorderColor = T.Surface3,
                        focusedTextColor = T.TextPrimary,
                        unfocusedTextColor = T.TextPrimary
                    ),
                    modifier = Modifier.weight(1.1f)
                )

                OutlinedTextField(
                    value = word2,
                    onValueChange = { if (it.length <= 12) { word2 = it.trim().uppercase(); errorMessage = null } },
                    label = { Text("Word 2", fontSize = 11.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Next
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = T.Purple,
                        unfocusedBorderColor = T.Surface3,
                        focusedTextColor = T.TextPrimary,
                        unfocusedTextColor = T.TextPrimary
                    ),
                    modifier = Modifier.weight(1.1f)
                )

                OutlinedTextField(
                    value = word3,
                    onValueChange = { if (it.length <= 12) { word3 = it.trim().uppercase(); errorMessage = null } },
                    label = { Text("Word 3", fontSize = 11.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Next
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = T.Purple,
                        unfocusedBorderColor = T.Surface3,
                        focusedTextColor = T.TextPrimary,
                        unfocusedTextColor = T.TextPrimary
                    ),
                    modifier = Modifier.weight(1.1f)
                )

                OutlinedTextField(
                    value = numberStr,
                    onValueChange = {
                        val digits = it.filter { ch -> ch.isDigit() }
                        if (digits.length <= 4) {
                            numberStr = digits
                            errorMessage = null
                        }
                    },
                    label = { Text("0000", fontSize = 11.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = T.Purple,
                        unfocusedBorderColor = T.Surface3,
                        focusedTextColor = T.TextPrimary,
                        unfocusedTextColor = T.TextPrimary
                    ),
                    modifier = Modifier.weight(0.9f)
                )
            }

            AnimatedVisibility(visible = errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    color = T.Failed,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    val wordlist = shortCodeManager?.wordlist ?: emptyList()
                    val w1 = word1.lowercase()
                    val w2 = word2.lowercase()
                    val w3 = word3.lowercase()
                    val num = numberStr.toIntOrNull()

                    if (wordlist.size != 2048) {
                        errorMessage = "BIP-39 dictionary error. Please try again."
                        return@Button
                    }

                    if (!wordlist.contains(w1) || !wordlist.contains(w2) || !wordlist.contains(w3) || num == null) {
                        errorMessage = "Invalid code. Check spelling and try again."
                        return@Button
                    }

                    val epochDay = System.currentTimeMillis() / 86_400_000L
                    val code = ShortCode(word1, word2, word3, num, epochDay)
                    errorMessage = null
                    discoveryManager?.initiateShortCodeSearch(code)
                },
                enabled = isSearchEnabled && searchResult.status != ShortCodeSearchStatus.SEARCHING_NEARBY,
                colors = ButtonDefaults.buttonColors(
                    containerColor = T.Purple,
                    disabledContainerColor = T.Surface2,
                    contentColor = Color.White,
                    disabledContentColor = T.TextMuted
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Search Mesh", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Live Search Results & States
            when (searchResult.status) {
                ShortCodeSearchStatus.SEARCHING_NEARBY -> {
                    PulsingSearchBox(text = "Searching nearby mesh...")
                }
                ShortCodeSearchStatus.SPRAYED_TO_MESH -> {
                    PulsingSearchBox(text = "Broadcasting through mesh...\nWill notify when found.")
                }
                ShortCodeSearchStatus.FOUND -> {
                    Card(
                        shape = RoundedCornerShape(T.RadiusCard),
                        colors = CardDefaults.cardColors(containerColor = T.Surface1),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, T.Online),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Found", tint = T.Online, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Found Contact!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = T.TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${searchResult.name ?: "Peer"} (#${searchResult.handle ?: ""})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = T.TextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Mutually verified and added to your contacts.",
                                style = MaterialTheme.typography.bodySmall,
                                color = T.Online,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { navController.popBackStack() },
                                colors = ButtonDefaults.buttonColors(containerColor = T.Purple, contentColor = Color.White),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Done", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                ShortCodeSearchStatus.TIMED_OUT -> {
                    Card(
                        shape = RoundedCornerShape(T.RadiusCard),
                        colors = CardDefaults.cardColors(containerColor = T.Surface1),
                        border = androidx.compose.foundation.BorderStroke(1.dp, T.Surface3),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = "Timeout", tint = Color(0xFFFFB703), modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Search Timed Out",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = T.TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = searchResult.error ?: "No response. The user may be out of range or the code may have expired.",
                                style = MaterialTheme.typography.bodySmall,
                                color = T.TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                ShortCodeSearchStatus.NOT_FOUND -> {
                    Card(
                        shape = RoundedCornerShape(T.RadiusCard),
                        colors = CardDefaults.cardColors(containerColor = T.Surface1),
                        border = androidx.compose.foundation.BorderStroke(1.dp, T.Failed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = searchResult.error ?: "Could not resolve short code.",
                                style = MaterialTheme.typography.bodySmall,
                                color = T.Failed,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                ShortCodeSearchStatus.IDLE -> {}
            }
        }
    }
}

@Composable
private fun PulsingSearchBox(text: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Card(
        shape = RoundedCornerShape(T.RadiusCard),
        colors = CardDefaults.cardColors(containerColor = T.Surface1),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, T.Purple.copy(alpha = alpha)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = T.Purple, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = T.TextPrimary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
        }
    }
}
