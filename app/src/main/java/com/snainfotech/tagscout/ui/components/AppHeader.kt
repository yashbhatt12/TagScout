package com.snainfotech.tagscout.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.Secondary

// Different timer states the header can show
sealed class TimerBadge {
    object None : TimerBadge()                       // No badge
    data class Live(val timeText: String) : TimerBadge()      // ● Live 2:45
    data class Paused(val timeText: String) : TimerBadge()    // ⏸ Paused 2:15
}

@Composable
fun AppHeader(
    title: String = "TagScout",
    showBackButton: Boolean = false,
    onBackClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    timerBadge: TimerBadge = TimerBadge.None,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(Primary, Secondary)
                )
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: Back button (optional) + Title
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBackButton) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }

            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Spacer pushes the rest to the right
        Spacer(modifier = Modifier.weight(1f))

        // Optional: Timer badge in the middle/right
        when (timerBadge) {
            is TimerBadge.Live -> {
                TimerBadgeView(
                    text = "● Live ${timerBadge.timeText}",
                    isLive = true
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            is TimerBadge.Paused -> {
                TimerBadgeView(
                    text = "⏸ Paused ${timerBadge.timeText}",
                    isLive = false
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            TimerBadge.None -> { /* nothing */ }
        }

        // Right side: Menu button (three dots)
        IconButton(onClick = onMenuClick) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Menu",
                tint = Color.White
            )
        }
    }
}

// The timer badge (live or paused)
@Composable
private fun TimerBadgeView(text: String, isLive: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.25f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ============================================
// PREVIEWS
// ============================================

@Preview(showBackground = true)
@Composable
fun AppHeaderDefaultPreview() {
    AppHeader()
}

@Preview(showBackground = true)
@Composable
fun AppHeaderWithBackPreview() {
    AppHeader(showBackButton = true)
}

@Preview(showBackground = true)
@Composable
fun AppHeaderLivePreview() {
    AppHeader(
        showBackButton = true,
        timerBadge = TimerBadge.Live("2:45")
    )
}

@Preview(showBackground = true)
@Composable
fun AppHeaderPausedPreview() {
    AppHeader(
        showBackButton = true,
        timerBadge = TimerBadge.Paused("2:15")
    )
}