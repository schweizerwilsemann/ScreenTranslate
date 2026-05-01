package com.screentranslate.core.ocr.di

import com.screentranslate.core.ocr.MlKitOcrEngine
import com.screentranslate.core.ocr.OcrEngine
import com.screentranslate.core.ocr.repository.OcrRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OcrModule {
    @Provides
    @Singleton
    fun provideOcrEngine(engine: MlKitOcrEngine): OcrEngine = engine

    @Provides
    @Singleton
    fun provideOcrRepository(engine: OcrEngine): OcrRepository = OcrRepository(engine)
}
