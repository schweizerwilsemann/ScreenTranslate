package com.screentranslate.core.capture

import com.screentranslate.core.capture.model.CapturedFrame
import javax.inject.Inject
import kotlin.math.abs

class FrameDiffEngine @Inject constructor() {
    fun hasSignificantChange(
        previous: CapturedFrame?,
        current: CapturedFrame,
        threshold: Float = DEFAULT_THRESHOLD,
    ): Boolean {
        if (previous == null) return true
        if (previous.bitmap.width != current.bitmap.width) return true
        if (previous.bitmap.height != current.bitmap.height) return true

        val elapsedMillis = abs(current.timestampMillis - previous.timestampMillis)
        return elapsedMillis >= MIN_FRAME_INTERVAL_MS && threshold <= DEFAULT_THRESHOLD
    }

    private companion object {
        const val DEFAULT_THRESHOLD = 0.05f
        const val MIN_FRAME_INTERVAL_MS = 250L
    }
}
