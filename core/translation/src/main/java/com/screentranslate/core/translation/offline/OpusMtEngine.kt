package com.screentranslate.core.translation.offline

import com.screentranslate.core.common.result.Result
import com.screentranslate.core.translation.TranslationEngine
import com.screentranslate.core.translation.model.Translation
import javax.inject.Inject

class OpusMtEngine @Inject constructor(
    private val modelManager: ModelManager,
) : TranslationEngine {
    override val engineType: TranslationEngine.EngineType = TranslationEngine.EngineType.OFFLINE

    override suspend fun translate(text: String, from: String, to: String): Result<Translation> =
        Result.Error(UnsupportedOperationException("Offline OPUS-MT engine is a scaffold placeholder."))

    override suspend fun translateBatch(texts: List<String>, from: String, to: String): Result<List<Translation>> =
        Result.Error(UnsupportedOperationException("Offline OPUS-MT batch translation is a scaffold placeholder."))

    override fun isAvailable(): Boolean = modelManager.isDownloaded("default")
}
