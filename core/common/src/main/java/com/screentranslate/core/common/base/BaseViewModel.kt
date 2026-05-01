package com.screentranslate.core.common.base

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

abstract class BaseViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    protected fun setLoading() {
        _uiState.value = UiState.Loading
    }

    protected fun setError(message: String) {
        _uiState.value = UiState.Error(message)
    }

    protected fun setIdle() {
        _uiState.value = UiState.Idle
    }

    sealed class UiState {
        data object Idle : UiState()
        data object Loading : UiState()
        data class Error(val message: String) : UiState()
    }
}
