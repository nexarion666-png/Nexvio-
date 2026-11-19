package com.nexvio.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Shared Liquid Glass material used by cards, controls and navigation.
 * The non-glass branch deliberately keeps the existing theme's solid surfaces.
 */
internal fun Modifier.nexvioGlass(
    shape: Shape = RoundedCornerShape(18.dp),
    fallback: Color = AppAppearanceState.panel
): Modifier {
    if (!AppAppearanceState.isLiquidGlass) return this.background(fallback, shape)

    val reflection = AppAppearanceState.posterReflection
    val surface = Brush.linearGradient(
        colors = listOf(
            Color(0x882B3B47),
            Color(0x660B121A),
            reflection.copy(alpha = 0.18f),
            Color(0x881B2832)
        )
    )
    val rim = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.48f),
            reflection.copy(alpha = 0.58f),
            Color.White.copy(alpha = 0.12f),
            reflection.copy(alpha = 0.42f)
        )
    )
    val hazeState = AppAppearanceState.hazeState
    val blurred = if (hazeState != null) {
        this.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = Color(0xFF111923),
                tints = listOf(HazeTint(reflection.copy(alpha = 0.12f))),
                blurRadius = 22.dp,
                noiseFactor = 0.035f
            )
        )
    } else this
    return blurred
        .shadow(13.dp, shape, clip = false)
        .clip(shape)
        .background(surface, shape)
        .border(1.dp, rim, shape)
}

/** A restrained luminous outline for poster artwork and channel logos. */
internal fun Modifier.nexvioGlassRim(shape: Shape = RoundedCornerShape(14.dp)): Modifier {
    if (!AppAppearanceState.isLiquidGlass) return this
    val reflection = AppAppearanceState.posterReflection
    return this
        .shadow(8.dp, shape, clip = false)
        .border(
            1.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.78f),
                    reflection.copy(alpha = 0.9f),
                    Color.White.copy(alpha = 0.22f)
                )
            ),
            shape
        )
}
