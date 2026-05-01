package com.screentranslate.core.common.base

import com.screentranslate.core.common.result.Result

abstract class BaseUseCase<in P, R> {
    suspend operator fun invoke(params: P): Result<R> = try {
        Result.Success(execute(params))
    } catch (e: Exception) {
        Result.Error(e)
    }

    protected abstract suspend fun execute(params: P): R
}

abstract class NoParamUseCase<R> {
    suspend operator fun invoke(): Result<R> = try {
        Result.Success(execute())
    } catch (e: Exception) {
        Result.Error(e)
    }

    protected abstract suspend fun execute(): R
}
