package com.ghostprotocol.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostprotocol.data.MessageEntity

/**
 * Quoted message preview bar shown above the input field when replying.
 */
@Composable
fun ReplyBar(
    quotedMessage: MessageEntity?,
    quotedSenderName: String,
    onCancel: () -> Unit,
    onTapPreview: () -> Unit
) {
    val T = GhostTheme

    AnimatedVisibility(
        visible = quotedMessage != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        quotedMessage?.let { msg ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(T.Surface2)
                    .clickable { onTapPreview() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Purple accent bar
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(T.Purple)
                )

                Spacer(modifier = Modifier.width(10.dp))

                // Quote content
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (msg.isOutgoing) "You" else quotedSenderName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = T.Purple,
                        maxLines = 1
                    )
                    Text(
                        text = msg.content,
                        fontSize = 13.sp,
                        color = T.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Cancel button
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Cancel reply",
                        tint = T.TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
