package com.snainfotech.tagscout.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.MediumGray

/**
 * A feature tile that respects the company's plan (settings/config features).
 *
 *   available = true  → the normal FeatureButton; [enabled] still applies
 *                       (e.g. greyed until a sled is connected)
 *   available = false → a faded tile that stays tappable and calls
 *                       [onUpgrade] with its title instead of opening the feature
 *
 * Pair with [UpgradeDialog]: hold `var upgradeFor by remember { mutableStateOf<String?>(null) }`
 * in the screen, pass `onUpgrade = { upgradeFor = it }`, and show the dialog when non-null.
 */
@Composable
fun GatedFeatureButton(
    available: Boolean,
    @DrawableRes icon: Int,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onUpgrade: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (available) {
        FeatureButton(
            icon = icon,
            title = title,
            description = description,
            enabled = enabled,
            onClick = onClick,
            modifier = modifier
        )
    } else {
        FeatureButton(
            icon = icon,
            title = title,
            description = "🔒 Not in your plan · Tap to upgrade",
            enabled = true,
            onClick = { onUpgrade(title) },
            modifier = modifier.alpha(0.6f)
        )
    }
}

/** Shown when a locked feature tile is tapped. */
@Composable
fun UpgradeDialog(featureTitle: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "🔒 $featureTitle",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                Text(
                    text = "$featureTitle isn't part of your current plan.",
                    fontSize = 13.sp,
                    color = DarkText
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "To enable it, contact SNA Infotech at contactus@sna-infotech.co.in or +91-22-42182620.",
                    fontSize = 12.sp,
                    color = MediumGray
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}