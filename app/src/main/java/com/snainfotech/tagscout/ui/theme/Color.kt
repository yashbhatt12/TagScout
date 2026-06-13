package com.snainfotech.tagscout.ui.theme

import androidx.compose.ui.graphics.Color

// Default Compose colors (keep these — used by theme)
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// === TagScout Brand Colors ===

// Primary palette (used for header gradient, main buttons)
val Primary = Color(0xFF667eea)
val Secondary = Color(0xFF764ba2)

// Status colors (device status indicators, banners)
val SuccessGreen = Color(0xFF00d084)      // Connected, ✓ found
val ErrorRed = Color(0xFFff6b6b)          // Disconnected, ✗ missing, danger
val WarningOrange = Color(0xFFffa500)     // Low battery, pause
val InfoBlue = Color(0xFF0d6efd)          // Charging

// Background colors for status banners
val SuccessBg = Color(0xFFd4edda)
val SuccessText = Color(0xFF155724)
val ErrorBg = Color(0xFFf8d7da)
val ErrorText = Color(0xFF721c24)
val WarningBg = Color(0xFFfff3cd)
val WarningText = Color(0xFF664d03)
val InfoBg = Color(0xFFcfe2ff)
val InfoText = Color(0xFF084298)

// Neutral colors
val Disabled = Color(0xFFe9ecef)
val DisabledText = Color(0xFFadb5bd)
val BorderGray = Color(0xFFe0e0e0)
val LightGray = Color(0xFFf8f9fa)
val MediumGray = Color(0xFF666666)
val DarkText = Color(0xFF2c3e50)

// Specific UI elements
val TableRowFound = Color(0xFFe8f5e9)     // Light green for found rows
val TableRowMissing = Color(0xFFffebee)   // Light red for missing rows
val TableRowPending = Color(0xFFf5f5f5)   // Light gray for pending rows