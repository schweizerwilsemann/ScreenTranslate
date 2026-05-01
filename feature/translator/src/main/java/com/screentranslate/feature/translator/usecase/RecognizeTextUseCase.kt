package com.screentranslate.feature.translator.usecase

import android.graphics.Bitmap
import com.screentranslate.core.common.base.BaseUseCase
import com.screentranslate.core.common.result.Result
import com.screentranslate.core.ocr.model.TextBlock
import com.screentranslate.core.ocr.repository.OcrRepository
import javax.inject.Inject

class RecognizeTextUseCase @Inject constructor(
    private val repository: OcrRepository,
) : BaseUseCase<Bitmap, List<TextBlock>>() {
    override suspend fun execute(params: Bitmap): List<TextBlock> =
        when (val result = repository.recognize(params)) {
            is Result.Success -> result.data
            is Result.Error -> throw result.exception
            Result.Loading -> error("OCR is still loading.")
        }
}
