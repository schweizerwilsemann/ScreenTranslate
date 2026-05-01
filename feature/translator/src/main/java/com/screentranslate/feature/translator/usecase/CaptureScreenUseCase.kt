package com.screentranslate.feature.translator.usecase

import com.screentranslate.core.capture.model.CapturedFrame
import com.screentranslate.core.capture.model.RegionOfInterest
import com.screentranslate.core.capture.repository.CaptureRepository
import com.screentranslate.core.common.base.BaseUseCase
import com.screentranslate.core.common.result.Result
import javax.inject.Inject

class CaptureScreenUseCase @Inject constructor(
    private val repository: CaptureRepository,
) : BaseUseCase<RegionOfInterest?, CapturedFrame>() {
    override suspend fun execute(params: RegionOfInterest?): CapturedFrame =
        when (val result = repository.capture(params)) {
            is Result.Success -> result.data
            is Result.Error -> throw result.exception
            Result.Loading -> error("Capture is still loading.")
        }
}
