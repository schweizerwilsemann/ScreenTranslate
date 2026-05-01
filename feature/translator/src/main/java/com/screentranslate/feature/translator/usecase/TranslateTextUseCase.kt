package com.screentranslate.feature.translator.usecase

import com.screentranslate.core.common.base.BaseUseCase
import com.screentranslate.core.common.result.Result
import com.screentranslate.core.translation.model.LanguagePair
import com.screentranslate.core.translation.model.Translation
import com.screentranslate.core.translation.repository.TranslationRepository
import javax.inject.Inject

class TranslateTextUseCase @Inject constructor(
    private val repository: TranslationRepository,
) : BaseUseCase<TranslateTextUseCase.Params, Translation>() {
    override suspend fun execute(params: Params): Translation =
        when (val result = repository.translate(params.text, params.languagePair)) {
            is Result.Success -> result.data
            is Result.Error -> throw result.exception
            Result.Loading -> error("Translation is still loading.")
        }

    data class Params(
        val text: String,
        val languagePair: LanguagePair,
    )
}
