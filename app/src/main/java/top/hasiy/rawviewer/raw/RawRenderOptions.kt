/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer.raw

import android.app.ActivityManager
import android.content.Context

enum class RawWhiteBalance { CAMERA, AUTO, DAYLIGHT }
/** Exposure in stops; half-size controls demosaicing independently of output size. */
data class RawRenderOptions(val exposureEv: Float = 0f,
    val whiteBalance: RawWhiteBalance = RawWhiteBalance.CAMERA, val halfSize: Boolean = true) {
    init { require(exposureEv.isFinite() && exposureEv in MIN_EV..MAX_EV) }
    companion object { const val MIN_EV = -3f; const val MAX_EV = 3f }
}
object PhotoMemoryPolicy {
    fun previewLongEdge(context: Context): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (manager.isLowRamDevice || manager.memoryClass <= LOW_MEMORY_CLASS_MB) 2048 else 4096
    }
    private const val LOW_MEMORY_CLASS_MB = 192
    const val COMPARISON_LONG_EDGE = 2048
}
