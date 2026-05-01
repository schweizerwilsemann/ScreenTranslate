package com.screentranslate.core.ocr

import android.graphics.Bitmap
import com.screentranslate.core.common.result.Result
import com.screentranslate.core.ocr.model.BoundingBox
import com.screentranslate.core.ocr.model.TextBlock

interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): Result<List<TextBlock>>
    suspend fun recognizeRegion(bitmap: Bitmap, region: BoundingBox): Result<List<TextBlock>>
    fun release()
}
