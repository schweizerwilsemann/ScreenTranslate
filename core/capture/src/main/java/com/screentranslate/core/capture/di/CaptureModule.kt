package com.screentranslate.core.capture.di

import com.screentranslate.core.capture.FrameDiffEngine
import com.screentranslate.core.capture.ScreenCaptureManager
import com.screentranslate.core.capture.repository.CaptureRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CaptureModule {
    @Provides
    @Singleton
    fun provideCaptureRepository(manager: ScreenCaptureManager): CaptureRepository = manager

    @Provides
    @Singleton
    fun provideFrameDiffEngine(): FrameDiffEngine = FrameDiffEngine()
}
