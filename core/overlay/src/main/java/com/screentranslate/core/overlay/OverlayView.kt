package com.screentranslate.core.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import com.screentranslate.core.overlay.model.OverlayConfig

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var config = OverlayConfig()
    private var entries: List<Pair<String, Rect>> = emptyList()
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0

    private val density = resources.displayMetrics.density
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.textColor
        textSize = config.textSizeSp * resources.displayMetrics.scaledDensity
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.backgroundColor
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.highlightColor
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.borderColor
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    fun configure(config: OverlayConfig) {
        this.config = config
        textPaint.color = config.textColor
        textPaint.textSize = config.textSizeSp * resources.displayMetrics.scaledDensity
        backgroundPaint.color = config.backgroundColor
        highlightPaint.color = config.highlightColor
        borderPaint.color = config.borderColor
        invalidate()
    }

    fun render(entries: List<Pair<String, Rect>>, sourceWidth: Int = 0, sourceHeight: Int = 0) {
        if (this.entries == entries && this.sourceWidth == sourceWidth && this.sourceHeight == sourceHeight) return
        this.entries = entries
        this.sourceWidth = sourceWidth
        this.sourceHeight = sourceHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        entries.forEach { (text, bounds) ->
            if (text.isBlank() || bounds.isEmpty) return@forEach

            val textBounds = bounds.toCanvasRect()
            val labelBounds = textBounds.expand(1.5f * density).clampToView()
            val displayText = text.replace('\n', ' ').take(MAX_TEXT_CHARS)

            canvas.drawRoundRect(labelBounds, config.cornerRadiusPx.toFloat(), config.cornerRadiusPx.toFloat(), backgroundPaint)
            canvas.drawRoundRect(textBounds.expand(0.5f * density).clampToView(), 4f * density, 4f * density, highlightPaint)
            canvas.drawRoundRect(labelBounds, config.cornerRadiusPx.toFloat(), config.cornerRadiusPx.toFloat(), borderPaint)
            if (config.drawText) {
                drawSingleLineText(canvas, displayText, labelBounds)
            }
        }
    }

    private fun Rect.toCanvasRect(): RectF {
        val scaleX = if (sourceWidth > 0) width.toFloat() / sourceWidth else 1f
        val scaleY = if (sourceHeight > 0) height.toFloat() / sourceHeight else 1f
        return RectF(
            left * scaleX,
            top * scaleY,
            right * scaleX,
            bottom * scaleY,
        ).clampToView()
    }

    private fun drawSingleLineText(canvas: Canvas, text: String, bounds: RectF) {
        val originalTextSize = textPaint.textSize
        val maxTextSize = config.textSizeSp * resources.displayMetrics.scaledDensity
        textPaint.textSize = (bounds.height() * 0.72f).coerceIn(MIN_TEXT_SIZE_SP * resources.displayMetrics.scaledDensity, maxTextSize)

        val horizontalPadding = (config.paddingPx * 0.5f).coerceAtLeast(3f * density)
        val availableWidth = (bounds.width() - horizontalPadding * 2f).coerceAtLeast(1f)
        val fittedText = TextUtils.ellipsize(text, textPaint, availableWidth, TextUtils.TruncateAt.END).toString()
        val metrics = textPaint.fontMetrics
        val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(fittedText, bounds.left + horizontalPadding, baseline, textPaint)
        textPaint.textSize = originalTextSize
    }

    private fun RectF.expand(amount: Float): RectF =
        RectF(left - amount, top - amount, right + amount, bottom + amount)

    private fun RectF.clampToView(): RectF {
        val margin = EDGE_MARGIN_PX * density
        val rect = RectF(this)
        if (rect.width() > width - margin * 2f) {
            rect.left = margin
            rect.right = width - margin
        }
        if (rect.height() > height - margin * 2f) {
            rect.top = margin
            rect.bottom = height - margin
        }
        if (rect.left < margin) {
            rect.offset(margin - rect.left, 0f)
        }
        if (rect.right > width - margin) {
            rect.offset(width - margin - rect.right, 0f)
        }
        if (rect.top < margin) {
            rect.offset(0f, margin - rect.top)
        }
        if (rect.bottom > height - margin) {
            rect.offset(0f, height - margin - rect.bottom)
        }
        return rect
    }

    private companion object {
        const val MAX_TEXT_CHARS = 96
        const val EDGE_MARGIN_PX = 8
        const val MIN_TEXT_SIZE_SP = 9f
    }
}
