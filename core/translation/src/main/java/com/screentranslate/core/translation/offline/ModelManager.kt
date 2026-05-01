package com.screentranslate.core.translation.offline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun modelFile(languagePair: String): File =
        File(context.filesDir, "translation-models/$languagePair.tflite")

    fun isDownloaded(languagePair: String): Boolean = modelFile(languagePair).exists()
}
