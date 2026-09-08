package com.snainfotech.tagscout.ui.screens.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.data.shop.CartItem
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.theme.Amber
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import java.text.NumberFormat
import java.util.Locale

@Composable
fun CartScreen(
    state: ShopState,
    onBackClick: () -> Unit,
    onRemoveItem: (String) -> Unit,
    onUpdateQuantity: (String, Int) -> Unit,
    onCheckout: () -> Unit,
    onDismissMessage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val format = remember { NumberFormat.getCurrencyInstance(Locale("en", "IN")) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showSuccessDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        if (state.message != null && state.message.contains("submitted", ignoreCase = true)) {
            showSuccessDialog = true
        }
    }

    // Confirmation dialog
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = {
                Text(text = "Confirm Purchase Requisition", fontWeight = FontWeight.SemiBold, color = DarkText)
            },
            text = {
                Column {
                    Text(
                        text = "You are about to submit a purchase requisition for ${state.cartItemCount} item${if (state.cartItemCount > 1) "s" else ""}.",
                        fontSize = 14.sp, color = DarkText
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Approximate order value: ${state.cartTotalFormatted}",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The SNA Infotech team will review your request and contact you to confirm pricing and delivery.",
                        fontSize = 12.sp, color = MediumGray
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showConfirmDialog = false; onCheckout() },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Submit Order") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel", color = MediumGray)
                }
            }
        )
    }

    // Success dialog
    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showSuccessDialog = false; onDismissMessage() },
            title = {
                Text(text = "Order Submitted!", fontWeight = FontWeight.SemiBold, color = SuccessGreen)
            },
            text = {
                Column {
                    Text(
                        text = "Your purchase requisition has been submitted successfully.",
                        fontSize = 14.sp, color = DarkText
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The SNA Infotech team will review your order and get in touch with you shortly.",
                        fontSize = 12.sp, color = MediumGray
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showSuccessDialog = false; onDismissMessage(); onBackClick() },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Done") }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightGray)
    ) {
        AppHeader(
            title = "Cart",
            showBackButton = true,
            onBackClick = onBackClick,
            showMenu = false
        )

        if (state.cart.isEmpty() && !showSuccessDialog) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "🛒", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Your cart is empty", fontSize = 16.sp, color = MediumGray, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Browse products and add items to get started", fontSize = 12.sp, color = MediumGray)
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = onBackClick) {
                        Text("Browse Products", color = Primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else if (state.cart.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${state.cartItemCount} item${if (state.cartItemCount > 1) "s" else ""} in cart",
                    fontSize = 12.sp, color = MediumGray, modifier = Modifier.padding(bottom = 4.dp)
                )
                state.cart.forEach { item ->
                    CartItemCard(
                        item = item,
                        format = format,
                        onRemove = { onRemoveItem(item.product.id) },
                        onQuantityChange = { qty -> onUpdateQuantity(item.product.id, qty) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Bottom: Order summary + Checkout
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Order Summary", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DarkText)
                    Spacer(modifier = Modifier.height(8.dp))

                    state.cart.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${item.product.title} × ${item.quantity}",
                                fontSize = 11.sp, color = MediumGray, modifier = Modifier.weight(1f)
                            )
                            Text(text = format.format(item.lineTotal), fontSize = 11.sp, color = DarkText, fontWeight = FontWeight.Medium)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = BorderGray)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Approximate Order Value", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DarkText)
                        Text(text = state.cartTotalFormatted, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Primary)
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Final pricing will be confirmed by the SNA Infotech team", fontSize = 9.sp, color = MediumGray)
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { showConfirmDialog = true },
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Amber)
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        } else {
                            Text(text = "Initiate Purchase Requisition", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CartItemCard(
    item: CartItem,
    format: NumberFormat,
    onRemove: () -> Unit,
    onQuantityChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.product.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DarkText)
                    Text(text = "${format.format(item.product.sellingPrice)} per ${item.product.unit}", fontSize = 11.sp, color = MediumGray)
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = ErrorRed, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .border(1.dp, BorderGray, RoundedCornerShape(8.dp))
                        .padding(horizontal = 4.dp)
                ) {
                    IconButton(
                        onClick = { if (item.quantity > item.product.minimumOrderQuantity) onQuantityChange(item.quantity - 1) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text(text = "−", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primary)
                    }
                    Text(
                        text = item.quantity.toString(), fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, color = DarkText,
                        modifier = Modifier.width(32.dp), textAlign = TextAlign.Center
                    )
                    IconButton(onClick = { onQuantityChange(item.quantity + 1) }, modifier = Modifier.size(28.dp)) {
                        Text(text = "+", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primary)
                    }
                }
                Text(text = format.format(item.lineTotal), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Primary)
            }
        }
    }
}