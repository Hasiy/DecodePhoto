/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.libheif

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/** BT.2100 transfer functions and a documented luminance-preserving SDR shoulder. */
object HdrToneMapMath {
    const val SDR_WHITE_NITS = 203.0
    const val HLG_PEAK_NITS = 1000.0
    private const val PQ_PEAK_NITS = 10000.0
    private const val HLG_A = 0.17883277
    private const val HLG_B = 0.28466892
    private const val HLG_C = 0.55991073

    fun pqNits(encoded: Double): Double {
        val p = encoded.coerceIn(0.0, 1.0).pow(1.0 / (2523.0 / 32.0))
        return ((max(p - 3424.0 / 4096.0, 0.0)) /
            max(2413.0 / 128.0 - 2392.0 / 128.0 * p, 1e-12))
            .pow(1.0 / (2610.0 / 16384.0)) * PQ_PEAK_NITS
    }
    fun hlgScene(encoded: Double): Double {
        val value = encoded.coerceIn(0.0, 1.0)
        return if (value <= 0.5) value * value / 3.0
        else (exp((value - HLG_C) / HLG_A) + HLG_B) / 12.0
    }
    fun srgbEncode(linear: Double): Double {
        val x = linear.coerceIn(0.0, 1.0)
        return if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - 0.055
    }
    /** Returns display-linear BT.2020 RGB, relative to a 203 nit SDR white. */
    fun linearRgb(r: Double, g: Double, b: Double, range: HeifDynamicRange): DoubleArray {
        if (range == HeifDynamicRange.PQ) return doubleArrayOf(pqNits(r), pqNits(g), pqNits(b))
            .map { it / SDR_WHITE_NITS }.toDoubleArray()
        val rgb = doubleArrayOf(hlgScene(r), hlgScene(g), hlgScene(b))
        val sceneY = 0.2627 * rgb[0] + 0.6780 * rgb[1] + 0.0593 * rgb[2]
        val ootf = sceneY.coerceAtLeast(0.0).pow(0.2) * HLG_PEAK_NITS / SDR_WHITE_NITS
        return rgb.map { it * ootf }.toDoubleArray()
    }
    fun luminance(rgb: DoubleArray): Double =
        0.2627 * rgb[0] + 0.6780 * rgb[1] + 0.0593 * rgb[2]

    /** Preserves dark/mid tones, starts a smooth exponential shoulder at half SDR white. */
    fun shoulder(y: Double): Double = if (y <= 0.5) y.coerceAtLeast(0.0)
        else 0.5 + 0.5 * (1.0 - exp(-(y - 0.5) / 0.5))

    fun toSrgb(rgb: DoubleArray): DoubleArray {
        val y = luminance(rgb)
        val scale = if (y > 0) shoulder(y) / y else 0.0
        val r = rgb[0] * scale; val g = rgb[1] * scale; val b = rgb[2] * scale
        return doubleArrayOf(
            srgbEncode(1.660491 * r - 0.587641 * g - 0.072850 * b),
            srgbEncode(-0.124550 * r + 1.132900 * g - 0.008350 * b),
            srgbEncode(-0.018151 * r - 0.100579 * g + 1.118730 * b),
        )
    }
}
