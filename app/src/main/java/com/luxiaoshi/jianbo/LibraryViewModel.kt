package com.luxiaoshi.jianbo

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.luxiaoshi.jianbo.data.LibraryRepository
import com.luxiaoshi.jianbo.data.LibraryUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LibraryRepository(application)
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()
    private var hasMediaPermission = false

    fun setPermission(granted: Boolean) {
        hasMediaPermission = granted
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, permissionGranted = hasMediaPermission, errorMessage = null) }
            runCatching { repository.loadLibrary(hasMediaPermission) }
                .onSuccess { groups ->
                    _uiState.update {
                        it.copy(isLoading = false, groups = groups, hiddenGroupCount = repository.hiddenGroupCount())
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "读取视频失败") }
                }
        }
    }

    fun importFolder(uri: Uri) {
        repository.addManualTree(uri)
        refresh()
    }

    fun hideGroups(keys: Set<String>) {
        repository.hideGroups(keys)
        refresh()
    }

    fun restoreHiddenGroups() {
        repository.restoreHiddenGroups()
        refresh()
    }
}
