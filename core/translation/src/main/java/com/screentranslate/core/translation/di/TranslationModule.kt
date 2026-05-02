package com.screentranslate.core.translation.di

import android.content.Context
import androidx.room.Room
import com.screentranslate.core.translation.TranslationEngine
import com.screentranslate.core.translation.cache.TranslationCache
import com.screentranslate.core.translation.cache.db.TranslationDao
import com.screentranslate.core.translation.cache.db.TranslationDatabase
import com.screentranslate.core.translation.offline.MlKitTranslateEngine
import com.screentranslate.core.translation.online.api.TranslationApiService
import com.screentranslate.core.translation.repository.TranslationRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TranslationModule {
    @Provides
    @Singleton
    fun provideTranslationApiService(): TranslationApiService =
        Retrofit.Builder()
            .baseUrl("https://translation.googleapis.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TranslationApiService::class.java)

    @Provides
    @Singleton
    fun provideTranslationDatabase(@ApplicationContext context: Context): TranslationDatabase =
        Room.databaseBuilder(context, TranslationDatabase::class.java, "translation_cache.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideTranslationDao(database: TranslationDatabase): TranslationDao = database.translationDao()

    @Provides
    @Singleton
    fun provideTranslationEngine(engine: MlKitTranslateEngine): TranslationEngine = engine

    @Provides
    @Singleton
    fun provideTranslationCache(dao: TranslationDao): TranslationCache = TranslationCache(dao)

    @Provides
    @Singleton
    fun provideTranslationRepository(
        engine: TranslationEngine,
        cache: TranslationCache,
    ): TranslationRepository = TranslationRepository(engine, cache)
}
