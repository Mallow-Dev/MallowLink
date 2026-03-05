package com.mallowlink.app.ui.sources

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mallowlink.app.data.indexing.FileIndexer
import com.mallowlink.app.data.repository.SourceRepository
import com.mallowlink.app.domain.model.IndexedSource
import com.mallowlink.app.domain.model.IndexingState
import com.mallowlink.app.domain.model.SourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourcesUiState(
    val sources: List<IndexedSource> = emptyList(),
    val indexingState: IndexingState = IndexingState.Idle,
    val error: String? = null,
)

@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val fileIndexer: FileIndexer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SourcesUiState())
    val uiState: StateFlow<SourcesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sourceRepository.observeSources().collect { sources ->
                _uiState.update { it.copy(sources = sources) }
            }
        }
        viewModelScope.launch {
            fileIndexer.indexingState.collect { state ->
                _uiState.update { it.copy(indexingState = state) }
            }
        }
    }

    fun addSource(uri: Uri) {
        viewModelScope.launch {
            try {
                val displayName = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringAfterLast('%')
                    ?.ifBlank { "Folder" }
                    ?: "Folder"
                sourceRepository.addSource(uri, displayName, SourceType.SAF_TREE)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun removeSource(sourceId: String) {
        viewModelScope.launch {
            sourceRepository.removeSource(sourceId)
        }
    }

    fun toggleSource(sourceId: String, enabled: Boolean) {
        viewModelScope.launch {
            sourceRepository.toggleSource(sourceId, enabled)
        }
    }

    fun reindexSource(sourceId: String) {
        viewModelScope.launch {
            sourceRepository.reindexSource(sourceId)
        }
    }

    fun pauseIndexing() { fileIndexer.pause() }
    fun resumeIndexing() { fileIndexer.resume() }
}
