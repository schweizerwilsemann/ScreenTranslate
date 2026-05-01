package com.screentranslate.core.translation.cache

import com.screentranslate.core.translation.cache.db.TranslationDao
import com.screentranslate.core.translation.cache.db.TranslationEntity
import com.screentranslate.core.translation.model.LanguagePair
import com.screentranslate.core.translation.model.Translation
import javax.inject.Inject

class TranslationCache @Inject constructor(
    private val dao: TranslationDao,
) {
    suspend fun get(text: String, pair: LanguagePair): Translation? =
        dao.find(text, pair.source, pair.target)?.toDomain()

    suspend fun put(translation: Translation) {
        dao.upsert(TranslationEntity.fromDomain(translation))
    }

    suspend fun clear() {
        dao.clear()
    }
}
