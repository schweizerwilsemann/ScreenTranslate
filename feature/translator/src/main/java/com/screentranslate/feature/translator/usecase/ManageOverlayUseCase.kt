package com.screentranslate.feature.translator.usecase

import android.graphics.Rect
import com.screentranslate.core.common.base.BaseUseCase
import com.screentranslate.core.overlay.OverlayManager
import com.screentranslate.core.overlay.model.OverlayConfig
import javax.inject.Inject

class ManageOverlayUseCase @Inject constructor(
    private val overlayManager: OverlayManager,
) : BaseUseCase<ManageOverlayUseCase.Params, Unit>() {
    override suspend fun execute(params: Params) {
        when (params.action) {
            Action.SHOW -> overlayManager.show(params.config)
            Action.UPDATE -> overlayManager.update(params.entries)
            Action.HIDE -> overlayManager.hide()
        }
    }

    data class Params(
        val action: Action,
        val entries: List<Pair<String, Rect>> = emptyList(),
        val config: OverlayConfig = OverlayConfig(),
    )

    enum class Action { SHOW, UPDATE, HIDE }
}
