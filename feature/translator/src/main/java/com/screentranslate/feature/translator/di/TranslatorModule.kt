package com.screentranslate.feature.translator.di

import com.screentranslate.feature.translator.TranslationPipeline
import com.screentranslate.feature.translator.usecase.CaptureScreenUseCase
import com.screentranslate.feature.translator.usecase.ManageOverlayUseCase
import com.screentranslate.feature.translator.usecase.RecognizeTextUseCase
import com.screentranslate.feature.translator.usecase.TranslateTextUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TranslatorModule {
    @Provides
    @Singleton
    fun provideTranslationPipeline(
        captureScreen: CaptureScreenUseCase,
        recognizeText: RecognizeTextUseCase,
        translateText: TranslateTextUseCase,
        manageOverlay: ManageOverlayUseCase,
    ): TranslationPipeline =
        TranslationPipeline(captureScreen, recognizeText, translateText, manageOverlay)
}
