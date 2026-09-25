package com.arrowescape.game

import androidx.compose.ui.graphics.Color

/**
 * Central color palette. All gameplay colors live here (explicit rule: BoardCanvas must not
 * hardcode colors). Three themes ship; Paper and Navy unlock at level milestones.
 */
object GameTheme {

    enum class ThemeName(val label: String, val unlockLevel: Int) {
        NIGHT("Night", 1),      // default
        PAPER("Paper", 25),     // unlocked at level 25
        NAVY("Navy", 100),      // unlocked at level 100
    }

    data class Palette(
        val background: Color,
        val dotColor: Color,
        val arrowRest: Color,
        val arrowHint: Color,
        val arrowSliding: Color,
        val arrowBlocked: Color,
        val heartFull: Color,
        val heartEmpty: Color,
        val heartPop: Color,
        val progressFill: Color,
        val progressTrack: Color,
        val titleText: Color,
        val chromeDark: Color,       // back-button circle etc.
        val chromeIcon: Color,
        val hintButton: Color,
        val hintButtonText: Color,
        val overlayPlum: Color,
        val nextLevelYellow: Color,
        val sparkleWhite: Color,
        val textPrimary: Color,
        val textMuted: Color,
        val surface: Color,
    )

    val Night = Palette(
        background = Color(0xFF202030),
        dotColor = Color(0xFF2C2D40),
        arrowRest = Color(0xFFBCBEDD),
        arrowHint = Color(0xFF90BECF),
        arrowSliding = Color(0xFF5DC7B8),
        arrowBlocked = Color(0xFFB73056),
        heartFull = Color(0xFFB62820),
        heartEmpty = Color(0xFF15151F),
        heartPop = Color(0xFFF0453F),
        progressFill = Color(0xFF4D7AA7),
        progressTrack = Color(0xFF14141E),
        titleText = Color(0xFF965876),
        chromeDark = Color(0xFF181826),
        chromeIcon = Color(0xFFFFFFFF),
        hintButton = Color(0xFFFBBC2E),
        hintButtonText = Color(0xFF202030),
        overlayPlum = Color(0xD94A3A55), // #4A3A55 @ 85% alpha
        nextLevelYellow = Color(0xFFFBBC2E),
        sparkleWhite = Color(0xFFFFFFFF),
        textPrimary = Color(0xFFEDEDF5),
        textMuted = Color(0xFF8A8AA0),
        surface = Color(0xFF2A2A3C),
    )

    val Paper = Palette(
        background = Color(0xFFF4EBDC),
        dotColor = Color(0x336B4F3A),
        arrowRest = Color(0xFF6B4F3A),
        arrowHint = Color(0xFF3E7C8C),
        arrowSliding = Color(0xFF2E9E8F),
        arrowBlocked = Color(0xFFB73056),
        heartFull = Color(0xFFB62820),
        heartEmpty = Color(0xFFD8CBB6),
        heartPop = Color(0xFFF0453F),
        progressFill = Color(0xFF4D7AA7),
        progressTrack = Color(0x336B4F3A),
        titleText = Color(0xFF8C4A62),
        chromeDark = Color(0xFFE4D6BE),
        chromeIcon = Color(0xFF6B4F3A),
        hintButton = Color(0xFFE8A93A),
        hintButtonText = Color(0xFF40301A),
        overlayPlum = Color(0xD94A3A55),
        nextLevelYellow = Color(0xFFFBBC2E),
        sparkleWhite = Color(0xFFFFFFFF),
        textPrimary = Color(0xFF40301A),
        textMuted = Color(0xFF8A745A),
        surface = Color(0xFFEADFCC),
    )

    val Navy = Palette(
        background = Color(0xFFFFFFFF),
        dotColor = Color(0x260F1B4C),
        arrowRest = Color(0xFF0F1B4C),
        arrowHint = Color(0xFF2F6BFF),
        arrowSliding = Color(0xFF0FA98F),
        arrowBlocked = Color(0xFFB73056),
        heartFull = Color(0xFFB62820),
        heartEmpty = Color(0xFFCBD3E8),
        heartPop = Color(0xFFF0453F),
        progressFill = Color(0xFF2F6BFF),
        progressTrack = Color(0x260F1B4C),
        titleText = Color(0xFF0F1B4C),
        chromeDark = Color(0xFFE8EDF8),
        chromeIcon = Color(0xFF0F1B4C),
        hintButton = Color(0xFFFBBC2E),
        hintButtonText = Color(0xFF0F1B4C),
        overlayPlum = Color(0xD94A3A55),
        nextLevelYellow = Color(0xFFFBBC2E),
        sparkleWhite = Color(0xFFFFFFFF),
        textPrimary = Color(0xFF0F1B4C),
        textMuted = Color(0xFF5A6685),
        surface = Color(0xFFF2F5FC),
    )

    fun paletteFor(name: ThemeName): Palette = when (name) {
        ThemeName.NIGHT -> Night
        ThemeName.PAPER -> Paper
        ThemeName.NAVY -> Navy
    }

    /** Confetti streamer/circle palette used by the win sequence (theme-independent). */
    val confettiColors = listOf(
        Color(0xFFFFD60A), Color(0xFFFF9F0A), Color(0xFF00C8D7),
        Color(0xFF2F6BFF), Color(0xFFE5484D), Color(0xFF34C759),
    )
}
