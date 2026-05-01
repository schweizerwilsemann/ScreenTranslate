package com.screentranslate.core.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.screentranslate.core.overlay.model.OverlayConfig

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var config = OverlayConfig()
    private var entries: List<Pair<String, Rect>> = emptyList()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.textColor
        textSize = config.textSizeSp * resources.displayMetrics.scaledDensity
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.backgroundColor
    }

    fun configure(config: OverlayConfig) {
        this.config = config
        textPaint.color = config.textColor
        textPaint.textSize = config.textSizeSp * resources.displayMetrics.scaledDensity
        backgroundPaint.color = config.backgroundColor
        invalidate()
    }

    fun render(entries: List<Pair<String, Rect>>) {
        if (this.entries == entries) return
        this.entries = entries
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        entries.forEach { (text, bounds) ->
            canvas.drawRect(bounds, backgroundPaint)
            canvas.drawText(
                text,
                bounds.left + config.paddingPx.toFloat(),
                bounds.bottom - config.paddingPx.toFloat(),
                textPaint,
            )
        }
    }
}
