package com.screentranslate.core.translation.cache.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface TranslationDao {
    @Query(
        "SELECT * FROM translations " +
            "WHERE originalText = :text AND sourceLanguage = :source AND targetLanguage = :target " +
            "LIMIT 1",
    )
    suspend fun find(text: String, source: String, target: String): TranslationEntity?

    @Upsert
    suspend fun upsert(entity: TranslationEntity)

    @Query("DELETE FROM translations")
    suspend fun clear()
}
