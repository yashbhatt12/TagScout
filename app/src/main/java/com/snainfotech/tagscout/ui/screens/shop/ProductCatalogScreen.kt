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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.data.shop.Product
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.theme.Amber
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.SuccessGreen
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ProductCatalogScreen(
    state: ShopState,
    onBackClick: () -> Unit,
    onCartClick: () -> Unit,
    onAddToCart: (Product, Int) -> Unit,
    onDismissMessage: () -> Unit,
    isInCart: (String) -> Boolean,
    getCartQuantity: (String) -> Int,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightGray)
        ) {
            AppHeader(
                title = "Shop",
                showBackButton = true,
                onBackClick = onBackClick,
                showMenu = false
            )

            // Cart button bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${state.products.size} products",
                    fontSize = 12.sp,
                    color = MediumGray
                )

                TextButton(onClick = onCartClick) {
                    BadgedBox(
                        badge = {
                            if (state.cartItemCount > 0) {
                                Badge(containerColor = Amber) {
                                    Text(state.cartItemCount.toString())
                                }
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.ShoppingCart,
                            contentDescription = "Cart",
                            tint = Primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (state.cartItemCount > 0) "View Cart (${state.cartItemCount})" else "Cart",
                        color = Primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Content
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Primary)
                }
            } else if (state.error != null && state.products.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = state.error, color = MediumGray, fontSize = 14.sp, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = { /* trigger reload via ViewModel */ }) {
                            Text("Retry", color = Primary)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    state.products.forEach { product ->
                        ProductCard(
                            product = product,
                            isInCart = isInCart(product.id),
                            cartQuantity = getCartQuantity(product.id),
                            onAddToCart = { qty -> onAddToCart(product, qty) }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // Snackbar for messages
        if (state.message != null) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = onDismissMessage) {
                        Text("OK", color = Color.White)
                    }
                },
                containerColor = DarkText
            ) {
                Text(text = state.message, color = Color.White)
            }
        }
    }
}

@Composable
private fun ProductCard(
    product: Product,
    isInCart: Boolean,
    cartQuantity: Int,
    onAddToCart: (Int) -> Unit
) {
    val format = remember { NumberFormat.getCurrencyInstance(Locale("en", "IN")) }
    var quantity by remember(product.id, cartQuantity) {
        mutableIntStateOf(if (cartQuantity > 0) cartQuantity else product.minimumOrderQuantity)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: category badge + title
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(category = product.category)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = product.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkText,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Features
            if (product.features.isNotEmpty()) {
                product.features.forEach { feature ->
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text(text = "•", color = Primary, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = feature, fontSize = 12.sp, color = DarkText)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Info row: warranty + delivery
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (product.warranty.isNotBlank()) {
                    InfoChip(label = "Warranty", value = product.warranty)
                }
                if (product.deliveryTimeline.isNotBlank()) {
                    InfoChip(label = "Delivery", value = product.deliveryTimeline)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Price + MOQ
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = format.format(product.sellingPrice),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )
                    Text(
                        text = "per ${product.unit} • Min. order: ${product.minimumOrderQuantity}",
                        fontSize = 10.sp,
                        color = MediumGray
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quantity selector + Add to Cart
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Quantity picker
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .border(1.dp, BorderGray, RoundedCornerShape(8.dp))
                        .padding(horizontal = 4.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (quantity > product.minimumOrderQuantity) quantity--
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text(text = "−", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Primary)
                    }

                    Text(
                        text = quantity.toString(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkText,
                        modifier = Modifier.width(36.dp),
                        textAlign = TextAlign.Center
                    )

                    IconButton(
                        onClick = { quantity++ },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Add, "Increase", tint = Primary, modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Add / Update button
                if (isInCart) {
                    OutlinedButton(
                        onClick = { onAddToCart(quantity) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SuccessGreen),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SuccessGreen),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Text("Update Cart", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = { onAddToCart(quantity) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Text("Add to Cart", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBadge(category: String) {
    val (color, label) = when (category.lowercase()) {
        "scanner" -> Primary to "Scanner"
        "printer" -> Amber to "Printer"
        "label" -> SuccessGreen to "Label"
        "accessory" -> MediumGray to "Accessory"
        else -> MediumGray to category.replaceFirstChar { it.uppercase() }
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Column {
        Text(text = label, fontSize = 9.sp, color = MediumGray)
        Text(text = value, fontSize = 11.sp, color = DarkText, fontWeight = FontWeight.Medium)
    }
}