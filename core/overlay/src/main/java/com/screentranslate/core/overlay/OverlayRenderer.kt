package com.screentranslate.core.overlay

import android.graphics.Rect
import javax.inject.Inject

class OverlayRenderer @Inject constructor() {
    fun render(view: OverlayView, entries: List<Pair<String, Rect>>, sourceWidth: Int = 0, sourceHeight: Int = 0) {
        view.render(entries, sourceWidth, sourceHeight)
    }
}
