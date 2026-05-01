package com.screentranslate.core.translation.online.api

import com.screentranslate.core.translation.online.api.dto.TranslationRequest
import com.screentranslate.core.translation.online.api.dto.TranslationResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface TranslationApiService {
    @POST("language/translate/v2")
    suspend fun translate(@Body request: TranslationRequest): TranslationResponse
}
