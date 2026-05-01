package com.screentranslate.core.common.extension

import com.screentranslate.core.common.result.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

fun <T> Flow<T>.asResult(): Flow<Result<T>> =
    map<T, Result<T>> { value -> Result.Success(value) }
        .onStart { emit(Result.Loading) }
        .catch { throwable -> emit(Result.Error(throwable)) }
