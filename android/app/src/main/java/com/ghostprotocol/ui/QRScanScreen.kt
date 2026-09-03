package com.ghostprotocol.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.ghostprotocol.data.Contact
import com.ghostprotocol.data.GhostDatabase
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRScanScreen(navController: NavController) {
    val T = GhostTheme
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var scannedContact by remember { mutableStateOf<Contact?>(null) }
    val scanGate = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var showSuccessCard by remember { mutableStateOf(false) }

    // Scanning line animation
    val infiniteTransition = rememberInfiniteTransition(label = "scan_line")
    val scanLineProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanLine"
    )

    // Corner bracket pulse
    val cornerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "corner"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val scanner = BarcodeScanning.getClient()
                        val executor = Executors.newSingleThreadExecutor()
                        
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also {
                                it.setAnalyzer(executor) { imageProxy ->
                                if (scanGate.get()) {
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }
                                    val mediaImage = imageProxy.image ?: run {
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }
                                    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                    
                                    scanner.process(inputImage)
                                        .addOnSuccessListener { barcodes ->
                                            for (barcode in barcodes) {
                                                barcode.rawValue?.let { value ->
                                                    if (value.startsWith("GHOST:") && scanGate.compareAndSet(false, true)) {
                                                        try {
                                                            val base64Data = value.substringAfter("GHOST:")
                                                            val payload = Base64.decode(base64Data, Base64.NO_WRAP)
                                                            if (payload.size >= 64) {
                                                                val ed25519Pub = payload.sliceArray(0 until 32)
                                                                val x25519Pub = payload.sliceArray(32 until 64)
                                                                val nameBytes = payload.sliceArray(64 until payload.size)
                                                                val name = String(nameBytes, Charsets.UTF_8)
                                                                
                                                                val md = MessageDigest.getInstance("SHA-256")
                                                                val hash = md.digest(ed25519Pub)
                                                                val id = hash.sliceArray(0 until 8).joinToString("") { "%02x".format(it) }
                                                                
                                                                // Prevent adding yourself as a contact
                                                                val myId = com.ghostprotocol.IdentityManager.getContactId()
                                                                if (id == myId) {
                                                                    scope.launch(Dispatchers.Main) {
                                                                        android.widget.Toast.makeText(context, "That's your own QR code!", android.widget.Toast.LENGTH_SHORT).show()
                                                                    }
                                                                    scanGate.set(false)
                                                                    return@let
                                                                }
                                                                
                                                                val contact = Contact(
                                                                    id = id,
                                                                    name = name,
                                                                    ed25519PubKey = Base64.encodeToString(ed25519Pub, Base64.NO_WRAP),
                                                                    x25519PubKey = Base64.encodeToString(x25519Pub, Base64.NO_WRAP),
                                                                    isVerified = true,
                                                                    createdAt = System.currentTimeMillis()
                                                                )
                                                                scannedContact = contact

                                                                scope.launch(Dispatchers.IO) {
                                                                    val db = GhostDatabase.getInstance(context)
                                                                    db.contactDao().insert(contact)

                                                                    val existingMessages = db.messageDao().getMessagesForContactOnce(id)
                                                                    val hasVerifiedMsg = existingMessages.any { it.content.startsWith("* verified ") }
                                                                    if (!hasVerifiedMsg) {
                                                                        val verifiedMsg = com.ghostprotocol.data.MessageEntity(
                                                                            id = java.util.UUID.randomUUID().toString(),
                                                                            contactId = id,
                                                                            content = "* verified $name *",
                                                                            isOutgoing = false,
                                                                            isVerified = true,
                                                                            status = com.ghostprotocol.data.MessageEntity.STATUS_DELIVERED,
                                                                            timestamp = System.currentTimeMillis()
                                                                        )
                                                                        db.messageDao().insert(verifiedMsg)
                                                                    }

                                                                    val preScanVerification = com.ghostprotocol.GhostService.pendingVerifications.remove(id) != null
                                                                    val alreadyReceivedVerification = preScanVerification || existingMessages.any { it.content.startsWith("* verified ") }
                                                                    val alreadyMutuallyVerified = existingMessages.any { it.content.startsWith("* mutual verification with ") }

                                                                    if (alreadyReceivedVerification && !alreadyMutuallyVerified) {
                                                                        val mutualMsg = com.ghostprotocol.data.MessageEntity(
                                                                            id = java.util.UUID.randomUUID().toString(),
                                                                            contactId = id,
                                                                            content = "* mutual verification with $name *",
                                                                            isOutgoing = false,
                                                                            isVerified = true,
                                                                            status = com.ghostprotocol.data.MessageEntity.STATUS_DELIVERED,
                                                                            timestamp = System.currentTimeMillis()
                                                                        )
                                                                        db.messageDao().insert(mutualMsg)
                                                                        com.ghostprotocol.util.NotificationHelper.showMutualVerificationNotification(context, name)
                                                                    }

                                                                    // Send verification handshake packet over BLE to peer
                                                                    try {
                                                                        val myName = com.ghostprotocol.IdentityManager.getDisplayName()
                                                                        val myEd25519PubKey = com.ghostprotocol.IdentityManager.getEd25519PubKey()
                                                                        val wireText = if (alreadyReceivedVerification) "$myName\u0000* mutual verification with $myName *" else "$myName\u0000* verified $myName *"
                                                                        val plaintextBytes = wireText.toByteArray(Charsets.UTF_8)
                                                                        val payload = myEd25519PubKey + plaintextBytes
                                                                        val signature = com.ghostprotocol.crypto.GhostCrypto.sign(com.ghostprotocol.IdentityManager.getEd25519Seed(), payload)
                                                                        val ciphertext = com.ghostprotocol.crypto.GhostCrypto.encrypt(x25519Pub, payload + signature)

                                                                        val targetAddress = contact.bleAddress ?: run {
                                                                            val fp = MessageDigest.getInstance("SHA-256").digest(ed25519Pub).copyOfRange(0, 4)
                                                                            com.ghostprotocol.ble.BleManager.peers.value.find { it.fingerprint?.contentEquals(fp) == true }?.address
                                                                        }
                                                                        if (targetAddress != null) {
                                                                            com.ghostprotocol.ble.BleManager.sendMessage(targetAddress, ciphertext) {}
                                                                        }
                                                                    } catch (e: Exception) {
                                                                        e.printStackTrace()
                                                                    }

                                                                    // Automatic transition: like Bitchat, immediately open your QR code so the other device can scan back!
                                                                    scope.launch(Dispatchers.Main) {
                                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                        android.widget.Toast.makeText(context, "Verified $name! Showing your QR code...", android.widget.Toast.LENGTH_SHORT).show()
                                                                        delay(400)
                                                                        navController.navigate("qr_show") {
                                                                            popUpTo("contacts")
                                                                        }
                                                                    }
                                                                }
                                                            } else {
                                                                scanGate.set(false)
                                                            }
                                                        } catch (e: Exception) {
                                                            scanGate.set(false)
                                                            e.printStackTrace()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        .addOnCompleteListener { imageProxy.close() }
                                }
                            }

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Dark overlay with cutout viewfinder
            Canvas(modifier = Modifier.fillMaxSize()) {
                val viewfinderSize = size.minDimension * 0.65f
                val left = (size.width - viewfinderSize) / 2f
                val top = (size.height - viewfinderSize) / 2f - 40f

                // Semi-transparent dark overlay
                drawRect(Color.Black.copy(alpha = 0.6f))
                
                // Clear the viewfinder area
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(left, top),
                    size = Size(viewfinderSize, viewfinderSize),
                    cornerRadius = CornerRadius(24f, 24f),
                    blendMode = BlendMode.Clear
                )

                // Corner brackets (purple)
                val bracketLen = 40f
                val bracketWidth = 4f
                val purple = Color(0xFF7C3AED).copy(alpha = cornerAlpha)
                val stroke = Stroke(width = bracketWidth, cap = StrokeCap.Round)
                val r = 16f

                // Top-left corner
                drawPath(Path().apply {
                    moveTo(left, top + bracketLen)
                    lineTo(left, top + r)
                    quadraticBezierTo(left, top, left + r, top)
                    lineTo(left + bracketLen, top)
                }, color = purple, style = stroke)

                // Top-right corner
                drawPath(Path().apply {
                    moveTo(left + viewfinderSize - bracketLen, top)
                    lineTo(left + viewfinderSize - r, top)
                    quadraticBezierTo(left + viewfinderSize, top, left + viewfinderSize, top + r)
                    lineTo(left + viewfinderSize, top + bracketLen)
                }, color = purple, style = stroke)

                // Bottom-left corner
                drawPath(Path().apply {
                    moveTo(left, top + viewfinderSize - bracketLen)
                    lineTo(left, top + viewfinderSize - r)
                    quadraticBezierTo(left, top + viewfinderSize, left + r, top + viewfinderSize)
                    lineTo(left + bracketLen, top + viewfinderSize)
                }, color = purple, style = stroke)

                // Bottom-right corner
                drawPath(Path().apply {
                    moveTo(left + viewfinderSize - bracketLen, top + viewfinderSize)
                    lineTo(left + viewfinderSize - r, top + viewfinderSize)
                    quadraticBezierTo(left + viewfinderSize, top + viewfinderSize, left + viewfinderSize, top + viewfinderSize - r)
                    lineTo(left + viewfinderSize, top + viewfinderSize - bracketLen)
                }, color = purple, style = stroke)

                // Scanning line (horizontal, moves down)
                if (!scanGate.get()) {
                    val lineY = top + (viewfinderSize * scanLineProgress)
                    if (lineY > top && lineY < top + viewfinderSize) {
                        drawLine(
                            color = Color(0xFF7C3AED).copy(alpha = 0.6f),
                            start = Offset(left + 16f, lineY),
                            end = Offset(left + viewfinderSize - 16f, lineY),
                            strokeWidth = 2f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        } else {
            // No camera permission
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(T.Surface0),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📷", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Camera permission required\nto scan QR codes",
                        color = T.TextSecondary,
                        textAlign = TextAlign.Center,
                        fontSize = 16.sp
                    )
                }
            }
        }

        // Top bar (floating over camera)
        TopAppBar(
            title = {
                Text("Scan QR Code", color = Color.White, fontWeight = FontWeight.SemiBold)
            },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            },
            actions = {
                TextButton(onClick = {
                    navController.navigate("qr_show") {
                        popUpTo("qr_scan") { inclusive = true }
                    }
                }) {
                    Text("My QR", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            modifier = Modifier.statusBarsPadding()
        )

        // Instruction text below viewfinder
        if (!showSuccessCard) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 120.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "Point at a GHOST QR code",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Success card overlay
        AnimatedVisibility(
            visible = showSuccessCard,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                scannedContact?.let { contact ->
                    val avatar = AvatarGenerator.fromPubkey(
                        Base64.decode(contact.ed25519PubKey, Base64.NO_WRAP),
                        contact.name
                    )
                    val contactHandle = run {
                        val hash = MessageDigest.getInstance("SHA-256")
                            .digest(Base64.decode(contact.ed25519PubKey, Base64.NO_WRAP))
                        "#" + hash.take(3).joinToString("") { "%02x".format(it) }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp),
                        colors = CardDefaults.cardColors(containerColor = T.Surface1),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Success icon
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = "Success",
                                tint = T.Online,
                                modifier = Modifier.size(48.dp)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Contact Added",
                                color = T.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            // Avatar
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .background(avatar.backgroundColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = avatar.initial.toString(),
                                    color = avatar.textColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 28.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = contact.name,
                                color = T.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 20.sp
                            )
                            Text(
                                text = contactHandle,
                                color = T.TextMuted,
                                fontSize = 14.sp
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            // "Show My QR" button
                            Button(
                                onClick = {
                                    showSuccessCard = false
                                    navController.navigate("qr_show") {
                                        popUpTo("contacts")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = T.Purple),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    text = "Show My QR Code",
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // "Done" text button
                            TextButton(
                                onClick = {
                                    showSuccessCard = false
                                    navController.popBackStack()
                                }
                            ) {
                                Text(
                                    text = "Done",
                                    color = T.TextSecondary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
