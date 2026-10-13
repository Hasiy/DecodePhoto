/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** Deterministic short AVC fixture for video extraction and frame decoding. */
object SyntheticVideo {
    fun write(file: File) {
        val width = 256; val height = 256
        val codec = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                val colors = codec.codecInfo.getCapabilitiesForType("video/avc").colorFormats
                val color = listOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible).first { it in colors }
                setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
                setInteger(MediaFormat.KEY_BIT_RATE, 1000000); setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var inputFrame = 0; var track = -1; var done = false
            val info = MediaCodec.BufferInfo()
            val timeout = System.nanoTime() + 10_000_000_000L
            while (!done && System.nanoTime() < timeout) {
                if (inputFrame <= 8) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        if (inputFrame == 8) codec.queueInputBuffer(index, 0, 0, inputFrame * 250000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        else {
                            repeat(width * height) { buffer.put((40 + inputFrame * 20).toByte()) }
                            repeat(width * height / 2) { buffer.put(128.toByte()) }
                            codec.queueInputBuffer(index, 0, width * height * 3 / 2, inputFrame * 250000L, 0)
                        }
                        inputFrame++
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)!!
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(started); buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
            check(done) { "Synthetic video encoder timed out" }
        } finally {
            codec.release()
            if (started) muxer.stop()
            muxer.release()
        }
    }
}
