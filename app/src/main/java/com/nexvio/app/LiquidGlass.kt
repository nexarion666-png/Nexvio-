package com.nexvio.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
 * Shared Liquid Glass surface for Nexvio.
 *
 * Haze samples the content behind the surface; the gradient and rim add
 * the cool, reflective material treatment. Non-glass themes keep solid fills.
 */
internal fun Modifier.nexvioGlass(
    shape: Shape = RoundedCornerShape(18.dp),
    fallback: Color = AppAppearanceState.panel
): Modifier {
    if (!AppAppearanceState.isLiquidGlass) return this.background(fallback, shape)

    val reflection = AppAppearanceState.posterReflection
    val surface = Brush.linearGradient(
        colors = listOf(
            Color(0x702B3B47),
            Color(0x520B121B),
            reflection.copy(alpha = 0.13f),
            Color(0x70202D38)
        )
    )
    val rim = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.42f),
            reflection.copy(alpha = 0.52f),
            Color.White.copy(alpha = 0.10f),
            reflection.copy(alpha = 0.32f)
        )
    )
    val state = AppAppearanceState.hazeState
    val blurred = if (state != null) {
        this.hazeEffect(
            state = state,
            style = HazeStyle(
                backgroundColor = Color(0xFF080D14),
                tints = listOf(HazeTint(reflection.copy(alpha = 0.11f))),
                blurRadius = 24.dp,
                noiseFactor = 0.035f
            )
        )
    } else this

    return blurred
        .shadow(14.dp, shape, clip = false)
        .clip(shape)
        .background(surface, shape)
        .border(1.dp, rim, shape)
}

/** A restrained luminous edge for artwork and image tiles. */
internal fun Modifier.nexvioGlassRim(
    shape: Shape = RoundedCornerShape(14.dp)
): Modifier {
    if (!AppAppearanceState.isLiquidGlass) return this
    val reflection = AppAppearanceState.posterReflection
    return this
        .shadow(8.dp, shape, clip = false)
        .border(
            1.dp,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.72f),
                    reflection.copy(alpha = 0.78f),
                    Color.White.copy(alpha = 0.18f)
                )
            ),
            shape
        )
}
