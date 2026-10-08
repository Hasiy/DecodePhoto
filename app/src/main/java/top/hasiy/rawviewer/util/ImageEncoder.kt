package top.hasiy.rawviewer.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 圖片附件處理（M4）：
 * 讀取 Uri → 等比縮放（最長邊 ≤ 1024）→ JPEG 85 → data URL（`data:image/jpeg;base64,…`）。
 */
object ImageEncoder {

    private const val MAX_EDGE = 1024f
    private const val JPEG_QUALITY = 85

    fun uriToDataUrl(context: Context, uri: Uri): String? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = scaleDown(bitmap)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val max = maxOf(src.width, src.height)
        if (max <= MAX_EDGE) return src
        val ratio = MAX_EDGE / max
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
