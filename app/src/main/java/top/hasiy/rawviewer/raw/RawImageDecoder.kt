/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.raw

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface RawImageDecoder {
    fun decode(uri: Uri, format: PhotoFormat): Flow<RawDecodeResult>
}

class ProgressiveRawImageDecoder(
    private val embeddedPreviewDecoder: EmbeddedPreviewDecoder,
    private val libRawDecoder: LibRawDecoder,
) : RawImageDecoder {

    override fun decode(uri: Uri, format: PhotoFormat): Flow<RawDecodeResult> = decode(uri, format, RawRenderOptions())

    fun decode(uri: Uri, format: PhotoFormat, options: RawRenderOptions): Flow<RawDecodeResult> = flow {
        try {
            embeddedPreviewDecoder.decode(uri)?.let { preview ->
                emit(
                    RawDecodeResult.Success(
                        bitmap = preview,
                        source = RawDecodeResult.Source.EMBEDDED_PREVIEW,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Embedded preview failed: ${error.javaClass.simpleName}")
        }

        try {
            emit(
                RawDecodeResult.Success(
                        bitmap = libRawDecoder.decode(uri, format, options),
                    source = RawDecodeResult.Source.LIBRAW,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "LibRaw decode failed: OutOfMemoryError")
            emit(RawDecodeResult.Failure(RawDecodeResult.Reason.OUT_OF_MEMORY, error))
        } catch (error: Exception) {
            Log.e(TAG, "LibRaw decode failed: ${error.javaClass.simpleName}")
            emit(RawDecodeResult.Failure(RawDecodeResult.Reason.CORRUPTED_FILE, error))
        }
    }

    private companion object {
        const val TAG = "ProgressiveRawDecoder"
    }
}
