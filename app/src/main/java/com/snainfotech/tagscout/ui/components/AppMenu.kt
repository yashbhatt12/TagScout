package com.snainfotech.tagscout.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.InfoBlue

@Composable
fun AppMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAboutClick: () -> Unit,
    onExitClick: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .background(Color.White)
            .width(200.dp)
    ) {
        MenuItem(
            icon = "ℹ️",
            label = "About TagScout",
            onClick = {
                onAboutClick()
                onDismiss()
            }
        )

        MenuDivider()

        MenuItem(
            icon = "❌",
            label = "Exit",
            textColor = ErrorRed,
            onClick = {
                onExitClick()
                onDismiss()
            }
        )
    }
}

@Composable
private fun MenuItem(
    icon: String,
    label: String,
    highlighted: Boolean = false,
    textColor: Color = DarkText,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (highlighted) Color(0xFFE7F3FF) else Color.White)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, fontSize = 16.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (highlighted) InfoBlue else textColor,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun MenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF0F0F0))
            .padding(top = 1.dp)
    )
}