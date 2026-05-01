package com.screentranslate.core.capture.repository

import com.screentranslate.core.common.base.BaseRepository
import com.screentranslate.core.common.result.Result
import com.screentranslate.core.capture.model.CapturedFrame
import com.screentranslate.core.capture.model.RegionOfInterest
import kotlinx.coroutines.flow.Flow

interface CaptureRepository : BaseRepository {
    val frames: Flow<CapturedFrame>

    suspend fun capture(region: RegionOfInterest? = null): Result<CapturedFrame>
}
