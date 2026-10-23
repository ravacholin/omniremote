package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// =========================================================================
// MONOLITH — a strict five-step monochrome scale.
// Black is the ground, white is the only emphasis, and every structure
// is drawn with a single hairline weight.
// =========================================================================

val Void = Color(0xFF000000)      // Ground
val Ink = Color(0xFF0B0B0B)       // Raised plane (sheets, fields)
val Hairline = Color(0xFF232323)  // The only structural line
val Graphite = Color(0xFF5C5C5C)  // Disabled / tertiary
val Ash = Color(0xFF9A9A9A)       // Secondary text
val Paper = Color(0xFFFFFFFF)     // Emphasis, inversion

// Backgrounds & Slabs
val RemoteDarkBg = Void
val RemoteSurface = Void
val RemoteSurfaceVariant = Ink
val RemoteCardBg = Ink
val RemoteCardBgElevated = Ink

// Borders
val RemoteBorder = Hairline
val RemoteBorderSubtle = Hairline
val RemoteBorderStrong = Hairline
val RemoteBorderWhite = Paper
val RemoteHighlight = Paper

// Button Surfaces
val RemoteBtnDefault = Void
val RemoteBtnActive = Ink
val RemoteBtnDpad = Void
val RemoteBtnDpadCenter = Paper
val RemoteBtnInverted = Paper

// Monochrome Tones
val BrutalistBlack = Void
val BrutalistCharcoal = Ink
val BrutalistDarkGray = Hairline
val BrutalistMidGray = Graphite
val BrutalistSilver = Ash
val BrutalistOffWhite = Paper
val BrutalistWhite = Paper

// Device identifiers stay monochrome: the brand is the name, not a color.
val SamsungCyan = Paper
val SamsungCyanDark = Ash
val AndroidMint = Paper
val AndroidMintDark = Ash

// Functional indicators
val PowerRed = Paper
val PowerRedAccent = Paper
val PowerRedGlow = Color(0x33FFFFFF)
val StatusGreen = Paper
val StatusGreenAccent = Paper
val StatusAmber = Ash
val StatusMuted = Graphite

// Typography Colors
val TextPrimary = Paper
val TextSecondary = Ash
val TextMuted = Color(0xFF7C7C7C)  // Tertiary text, kept above 4.5:1 on Void

// Legacy Fallbacks
val Purple80 = Paper
val PurpleGrey80 = Ash
val Pink80 = Paper
val Purple40 = Hairline
val PurpleGrey40 = Ink
val Pink40 = Ink
