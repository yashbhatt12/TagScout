package com.snainfotech.tagscout.ui.screens.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.Timestamp
import com.snainfotech.tagscout.data.manager.Alert
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorBg
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.ErrorText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Manager Alerts screen — live feed of unsold items seen at exit portals.
 *
 * Backed by [ManagerAlertsViewModel], which subscribes to Firestore in realtime
 * via [com.snainfotech.tagscout.data.manager.ManagerRepository.observeAlerts].
 *
 * Behaviour:
 *   - Unread alerts render with a red indicator and a bold title.
 *   - Tapping an alert marks it read in Firestore (the realtime listener
 *     reflects the change back without needing a refresh).
 *   - The top toolbar shows the current unread count and a "Clear all"
 *     button when there's anything to clear.
 *   - A one-shot Snackbar surfaces errors and success messages.
 *
 * Preview-friendly: the whole screen is driven off [ManagerAlertsState] so
 * the previews below can show empty / loading / with-alerts states without
 * any ViewModel wiring.
 */
@Composable
fun ManagerAlertsScreen(
    state: ManagerAlertsState,
    onBackClick: () -> Unit,
    onAlertClick: (alertId: String) -> Unit,
    onClearAllClick: () -> Unit,
    onMessageDismissed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface error / info messages as a Snackbar, then clear state.
    LaunchedEffect(state.errorMessage, state.infoMessage) {
        val msg = state.errorMessage ?: state.infoMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            onMessageDismissed()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(LightGray)) {
        Column(modifier = Modifier.fillMaxSize()) {

            AppHeader(
                title = "Alerts",
                showBackButton = true,
                onBackClick = onBackClick,
                showMenu = false
            )

            // Toolbar — unread count + Clear-all
            AlertsToolbar(
                unreadCount = state.unreadCount,
                totalCount = state.alerts.size,
                isLoading = state.isLoading,
                onClearAllClick = onClearAllClick
            )

            // Content
            when {
                state.isLoading -> LoadingBlock()
                state.isEmpty -> EmptyBlock()
                else -> AlertList(
                    alerts = state.alerts,
                    onAlertClick = onAlertClick
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) { data -> Snackbar(snackbarData = data) }
    }
}

// ── Sub-composables ─────────────────────────────────────────────────

@Composable
private fun AlertsToolbar(
    unreadCount: Int,
    totalCount: Int,
    isLoading: Boolean,
    onClearAllClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (isLoading) {
                Text("Loading…", color = MediumGray, fontSize = 13.sp)
            } else {
                Text(
                    text = when (unreadCount) {
                        0 -> "No unread alerts"
                        1 -> "1 unread alert"
                        else -> "$unreadCount unread alerts"
                    },
                    color = DarkText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                if (totalCount > unreadCount) {
                    Text(
                        text = "$totalCount total",
                        color = MediumGray,
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (unreadCount > 0) {
            OutlinedButton(onClick = onClearAllClick) {
                Text("Clear all")
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = Primary)
    }
}

@Composable
private fun EmptyBlock() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "✓",
                color = SuccessGreen,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No alerts",
                color = DarkText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Nothing has triggered a portal alarm.",
                color = MediumGray,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun AlertList(
    alerts: List<Alert>,
    onAlertClick: (alertId: String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(alerts, key = { it.id }) { alert ->
            AlertCard(alert = alert, onClick = { onAlertClick(alert.id) })
        }
        item { Spacer(Modifier.height(80.dp)) }  // room for the Snackbar
    }
}

@Composable
private fun AlertCard(alert: Alert, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (alert.read) LightGray else ErrorBg)
            .clickable(enabled = !alert.read, onClick = onClick)
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Red dot for unread; grey dot for read.
                Box(
                    modifier = Modifier
                        .width(10.dp)
                        .height(10.dp)
                        .clip(CircleShape)
                        .background(if (alert.read) BorderGray else ErrorRed)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = alert.itemCode ?: alert.epc,
                    color = if (alert.read) MediumGray else ErrorText,
                    fontWeight = if (alert.read) FontWeight.Normal else FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                if (!alert.read) {
                    Text(
                        text = "UNREAD",
                        color = ErrorRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = buildCardSubline(alert),
                color = DarkText,
                fontSize = 13.sp
            )

            if (alert.epc.isNotBlank() && alert.itemCode != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "EPC ${alert.epc}",
                    color = MediumGray,
                    fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTimestamp(alert.createdAt),
                    color = MediumGray,
                    fontSize = 11.sp
                )
                alert.readerId?.let {
                    Text("  •  ", color = MediumGray, fontSize = 11.sp)
                    Text(it, color = MediumGray, fontSize = 11.sp)
                }
                if (!alert.read) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Tap to acknowledge",
                        color = Primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ── Formatters ──────────────────────────────────────────────────────

/** "₹50,000 • rings"  or  "rings"  or  "₹50,000"  — whichever fields are present. */
private fun buildCardSubline(alert: Alert): String {
    val parts = buildList {
        alert.price?.let { add("₹${it.toInt()}") }
        alert.category?.let { add(it) }
    }
    return if (parts.isEmpty()) "Portal alert" else parts.joinToString("  •  ")
}

/** "14:32  •  5 Oct"  —  short time + date, device locale. */
private fun formatTimestamp(ts: Timestamp?): String {
    if (ts == null) return "just now"
    val millis = ts.toDate().time
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    val date = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(millis))
    return "$time  •  $date"
}

// ── Previews ────────────────────────────────────────────────────────

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun ManagerAlertsScreenEmptyPreview() {
    ManagerAlertsScreen(
        state = ManagerAlertsState(alerts = emptyList(), isLoading = false),
        onBackClick = {}, onAlertClick = {}, onClearAllClick = {},
        onMessageDismissed = {}
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun ManagerAlertsScreenLoadingPreview() {
    ManagerAlertsScreen(
        state = ManagerAlertsState(isLoading = true),
        onBackClick = {}, onAlertClick = {}, onClearAllClick = {},
        onMessageDismissed = {}
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun ManagerAlertsScreenWithAlertsPreview() {
    ManagerAlertsScreen(
        state = ManagerAlertsState(
            isLoading = false,
            alerts = listOf(
                Alert(
                    id = "a1",
                    epc = "3000E2801191A504007518B5AC54",
                    itemCode = "RING-001",
                    category = "rings",
                    price = 50000.0,
                    readerId = "fr901-main-exit",
                    read = false,
                    createdAt = Timestamp.now()
                ),
                Alert(
                    id = "a2",
                    epc = "3000E2801191A504007518B58B5C",
                    itemCode = "NECKLACE-14",
                    category = "necklaces",
                    price = 185000.0,
                    readerId = "fr901-main-exit",
                    read = false,
                    createdAt = Timestamp.now()
                ),
                Alert(
                    id = "a3",
                    epc = "3000E2801191A504006D15675153",
                    itemCode = "BRACELET-7",
                    category = "bracelets",
                    price = 32000.0,
                    readerId = "fr901-main-exit",
                    read = true,
                    createdAt = Timestamp.now()
                )
            )
        ),
        onBackClick = {}, onAlertClick = {}, onClearAllClick = {},
        onMessageDismissed = {}
    )
}