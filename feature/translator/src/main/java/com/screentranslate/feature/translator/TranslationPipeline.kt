package com.screentranslate.feature.translator

import android.graphics.Rect
import com.screentranslate.core.capture.model.RegionOfInterest
import com.screentranslate.core.common.result.Result
import com.screentranslate.core.ocr.model.TextBlock
import com.screentranslate.core.translation.model.LanguagePair
import com.screentranslate.core.translation.model.Translation
import com.screentranslate.feature.translator.usecase.CaptureScreenUseCase
import com.screentranslate.feature.translator.usecase.ManageOverlayUseCase
import com.screentranslate.feature.translator.usecase.RecognizeTextUseCase
import com.screentranslate.feature.translator.usecase.TranslateTextUseCase

class TranslationPipeline(
    private val captureScreen: CaptureScreenUseCase,
    private val recognizeText: RecognizeTextUseCase,
    private val translateText: TranslateTextUseCase,
    private val manageOverlay: ManageOverlayUseCase,
) {
    suspend fun translateFrame(
        region: RegionOfInterest?,
        languagePair: LanguagePair,
    ): Result<List<Translation>> {
        val frame = when (val captureResult = captureScreen(region)) {
            is Result.Success -> captureResult.data
            is Result.Error -> return captureResult
            Result.Loading -> return Result.Loading
        }

        val blocks = when (val ocrResult = recognizeText(frame.bitmap)) {
            is Result.Success -> ocrResult.data
            is Result.Error -> return ocrResult
            Result.Loading -> return Result.Loading
        }

        val translations = mutableListOf<Translation>()
        blocks.forEach { block ->
            when (
                val translationResult = translateText(
                    TranslateTextUseCase.Params(block.text, languagePair),
                )
            ) {
                is Result.Success -> translations += translationResult.data
                is Result.Error -> return translationResult
                Result.Loading -> return Result.Loading
            }
        }

        manageOverlay(
            ManageOverlayUseCase.Params(
                action = ManageOverlayUseCase.Action.UPDATE,
                entries = blocks.toOverlayEntries(translations),
            ),
        )
        return Result.Success(translations)
    }

    private fun List<TextBlock>.toOverlayEntries(translations: List<Translation>): List<Pair<String, Rect>> =
        mapIndexed { index, block ->
            val translatedText = translations.getOrNull(index)?.translatedText.orEmpty()
            translatedText to block.boundingBox.toRect()
        }
}
