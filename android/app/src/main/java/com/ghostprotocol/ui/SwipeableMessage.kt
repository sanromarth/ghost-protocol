package com.ghostprotocol.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Telegram-style swipeable message wrapper.
 * 
 * - Swipe RIGHT → reply (primary action, like Signal/Telegram)
 * - Swipe LEFT → copy (secondary action)
 * 
 * Features:
 * - Rubber-band resistance past threshold
 * - Icon scales up and locks at threshold
 * - Haptic tick at threshold crossing
 * - Smooth spring snap-back
 */
@Composable
fun SwipeableMessage(
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val T = GhostTheme
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val density = LocalDensity.current
    
    // Lower threshold = more responsive (Signal uses ~60dp)
    val triggerThresholdPx = with(density) { 64.dp.toPx() }
    // Max drag distance with rubber-band
    val maxDragPx = with(density) { 100.dp.toPx() }
    
    var crossedThreshold by remember { mutableStateOf(false) }

    // rememberUpdatedState ensures pointerInput always sees current lambda refs
    val currentOnReply by rememberUpdatedState(onReply)
    val currentOnCopy by rememberUpdatedState(onCopy)

    Box(modifier = Modifier.fillMaxWidth()) {
        // ── Reply icon (left side, revealed on right swipe) ──
        if (offsetX.value > 4f) {
            val rawProgress = (offsetX.value / triggerThresholdPx).coerceIn(0f, 1.5f)
            val triggered = rawProgress >= 1f
            val iconScale = if (triggered) 1.15f else 0.5f + (rawProgress * 0.5f)
            val iconAlpha = if (triggered) 1f else rawProgress * 0.8f
            val bgAlpha = if (triggered) 0.9f else rawProgress * 0.5f

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(start = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .size(if (triggered) 40.dp else 36.dp)
                        .scale(iconScale)
                        .background(
                            T.Purple.copy(alpha = bgAlpha),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Reply,
                        contentDescription = "Reply",
                        tint = T.TextOnPurple.copy(alpha = iconAlpha),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ── Copy icon (right side, revealed on left swipe) ──
        if (offsetX.value < -4f) {
            val rawProgress = (abs(offsetX.value) / triggerThresholdPx).coerceIn(0f, 1.5f)
            val triggered = rawProgress >= 1f
            val iconScale = if (triggered) 1.15f else 0.5f + (rawProgress * 0.5f)
            val iconAlpha = if (triggered) 1f else rawProgress * 0.8f
            val bgAlpha = if (triggered) 0.9f else rawProgress * 0.5f

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .size(if (triggered) 40.dp else 36.dp)
                        .scale(iconScale)
                        .background(
                            T.Surface3.copy(alpha = bgAlpha),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy",
                        tint = T.TextSecondary.copy(alpha = iconAlpha),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Swipeable message content
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .graphicsLayer {
                    // Slight elevation when dragging
                    if (abs(offsetX.value) > 4f) {
                        shadowElevation = 4f
                    }
                }
                .pointerInput(currentOnReply, currentOnCopy) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                when {
                                    offsetX.value > triggerThresholdPx -> {
                                        // Trigger reply
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        currentOnReply()
                                    }
                                    offsetX.value < -triggerThresholdPx -> {
                                        // Trigger copy
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        currentOnCopy()
                                    }
                                }
                                // Spring back to center
                                offsetX.animateTo(
                                    0f,
                                    spring(
                                        dampingRatio = 0.6f,        // Bouncy but controlled
                                        stiffness = 400f            // Fast snap-back
                                    )
                                )
                                crossedThreshold = false
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                offsetX.animateTo(
                                    0f,
                                    spring(dampingRatio = 0.8f, stiffness = 600f)
                                )
                                crossedThreshold = false
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            // Apply rubber-band resistance past threshold — synchronous, no coroutine
                            val currentOffset = offsetX.value
                            val newRaw = currentOffset + dragAmount

                            val newValue = when {
                                // Right swipe: rubber-band past trigger
                                newRaw > triggerThresholdPx -> {
                                    val excess = newRaw - triggerThresholdPx
                                    triggerThresholdPx + excess * 0.3f // 30% of excess
                                }
                                // Left swipe: rubber-band past trigger
                                newRaw < -triggerThresholdPx -> {
                                    val excess = newRaw + triggerThresholdPx
                                    -triggerThresholdPx + excess * 0.3f
                                }
                                else -> newRaw
                            }.coerceIn(-maxDragPx, maxDragPx)

                            scope.launch { offsetX.snapTo(newValue) }

                            // Haptic tick when crossing threshold
                            val isOverThreshold = abs(newValue) > triggerThresholdPx
                            if (isOverThreshold && !crossedThreshold) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                crossedThreshold = true
                            } else if (!isOverThreshold && crossedThreshold) {
                                // Tick again when dragging back below threshold
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                crossedThreshold = false
                            }
                        }
                    )
                }
        ) {
            content()
        }
    }
}
