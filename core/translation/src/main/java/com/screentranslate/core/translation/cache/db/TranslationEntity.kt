package com.screentranslate.core.translation.cache.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.screentranslate.core.translation.TranslationEngine
import com.screentranslate.core.translation.model.LanguagePair
import com.screentranslate.core.translation.model.Translation

@Entity(
    tableName = "translations",
    indices = [
        Index(
            value = ["originalText", "sourceLanguage", "targetLanguage"],
            unique = true,
        ),
    ],
)
data class TranslationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val engineType: String,
    val createdAtMillis: Long,
) {
    fun toDomain(): Translation =
        Translation(
            originalText = originalText,
            translatedText = translatedText,
            languagePair = LanguagePair(sourceLanguage, targetLanguage),
            engineType = TranslationEngine.EngineType.valueOf(engineType),
            createdAtMillis = createdAtMillis,
        )

    companion object {
        fun fromDomain(translation: Translation): TranslationEntity =
            TranslationEntity(
                originalText = translation.originalText,
                translatedText = translation.translatedText,
                sourceLanguage = translation.languagePair.source,
                targetLanguage = translation.languagePair.target,
                engineType = translation.engineType.name,
                createdAtMillis = translation.createdAtMillis,
            )
    }
}
