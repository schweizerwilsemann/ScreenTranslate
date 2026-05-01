package com.screentranslate.core.translation

import com.screentranslate.core.common.result.Result
import com.screentranslate.core.translation.model.Translation

interface TranslationEngine {
    suspend fun translate(text: String, from: String, to: String): Result<Translation>
    suspend fun translateBatch(texts: List<String>, from: String, to: String): Result<List<Translation>>
    fun isAvailable(): Boolean
    val engineType: EngineType

    enum class EngineType { ONLINE, OFFLINE }
}
