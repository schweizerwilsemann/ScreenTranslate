package com.screentranslate.core.overlay.model

import android.graphics.Color

data class OverlayConfig(
    val textSizeSp: Float = 16f,
    val textColor: Int = Color.WHITE,
    val backgroundColor: Int = 0xCC000000.toInt(),
    val paddingPx: Int = 12,
)
