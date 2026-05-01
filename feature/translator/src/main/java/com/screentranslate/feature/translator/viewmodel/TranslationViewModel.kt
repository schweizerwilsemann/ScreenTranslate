package com.screentranslate.feature.translator.viewmodel

import androidx.lifecycle.viewModelScope
import com.screentranslate.core.capture.model.RegionOfInterest
import com.screentranslate.core.common.base.BaseViewModel
import com.screentranslate.core.common.result.Result
import com.screentranslate.core.translation.model.LanguagePair
import com.screentranslate.core.translation.model.Translation
import com.screentranslate.feature.translator.TranslationPipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TranslationViewModel @Inject constructor(
    private val pipeline: TranslationPipeline,
) : BaseViewModel() {
    private val mutableTranslations = MutableStateFlow<List<Translation>>(emptyList())
    val translations: StateFlow<List<Translation>> = mutableTranslations.asStateFlow()

    fun translateLatest(region: RegionOfInterest?, languagePair: LanguagePair) {
        viewModelScope.launch {
            setLoading()
            when (val result = pipeline.translateFrame(region, languagePair)) {
                is Result.Success -> {
                    mutableTranslations.value = result.data
                    setIdle()
                }
                is Result.Error -> setError(result.message ?: result.exception.message.orEmpty())
                Result.Loading -> setLoading()
            }
        }
    }
}
