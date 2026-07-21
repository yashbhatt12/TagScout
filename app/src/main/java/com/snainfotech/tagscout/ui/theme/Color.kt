package com.snainfotech.tagscout.ui.theme

import androidx.compose.ui.graphics.Color

// Default Compose colors (keep these — used by theme)
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// ============================================================
// TagScout Brand Colors — Professional Redesign (Navy + Amber)
// ============================================================

// --- Primary palette ---
val Primary = Color(0xFF1E4B8F)        // Navy — main buttons, primary actions, icons
val Secondary = Color(0xFF163A6E)      // Darker navy
val Amber = Color(0xFFD98E2B)          // NEW accent — scanning/pause, stat values, highlights

// --- Header (new light-grey header, replaces the purple gradient) ---
val HeaderBg = Color(0xFFE8EAEE)       // NEW — header bar background
val HeaderText = Color(0xFF232A3B)     // NEW — header title text
val HeaderIcon = Color(0xFF5B6472)     // NEW — header icons (back arrow, menu dot)

// --- Status colors ---
val SuccessGreen = Color(0xFF2E9B62)   // Connected, found, battery, progress
val ErrorRed = Color(0xFFC24444)       // Disconnected, missing, danger/destructive
val WarningOrange = Color(0xFFD98E2B)  // Low battery / pause — now amber-aligned
val InfoBlue = Color(0xFF1E4B8F)       // Charging — folded into navy for consistency

// --- Background colors for status banners ---
val SuccessBg = Color(0xFFEAF6EF)
val SuccessText = Color(0xFF1B6B41)
val ErrorBg = Color(0xFFFDF3F3)
val ErrorText = Color(0xFF8A2F2F)
val WarningBg = Color(0xFFFBF1E3)
val WarningText = Color(0xFF8A5A18)
val InfoBg = Color(0xFFE8EEF7)
val InfoText = Color(0xFF163A6E)

// --- Neutral colors ---
val Disabled = Color(0xFFEDEFF2)
val DisabledText = Color(0xFFAAB2BF)
val BorderGray = Color(0xFFE4E7EC)     // card/table borders
val LightGray = Color(0xFFF4F5F7)      // app background
val MediumGray = Color(0xFF6B7688)     // muted / secondary text
val DarkText = Color(0xFF1B2436)       // primary "ink" text
val CardBg = Color(0xFFFFFFFF)         // NEW — explicit card surface

// --- Specific UI elements ---
val TableRowFound = Color(0xFFF3FAF5)     // soft green for found/picked rows
val TableRowMissing = Color(0xFFFDF3F3)   // soft red for missing rows
val TableRowPending = Color(0xFFF9FAFB)   // soft grey for pending rows