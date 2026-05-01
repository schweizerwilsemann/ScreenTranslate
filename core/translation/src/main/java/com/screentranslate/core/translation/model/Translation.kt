package com.screentranslate.core.translation.model

import com.screentranslate.core.translation.TranslationEngine

data class Translation(
    val originalText: String,
    val translatedText: String,
    val languagePair: LanguagePair,
    val engineType: TranslationEngine.EngineType,
    val createdAtMillis: Long = System.currentTimeMillis(),
)
