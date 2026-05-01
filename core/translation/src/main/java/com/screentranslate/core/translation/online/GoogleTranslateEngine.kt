package com.screentranslate.core.translation.online

import com.screentranslate.core.common.result.Result
import com.screentranslate.core.translation.TranslationEngine
import com.screentranslate.core.translation.model.LanguagePair
import com.screentranslate.core.translation.model.Translation
import com.screentranslate.core.translation.online.api.TranslationApiService
import com.screentranslate.core.translation.online.api.dto.TranslationRequest
import javax.inject.Inject

class GoogleTranslateEngine @Inject constructor(
    private val apiService: TranslationApiService,
) : TranslationEngine {
    override val engineType: TranslationEngine.EngineType = TranslationEngine.EngineType.ONLINE

    override suspend fun translate(text: String, from: String, to: String): Result<Translation> =
        try {
            val response = apiService.translate(TranslationRequest(listOf(text), from, to))
            val translated = response.data?.translations?.firstOrNull()?.translatedText.orEmpty()
            Result.Success(
                Translation(
                    originalText = text,
                    translatedText = translated.ifBlank { text },
                    languagePair = LanguagePair(from, to),
                    engineType = engineType,
                ),
            )
        } catch (exception: Exception) {
            Result.Error(exception)
        }

    override suspend fun translateBatch(texts: List<String>, from: String, to: String): Result<List<Translation>> =
        try {
            val response = apiService.translate(TranslationRequest(texts, from, to))
            val translatedItems = response.data?.translations.orEmpty()
            Result.Success(
                texts.mapIndexed { index, source ->
                    Translation(
                        originalText = source,
                        translatedText = translatedItems.getOrNull(index)?.translatedText ?: source,
                        languagePair = LanguagePair(from, to),
                        engineType = engineType,
                    )
                },
            )
        } catch (exception: Exception) {
            Result.Error(exception)
        }

    override fun isAvailable(): Boolean = true
}
