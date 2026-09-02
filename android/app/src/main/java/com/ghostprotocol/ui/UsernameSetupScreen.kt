package com.ghostprotocol.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun UsernameSetupScreen(onUsernameSet: () -> Unit) {
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

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
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Welcome to GHOST",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Choose your identity",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(32.dp))
            
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    error = validateUsername(it)
                },
                label = { Text("Username") },
                isError = error != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            
            if (error != null) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 4.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = {
                    com.ghostprotocol.IdentityManager.setDisplayName(username)
                    onUsernameSet()
                },
                enabled = username.isNotEmpty() && validateUsername(username) == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    }
}
