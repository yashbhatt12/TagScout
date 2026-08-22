package com.snainfotech.tagscout.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.theme.Amber
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.HeaderBg
import com.snainfotech.tagscout.ui.theme.HeaderIcon
import com.snainfotech.tagscout.ui.theme.HeaderText

// Different timer states the header can show
sealed class TimerBadge {
    object None : TimerBadge()                               // No badge
    data class Live(val timeText: String) : TimerBadge()    // ● Live 2:45
    data class Paused(val timeText: String) : TimerBadge()  // ⏸ Paused 2:15
}

@Composable
fun AppHeader(
    title: String = "TagScout",
    showBackButton: Boolean = false,
    onBackClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    showMenu: Boolean = true,
    timerBadge: TimerBadge = TimerBadge.None,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(HeaderBg)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: back button (optional) + logo + title
        if (showBackButton) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = HeaderIcon
                )
            }
        } else {
            Spacer(modifier = Modifier.width(10.dp))
        }

        TagScoutLogo(size = 36.dp)

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = title,
            color = HeaderText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.weight(1f))

        // Optional timer badge
        when (timerBadge) {
            is TimerBadge.Live -> {
                TimerBadgeView(text = "Live · ${timerBadge.timeText}", isLive = true)
                Spacer(modifier = Modifier.width(6.dp))
            }
            is TimerBadge.Paused -> {
                TimerBadgeView(text = "Paused · ${timerBadge.timeText}", isLive = false)
                Spacer(modifier = Modifier.width(6.dp))
            }
            TimerBadge.None -> { /* nothing */ }
        }

        // Right: menu (three dots) — only shown when the screen provides a menu
        if (showMenu) {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Menu",
                    tint = HeaderIcon
                )
            }
        } else {
            // Keep the right-edge padding balanced with the left when no menu
            Spacer(modifier = Modifier.width(12.dp))
        }
    }
}

// The timer badge (live = amber, paused = muted grey)
@Composable
private fun TimerBadgeView(text: String, isLive: Boolean) {
    val bg = if (isLive) Amber.copy(alpha = 0.15f) else BorderGray
    val fg = if (isLive) Amber else HeaderIcon
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = fg,
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
    AppHeader(showBackButton = true, title = "Quick Scan")
}

@Preview(showBackground = true)
@Composable
fun AppHeaderLivePreview() {
    AppHeader(showBackButton = true, title = "Quick Scan", timerBadge = TimerBadge.Live("2:45"))
}

@Preview(showBackground = true)
@Composable
fun AppHeaderPausedPreview() {
    AppHeader(showBackButton = true, title = "Quick Scan", timerBadge = TimerBadge.Paused("2:15"))
}