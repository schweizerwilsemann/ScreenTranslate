package com.screentranslate.core.translation.offline

import com.screentranslate.core.common.result.Result
import com.screentranslate.core.translation.TranslationEngine
import com.screentranslate.core.translation.model.LanguagePair
import com.screentranslate.core.translation.model.Translation
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation as MlKitTranslation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class MlKitTranslateEngine @Inject constructor() : TranslationEngine {
    private val translators = ConcurrentHashMap<LanguagePair, Translator>()
    private val downloadConditions = DownloadConditions.Builder().build()

    override val engineType: TranslationEngine.EngineType = TranslationEngine.EngineType.OFFLINE

    override suspend fun translate(text: String, from: String, to: String): Result<Translation> {
        val normalizedText = text.trim()
        if (normalizedText.isBlank() || from == to) {
            return Result.Success(
                Translation(
                    originalText = text,
                    translatedText = text,
                    languagePair = LanguagePair(from, to),
                    engineType = engineType,
                ),
            )
        }

        return try {
            val languagePair = LanguagePair(from, to)
            val translatedText = translatorFor(languagePair)
                .also { translator -> translator.downloadModelIfNeeded(downloadConditions).await() }
                .translate(normalizedText)
                .await()

            Result.Success(
                Translation(
                    originalText = normalizedText,
                    translatedText = translatedText.ifBlank { normalizedText },
                    languagePair = languagePair,
                    engineType = engineType,
                ),
            )
        } catch (exception: Exception) {
            Result.Error(exception)
        }
    }

    override suspend fun translateBatch(texts: List<String>, from: String, to: String): Result<List<Translation>> {
        val translations = mutableListOf<Translation>()
        texts.forEach { text ->
            when (val result = translate(text, from, to)) {
                is Result.Success -> translations += result.data
                is Result.Error -> return result
                Result.Loading -> return Result.Loading
            }
        }
        return Result.Success(translations)
    }

    override fun isAvailable(): Boolean = true

    private fun translatorFor(languagePair: LanguagePair): Translator =
        translators.getOrPut(languagePair) {
            val source = TranslateLanguage.fromLanguageTag(languagePair.source)
                ?: throw IllegalArgumentException("Unsupported source language: ${languagePair.source}")
            val target = TranslateLanguage.fromLanguageTag(languagePair.target)
                ?: throw IllegalArgumentException("Unsupported target language: ${languagePair.target}")
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
            MlKitTranslation.getClient(options)
        }

    private suspend fun <T> Task<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            addOnFailureListener { exception ->
                if (continuation.isActive) continuation.resumeWithException(exception)
            }
            addOnCanceledListener {
                if (continuation.isActive) {
                    continuation.resumeWithException(CancellationException("ML Kit translation task was cancelled"))
                }
            }
        }
}
