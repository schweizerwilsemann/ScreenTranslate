package com.screentranslate.core.overlay.model

import android.graphics.Color

data class OverlayConfig(
    val textSizeSp: Float = 17f,
    val textColor: Int = Color.BLACK,
    val backgroundColor: Int = 0x22FFF59D,
    val highlightColor: Int = 0x22FFEB3B,
    val borderColor: Int = 0xCCFFC107.toInt(),
    val paddingPx: Int = 10,
    val cornerRadiusPx: Int = 10,
    val drawText: Boolean = false,
    val subtitleTextSizeSp: Float = 24f,
    val subtitleTextColor: Int = 0xFFFFEB3B.toInt(),
    val subtitleBackgroundColor: Int = 0xEE000000.toInt(),
    val subtitleBottomRatio: Float = 0.58f,
    val subtitleMaxWidthRatio: Float = 0.88f,
    val subtitleMaxLines: Int = 3,
)
