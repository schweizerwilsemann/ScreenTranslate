package com.screentranslate.core.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.screentranslate.core.common.result.Result
import com.screentranslate.core.ocr.model.BoundingBox
import com.screentranslate.core.ocr.model.TextBlock
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitOcrEngine @Inject constructor() : OcrEngine {
    private val recognizers = listOf(
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
    )

    override suspend fun recognize(bitmap: Bitmap): Result<List<TextBlock>> =
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val blocks = recognizers
                .map { recognizer -> recognizer.process(image) }
                .flatMap { task -> task.await().toTextBlocks() }
                .dedupeNearbyLines()
            Result.Success(blocks)
        } catch (exception: Exception) {
            Result.Error(exception)
        }

    override suspend fun recognizeRegion(bitmap: Bitmap, region: BoundingBox): Result<List<TextBlock>> {
        val rect = region.toRect().clampTo(bitmap.width, bitmap.height)
        if (rect.width() <= 0 || rect.height() <= 0) {
            return Result.Error(IllegalArgumentException("Region is outside the bitmap bounds."))
        }
        val cropped = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        return try {
            recognize(cropped)
        } finally {
            cropped.recycle()
        }
    }

    override fun release() {
        recognizers.forEach(TextRecognizer::close)
    }

    private fun Text.toTextBlocks(): List<TextBlock> =
        textBlocks.flatMap { block ->
            block.lines.takeIf { it.isNotEmpty() }?.map { line ->
                val rect = line.boundingBox ?: Rect()
                TextBlock(
                    text = line.text,
                    boundingBox = BoundingBox(rect.left, rect.top, rect.right, rect.bottom),
                )
            } ?: listOf(
                TextBlock(
                    text = block.text,
                    boundingBox = (block.boundingBox ?: Rect()).toBoundingBox(),
                ),
            )
        }

    private fun Rect.toBoundingBox(): BoundingBox =
        BoundingBox(left, top, right, bottom)

    private suspend fun <T> Task<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            addOnFailureListener { exception ->
                if (continuation.isActive) continuation.resumeWithException(exception)
            }
            addOnCanceledListener {
                if (continuation.isActive) {
                    continuation.resumeWithException(CancellationException("ML Kit OCR task was cancelled"))
                }
            }
        }

    private fun List<TextBlock>.dedupeNearbyLines(): List<TextBlock> {
        val deduped = mutableListOf<TextBlock>()
        sortedWith(compareBy<TextBlock> { it.boundingBox.top }.thenBy { it.boundingBox.left })
            .forEach { block ->
                val normalized = block.text.normalizedForDedupe()
                val duplicate = deduped.any { existing ->
                    normalized == existing.text.normalizedForDedupe() &&
                        block.boundingBox.toRect().isNear(existing.boundingBox.toRect())
                }
                if (!duplicate) {
                    deduped += block
                }
            }
        return deduped
    }

    private fun String.normalizedForDedupe(): String =
        lowercase().filter { it.isLetterOrDigit() }

    private fun Rect.isNear(other: Rect): Boolean =
        kotlin.math.abs(centerX() - other.centerX()) <= DEDUPE_CENTER_TOLERANCE_PX &&
            kotlin.math.abs(centerY() - other.centerY()) <= DEDUPE_CENTER_TOLERANCE_PX

    private fun Rect.clampTo(maxWidth: Int, maxHeight: Int): Rect =
        Rect(
            left.coerceIn(0, maxWidth),
            top.coerceIn(0, maxHeight),
            right.coerceIn(0, maxWidth),
            bottom.coerceIn(0, maxHeight),
        )

    private companion object {
        const val DEDUPE_CENTER_TOLERANCE_PX = 12
    }
}
