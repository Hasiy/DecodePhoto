/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer.raw

import top.hasiy.rawviewer.R

object PhotoFailure {
    fun message(error: Throwable): Int = when {
        error is SecurityException -> R.string.photo_error_access
        error is PhotoInputTooLargeException -> R.string.photo_error_size
        error is OutOfMemoryError -> R.string.photo_error_memory
        error.message.orEmpty().contains("memory", true) -> R.string.photo_error_memory
        error.message.orEmpty().contains("dimensions", true) -> R.string.photo_error_dimensions
        error.message.orEmpty().contains("Unsupported", true) -> R.string.photo_error_unsupported
        else -> R.string.photo_error_corrupt
    }
}
