package com.screentranslate.core.capture.model

import android.graphics.Rect

data class RegionOfInterest(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun toRect(): Rect = Rect(left, top, right, bottom)
}
