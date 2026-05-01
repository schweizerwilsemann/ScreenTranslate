package com.screentranslate.core.overlay.di

import com.screentranslate.core.overlay.OverlayRenderer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OverlayModule {
    @Provides
    @Singleton
    fun provideOverlayRenderer(): OverlayRenderer = OverlayRenderer()
}
