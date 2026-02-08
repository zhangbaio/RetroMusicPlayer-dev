package code.name.monkey.retromusic.util.color

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

object PlayerAdaptiveColorUtil {

    data class GradientColors(
        @ColorInt val topColor: Int,
        @ColorInt val bottomColor: Int
    )

    @ColorInt
    fun extractDominantColor(
        palette: Palette?,
        @ColorInt fallback: Int
    ): Int {
        val swatch = palette?.swatches
            ?.filter { it.population > 0 }
            ?.maxByOrNull { it.population }
        return opaque(swatch?.rgb ?: fallback)
    }

    fun buildGradientColors(
        @ColorInt dominantColor: Int,
        @ColorInt fallbackSurfaceColor: Int
    ): GradientColors {
        val safeDominant = opaque(dominantColor)
        val safeSurface = opaque(fallbackSurfaceColor)
        val dominantLightness = lightness(safeDominant)
        val tonedDominant = softenDominantColor(safeDominant, dominantLightness)
        val topMixRatio = topSurfaceMixRatio(dominantLightness)
        val safeTopColor = opaque(ColorUtils.blendARGB(tonedDominant, safeSurface, topMixRatio))
        val bottomDarkenRatio = bottomDarkenRatio(dominantLightness, safeTopColor)
        val safeBottomColor =
            opaque(ColorUtils.blendARGB(safeTopColor, Color.BLACK, bottomDarkenRatio))
        return GradientColors(
            topColor = safeTopColor,
            bottomColor = safeBottomColor
        )
    }

    @ColorInt
    private fun softenDominantColor(@ColorInt color: Int, sourceLightness: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        val sourceSaturation = hsl[1]

        // Keep dominant hue, but remap S/L to a softer, tinted range.
        // This avoids the "all gray" look on light covers while staying non-neon.
        hsl[1] = when {
            sourceLightness >= 0.78f -> sourceSaturation.coerceIn(0.14f, 0.30f)
            sourceLightness >= 0.62f -> sourceSaturation.coerceIn(0.14f, 0.34f)
            sourceLightness <= 0.30f -> (sourceSaturation * 0.80f).coerceIn(0.14f, 0.45f)
            else -> (sourceSaturation * 0.85f).coerceIn(0.14f, 0.42f)
        }
        hsl[2] = when {
            sourceLightness >= 0.82f -> 0.72f
            sourceLightness >= 0.72f -> 0.68f
            sourceLightness >= 0.62f -> 0.64f
            sourceLightness >= 0.50f -> 0.60f
            sourceLightness <= 0.24f -> 0.42f
            else -> sourceLightness.coerceIn(0.44f, 0.60f)
        }
        return ColorUtils.HSLToColor(hsl)
    }

    private fun topSurfaceMixRatio(sourceLightness: Float): Float {
        return when {
            sourceLightness >= 0.78f -> 0.05f
            sourceLightness >= 0.62f -> 0.08f
            sourceLightness >= 0.50f -> 0.10f
            else -> 0.14f
        }
    }

    private fun bottomDarkenRatio(sourceLightness: Float, @ColorInt color: Int): Float {
        if (sourceLightness >= 0.62f) {
            return 0.10f
        }
        return if (ColorUtils.calculateLuminance(color) > 0.35) {
            0.16f
        } else {
            0.12f
        }
    }

    private fun lightness(@ColorInt color: Int): Float {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        return hsl[2]
    }

    @ColorInt
    private fun opaque(@ColorInt color: Int): Int {
        return ColorUtils.setAlphaComponent(color, 255)
    }
}
