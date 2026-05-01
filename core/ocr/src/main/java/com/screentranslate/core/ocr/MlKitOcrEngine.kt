package com.screentranslate.core.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.screentranslate.core.common.result.Result
import com.screentranslate.core.ocr.model.BoundingBox
import com.screentranslate.core.ocr.model.TextBlock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

class MlKitOcrEngine @Inject constructor() : OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap): Result<List<TextBlock>> =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    continuation.resume(Result.Success(text.toTextBlocks()))
                }
                .addOnFailureListener { exception ->
                    continuation.resume(Result.Error(exception))
                }
        }

    override suspend fun recognizeRegion(bitmap: Bitmap, region: BoundingBox): Result<List<TextBlock>> {
        val rect = region.toRect().clampTo(bitmap.width, bitmap.height)
        if (rect.width() <= 0 || rect.height() <= 0) {
            return Result.Error(IllegalArgumentException("Region is outside the bitmap bounds."))
        }
        val cropped = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        return recognize(cropped)
    }

    override fun release() {
        recognizer.close()
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

    private fun Rect.clampTo(maxWidth: Int, maxHeight: Int): Rect =
        Rect(
            left.coerceIn(0, maxWidth),
            top.coerceIn(0, maxHeight),
            right.coerceIn(0, maxWidth),
            bottom.coerceIn(0, maxHeight),
        )
}
