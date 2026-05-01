package com.screentranslate.core.ocr.model

data class TextBlock(
    val text: String,
    val boundingBox: BoundingBox,
    val confidence: Float? = null,
)
