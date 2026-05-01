package com.screentranslate.core.translation.online.api.dto

data class TranslationResponse(
    val data: Data? = null,
) {
    data class Data(
        val translations: List<Item> = emptyList(),
    )

    data class Item(
        val translatedText: String,
        val detectedSourceLanguage: String? = null,
    )
}
