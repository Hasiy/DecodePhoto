package top.hasiy.rawviewer.util

import kotlin.math.abs
import kotlin.math.roundToInt

/** RGB 顏色；每個色彩通道的有效範圍為 0..255。 */
data class RgbColor(
    val red: Int,
    val green: Int,
    val blue: Int,
) {
    init {
        require(red in RGB_CHANNEL_RANGE) { "red must be in 0..255" }
        require(green in RGB_CHANNEL_RANGE) { "green must be in 0..255" }
        require(blue in RGB_CHANNEL_RANGE) { "blue must be in 0..255" }
    }

    private companion object {
        val RGB_CHANNEL_RANGE = 0..255
    }
}

/**
 * HSB（亦稱 HSV）顏色。
 *
 * @param hue 色相角度，可為任意有限值；轉換時會環繞至 0（含）到 360（不含）。
 * @param saturation 飽和度百分比，範圍為 0..100。
 * @param brightness 明度百分比，範圍為 0..100。
 */
data class HsbColor(
    val hue: Double,
    val saturation: Double,
    val brightness: Double,
) {
    init {
        require(hue.isFinite()) { "hue must be finite" }
        require(saturation in PERCENT_RANGE) { "saturation must be in 0..100" }
        require(brightness in PERCENT_RANGE) { "brightness must be in 0..100" }
    }

    private companion object {
        val PERCENT_RANGE = 0.0..100.0
    }
}

/** RGB 與 HSB（HSV）顏色模型之間的轉換。 */
object ColorConverter {

    private const val RGB_CHANNEL_MAX = 255.0
    private const val FULL_HUE_DEGREES = 360.0
    private const val HUE_SECTOR_DEGREES = 60.0
    private const val PERCENT_MAX = 100.0

    /**
     * 將 RGB 顏色轉為 HSB。
     *
     * @param rgb RGB 各通道範圍為 0..255。
     * @return 色相為 0（含）到 360（不含），飽和度與明度為 0..100 的 HSB 顏色。
     */
    fun rgbToHsb(rgb: RgbColor): HsbColor {
        val red = rgb.red / RGB_CHANNEL_MAX
        val green = rgb.green / RGB_CHANNEL_MAX
        val blue = rgb.blue / RGB_CHANNEL_MAX

        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val delta = max - min

        val hue = when {
            delta == 0.0 -> 0.0
            max == red -> HUE_SECTOR_DEGREES * (((green - blue) / delta) % 6.0)
            max == green -> HUE_SECTOR_DEGREES * (((blue - red) / delta) + 2.0)
            else -> HUE_SECTOR_DEGREES * (((red - green) / delta) + 4.0)
        }.normalizeHue()

        val saturation = if (max == 0.0) 0.0 else delta / max
        return HsbColor(
            hue = hue,
            saturation = saturation * PERCENT_MAX,
            brightness = max * PERCENT_MAX,
        )
    }

    /**
     * 將 HSB 顏色轉為 RGB。
     *
     * @param hsb HSB 色相可環繞，飽和度與明度範圍為 0..100。
     * @return 各通道皆在 0..255 的 RGB 顏色。
     */
    fun hsbToRgb(hsb: HsbColor): RgbColor {
        val hue = hsb.hue.normalizeHue()
        val saturation = hsb.saturation / PERCENT_MAX
        val brightness = hsb.brightness / PERCENT_MAX

        val chroma = brightness * saturation
        val hueSector = hue / HUE_SECTOR_DEGREES
        val intermediate = chroma * (1.0 - abs((hueSector % 2.0) - 1.0))
        val match = brightness - chroma

        val (redPrime, greenPrime, bluePrime) = when (hueSector.toInt()) {
            0 -> Triple(chroma, intermediate, 0.0)
            1 -> Triple(intermediate, chroma, 0.0)
            2 -> Triple(0.0, chroma, intermediate)
            3 -> Triple(0.0, intermediate, chroma)
            4 -> Triple(intermediate, 0.0, chroma)
            else -> Triple(chroma, 0.0, intermediate)
        }

        return RgbColor(
            red = ((redPrime + match) * RGB_CHANNEL_MAX).roundToRgbChannel(),
            green = ((greenPrime + match) * RGB_CHANNEL_MAX).roundToRgbChannel(),
            blue = ((bluePrime + match) * RGB_CHANNEL_MAX).roundToRgbChannel(),
        )
    }

    private fun Double.normalizeHue(): Double =
        ((this % FULL_HUE_DEGREES) + FULL_HUE_DEGREES) % FULL_HUE_DEGREES

    private fun Double.roundToRgbChannel(): Int = roundToInt().coerceIn(0, RGB_CHANNEL_MAX.toInt())
}
