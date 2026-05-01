package com.screentranslate.core.overlay

import android.graphics.Rect
import javax.inject.Inject

class OverlayRenderer @Inject constructor() {
    fun render(view: OverlayView, entries: List<Pair<String, Rect>>) {
        view.render(entries)
    }
}
