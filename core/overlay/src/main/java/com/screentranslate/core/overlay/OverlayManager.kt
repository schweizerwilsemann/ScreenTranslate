package com.screentranslate.core.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.screentranslate.core.common.base.BaseRepository
import com.screentranslate.core.overlay.model.OverlayConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class OverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : BaseRepository {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var overlayView: OverlayView? = null

    fun show(config: OverlayConfig = OverlayConfig()): Boolean {
        if (overlayView != null) return true
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission is not granted")
            return false
        }

        val view = OverlayView(context).apply {
            configure(config)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        return runCatching {
            windowManager.addView(view, params)
            overlayView = view
        }.onFailure { exception ->
            Log.e(TAG, "Unable to show overlay window", exception)
        }.isSuccess
    }

    fun update(entries: List<Pair<String, Rect>>, sourceWidth: Int = 0, sourceHeight: Int = 0) {
        Log.d(TAG, "Overlay update entries=${entries.size} source=${sourceWidth}x$sourceHeight")
        overlayView?.render(entries, sourceWidth, sourceHeight)
    }

    fun updateSubtitle(text: String) {
        Log.d(TAG, "Subtitle overlay update chars=${text.length}")
        overlayView?.renderSubtitle(text)
    }

    fun hide() {
        overlayView?.let(windowManager::removeView)
        overlayView = null
    }

    override suspend fun clear() {
        hide()
    }

    @Suppress("DEPRECATION")
    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private companion object {
        const val TAG = "ScreenOverlayManager"
    }
}
