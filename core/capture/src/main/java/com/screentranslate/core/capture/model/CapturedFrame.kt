package com.screentranslate.core.capture.model

import android.graphics.Bitmap

data class CapturedFrame(
    val bitmap: Bitmap,
    val timestampMillis: Long = System.currentTimeMillis(),
    val region: RegionOfInterest? = null,
)
