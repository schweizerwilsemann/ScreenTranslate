package com.screentranslate.core.ocr

import android.graphics.Bitmap
import com.screentranslate.core.common.result.Result
import com.screentranslate.core.ocr.model.BoundingBox
import com.screentranslate.core.ocr.model.TextBlock
import javax.inject.Inject

class TesseractOcrEngine @Inject constructor() : OcrEngine {
    override suspend fun recognize(bitmap: Bitmap): Result<List<TextBlock>> =
        Result.Error(UnsupportedOperationException("Tesseract engine is a scaffold placeholder."))

    override suspend fun recognizeRegion(bitmap: Bitmap, region: BoundingBox): Result<List<TextBlock>> =
        Result.Error(UnsupportedOperationException("Tesseract region OCR is a scaffold placeholder."))

    override fun release() = Unit
}
