package com.dafei.voicenotes.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dafei.voicenotes.VoiceNotesApp
import com.dafei.voicenotes.data.local.DiaryEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DiaryListViewModel(private val app: VoiceNotesApp) : ViewModel() {
    val entries = app.repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteEntries(ids: List<Long>) {
        viewModelScope.launch {
            app.repository.deleteEntries(ids)
        }
    }

    companion object {
        fun factory(app: VoiceNotesApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DiaryListViewModel(app) as T
            }
        }
    }
}
