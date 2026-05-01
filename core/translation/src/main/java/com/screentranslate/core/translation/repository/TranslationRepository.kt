package com.screentranslate.core.translation.repository

import com.screentranslate.core.common.base.BaseRepository
import com.screentranslate.core.common.result.Result
import com.screentranslate.core.translation.TranslationEngine
import com.screentranslate.core.translation.cache.TranslationCache
import com.screentranslate.core.translation.model.LanguagePair
import com.screentranslate.core.translation.model.Translation
import javax.inject.Inject

class TranslationRepository @Inject constructor(
    private val engine: TranslationEngine,
    private val cache: TranslationCache,
) : BaseRepository {
    suspend fun translate(text: String, pair: LanguagePair): Result<Translation> {
        cache.get(text, pair)?.let { cached -> return Result.Success(cached) }
        return when (val translated = engine.translate(text, pair.source, pair.target)) {
            is Result.Success -> {
                cache.put(translated.data)
                translated
            }
            is Result.Error -> translated
            Result.Loading -> translated
        }
    }

    suspend fun translateBatch(texts: List<String>, pair: LanguagePair): Result<List<Translation>> =
        engine.translateBatch(texts, pair.source, pair.target)

    override suspend fun clear() {
        cache.clear()
    }
}
