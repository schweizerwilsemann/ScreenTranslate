package com.screentranslate.core.ocr.repository

import android.graphics.Bitmap
import com.screentranslate.core.common.base.BaseRepository
import com.screentranslate.core.common.result.Result
import com.screentranslate.core.ocr.OcrEngine
import com.screentranslate.core.ocr.model.BoundingBox
import com.screentranslate.core.ocr.model.TextBlock
import javax.inject.Inject

class OcrRepository @Inject constructor(
    private val engine: OcrEngine,
) : BaseRepository {
    suspend fun recognize(bitmap: Bitmap): Result<List<TextBlock>> = engine.recognize(bitmap)

    suspend fun recognizeRegion(bitmap: Bitmap, region: BoundingBox): Result<List<TextBlock>> =
        engine.recognizeRegion(bitmap, region)

    override suspend fun clear() {
        engine.release()
    }
}
