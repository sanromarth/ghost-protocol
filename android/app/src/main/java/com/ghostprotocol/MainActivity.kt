package com.ghostprotocol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ghostprotocol.ui.ChatScreen
import com.ghostprotocol.ui.ContactListScreen
import com.ghostprotocol.ui.GhostTheme
import com.ghostprotocol.ui.QRScanScreen
import com.ghostprotocol.ui.QRShowScreen
import com.ghostprotocol.ui.UsernameSetupScreen
import com.ghostprotocol.ui.SettingsScreen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Start service as long as BLE permissions are granted — CAMERA is optional
        val bleGranted = permissions.entries
            .filter { it.key != Manifest.permission.CAMERA && it.key != Manifest.permission.POST_NOTIFICATIONS }
            .all { it.value }
        if (bleGranted) {
            startGhostService()
        }
    }

    override fun onResume() {
        super.onResume()
        IdentityManager.init(this)
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            startGhostService()
            try {
                com.ghostprotocol.ble.BleManager.setLocalFingerprint(IdentityManager.getFingerprint())
                com.ghostprotocol.ble.BleManager.start(applicationContext)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error starting BleManager in onResume: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IdentityManager.init(this)

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        checkPermissionsAndStartService()

        val T = GhostTheme

        setContent {
            var hasUsername by remember {
                mutableStateOf(
                    getSharedPreferences("ghost_identity", MODE_PRIVATE)
                        .getString("GHOST_USERNAME", null) != null
                )
            }

            val ghostColorScheme = darkColorScheme(
                primary = T.Purple,
                onPrimary = Color.White,
                background = T.Surface0,
                surface = T.Surface0,
                surfaceVariant = T.Surface2,
                onBackground = T.TextPrimary,
                onSurface = T.TextPrimary,
                onSurfaceVariant = T.TextSecondary
            )

            MaterialTheme(colorScheme = ghostColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = T.Surface0
                ) {
                    if (!hasUsername) {
                        UsernameSetupScreen(onUsernameSet = { hasUsername = true })
                    } else {
                        val navController = rememberNavController()

                        NavHost(
                            navController = navController,
                            startDestination = "contacts",
                            enterTransition = { slideInVertically(tween(250)) { it / 3 } + fadeIn(tween(250)) },
                            exitTransition = { fadeOut(tween(150)) },
                            popEnterTransition = { fadeIn(tween(200)) },
                            popExitTransition = { slideOutVertically(tween(250)) { it / 3 } + fadeOut(tween(200)) }
                        ) {
                            composable("contacts") { ContactListScreen(navController) }
                            composable("chat/{contactId}") { backStackEntry ->
                                val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
                                ChatScreen(contactId = contactId, navController = navController)
                            }
                            composable("qr_show") { QRShowScreen(navController) }
                            composable("qr_scan") { QRScanScreen(navController) }
                            composable("settings") { SettingsScreen(navController) }
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionsAndStartService() {
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requiredPermissions.add(Manifest.permission.CAMERA)
        // Android 13+ requires runtime notification permission for foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startGhostService()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startGhostService() {
        val serviceIntent = Intent(this, GhostService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Request battery optimization exclusion so Android doesn't kill the service
        requestBatteryOptimizationExclusion()
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
