package com.screentranslate.core.translation.online.api.dto

data class TranslationRequest(
    val q: List<String>,
    val source: String,
    val target: String,
    val format: String = "text",
)
