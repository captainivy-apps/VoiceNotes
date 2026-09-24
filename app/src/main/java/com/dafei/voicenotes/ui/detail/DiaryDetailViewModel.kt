package com.dafei.voicenotes.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dafei.voicenotes.VoiceNotesApp
import com.dafei.voicenotes.asr.AsrPhase
import com.dafei.voicenotes.asr.AsrProgressMessages
import com.dafei.voicenotes.data.AppPrefs
import com.dafei.voicenotes.export.ExportFileNames
import com.dafei.voicenotes.export.FileExporter
import com.dafei.voicenotes.llm.LlmPhase
import com.dafei.voicenotes.llm.LlmProfile
import com.dafei.voicenotes.llm.LlmProgressMessages
import com.dafei.voicenotes.llm.llmConfigFromPrefs
import com.dafei.voicenotes.ui.common.AsrElapsedTimer
import com.dafei.voicenotes.ui.common.LlmProgressAnimator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class DiaryDetailUiState(
    val loading: Boolean = true,
    val entryId: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val audioPath: String? = null,
    val transcript: String = "",
    val diaryText: String = "",
    val prevId: Long? = null,
    val nextId: Long? = null,
    val llmProfiles: List<LlmProfile> = emptyList(),
    val selectedLlmProfileId: String = "",
    val isProcessing: Boolean = false,
    val statusMessage: String = "",
    val asrProgress: Float? = null,
    val asrElapsedMs: Long = 0L,
    val llmProgress: Float? = null,
    val llmElapsedMs: Long = 0L,
    val error: String? = null
)

sealed interface DetailEvent {
    data class Toast(val message: String) : DetailEvent
    data object SwitchToPolished : DetailEvent
    data object NavigateBack : DetailEvent
}

class DiaryDetailViewModel(
    private val app: VoiceNotesApp,
    private val entryId: Long
) : ViewModel() {
    private val _uiState = MutableStateFlow(DiaryDetailUiState(entryId = entryId))
    val uiState = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()
    private val asrTimer = AsrElapsedTimer(viewModelScope)
    private val llmAnimator = LlmProgressAnimator(viewModelScope)

    init {
        refreshLlmProfiles()
        refresh()
    }

    fun refreshLlmProfiles() {
        val profiles = AppPrefs.getLlmProfiles(app)
        val activeId = AppPrefs.getActiveLlmProfileId(app)
        _uiState.update { state ->
            val selected = state.selectedLlmProfileId.takeIf { id ->
                profiles.any { it.id == id }
            } ?: activeId
            state.copy(llmProfiles = profiles, selectedLlmProfileId = selected)
        }
    }

    fun selectLlmProfile(profileId: String) {
        if (_uiState.value.llmProfiles.none { it.id == profileId }) return
        _uiState.update { it.copy(selectedLlmProfileId = profileId) }
    }

    private fun refresh() {
        viewModelScope.launch {
            val entry = app.repository.getById(entryId)
            if (entry == null) {
                _uiState.update { it.copy(loading = false, error = "记事不存在") }
                return@launch
            }
            val (prev, next) = app.repository.neighborIds(entryId)
            _uiState.update {
                it.copy(
                    loading = false,
                    createdAt = entry.createdAt,
                    updatedAt = entry.updatedAt,
                    audioPath = entry.audioPath,
                    transcript = entry.transcriptText.orEmpty(),
                    diaryText = entry.diaryText.orEmpty(),
                    prevId = prev,
                    nextId = next,
                    error = null
                )
            }
        }
    }

    fun updateTranscript(text: String) {
        _uiState.update { it.copy(transcript = text) }
        viewModelScope.launch {
            app.repository.saveTranscript(entryId, text)
        }
    }

    fun updateDiaryText(text: String) {
        _uiState.update { it.copy(diaryText = text) }
        viewModelScope.launch {
            app.repository.saveDiaryText(entryId, text)
        }
    }

    private fun emitEvent(event: DetailEvent) {
        viewModelScope.launch { _events.emit(event) }
    }

    fun runAsr() {
        val path = _uiState.value.audioPath ?: return
        _uiState.update {
            it.copy(
                isProcessing = true,
                asrProgress = 0f,
                asrElapsedMs = 0L,
                statusMessage = AsrProgressMessages.format(AsrPhase.LOADING_MODEL, 0f),
                error = null
            )
        }
        asrTimer.start { elapsed ->
            _uiState.update { it.copy(asrElapsedMs = elapsed) }
        }
        val modelId = AppPrefs.getSelectedAsrModel(app)
        viewModelScope.launch {
            app.whisperEngine.transcribe(modelId, File(path)) { progress, phase ->
                _uiState.update {
                    it.copy(
                        asrProgress = progress,
                        statusMessage = AsrProgressMessages.format(phase, progress)
                    )
                }
            }
                .onSuccess { text ->
                    asrTimer.stop()
                    app.repository.saveTranscript(entryId, text)
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            asrProgress = null,
                            asrElapsedMs = 0L,
                            transcript = text,
                            statusMessage = ""
                        )
                    }
                    emitEvent(DetailEvent.Toast("识别完成"))
                }
                .onFailure { e ->
                    asrTimer.stop()
                    val message = when (e) {
                        is OutOfMemoryError -> "音频过大，内存不足"
                        else -> e.message ?: "识别失败"
                    }
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            asrProgress = null,
                            asrElapsedMs = 0L,
                            statusMessage = "",
                            error = message
                        )
                    }
                    emitEvent(DetailEvent.Toast(message))
                }
        }
    }

    fun copyTranscriptToDiary() {
        val text = _uiState.value.transcript
        viewModelScope.launch {
            app.repository.saveDiaryText(entryId, text)
            _uiState.update { it.copy(diaryText = text) }
            emitEvent(DetailEvent.SwitchToPolished)
            emitEvent(DetailEvent.Toast("已当作润色稿"))
        }
    }

    fun runLlm() {
        val transcript = _uiState.value.transcript
        beginLlmProcessing()
        val profileId = _uiState.value.selectedLlmProfileId
        viewModelScope.launch {
            app.llmClient.polishTranscript(
                llmConfigFromPrefs(app, profileId),
                transcript
            ) { progress, phase ->
                updateLlmProgress(progress, phase)
            }
                .onSuccess { diary ->
                    endLlmProcessing()
                    app.repository.saveDiaryText(entryId, diary)
                    _uiState.update { it.copy(diaryText = diary) }
                    emitEvent(DetailEvent.SwitchToPolished)
                    emitEvent(DetailEvent.Toast("润色完成"))
                }
                .onFailure { e ->
                    endLlmProcessing()
                    val message = e.message ?: "LLM 处理失败"
                    _uiState.update { it.copy(error = message) }
                    emitEvent(DetailEvent.Toast(message))
                }
        }
    }

    private fun beginLlmProcessing() {
        _uiState.update {
            it.copy(
                isProcessing = true,
                llmProgress = 0.05f,
                llmElapsedMs = 0L,
                statusMessage = LlmProgressMessages.format(LlmPhase.PREPARING, 0.05f),
                error = null
            )
        }
        asrTimer.start { elapsed ->
            _uiState.update { state ->
                if (state.llmProgress != null) state.copy(llmElapsedMs = elapsed) else state
            }
        }
        llmAnimator.startWaitingPulse { progress, message ->
            _uiState.update { state ->
                if (state.isProcessing && state.llmProgress != null && state.llmProgress < 0.9f) {
                    state.copy(llmProgress = progress, statusMessage = message)
                } else {
                    state
                }
            }
        }
    }

    private fun updateLlmProgress(progress: Float, phase: LlmPhase) {
        if (phase == LlmPhase.RECEIVING) llmAnimator.stop()
        _uiState.update {
            it.copy(
                llmProgress = progress,
                statusMessage = LlmProgressMessages.format(phase, progress)
            )
        }
    }

    private fun endLlmProcessing() {
        llmAnimator.stop()
        asrTimer.stop()
        _uiState.update {
            it.copy(
                isProcessing = false,
                llmProgress = null,
                llmElapsedMs = 0L,
                statusMessage = ""
            )
        }
    }

    fun deleteAudio() {
        viewModelScope.launch {
            app.repository.deleteAudio(entryId)
            _uiState.update { it.copy(audioPath = null) }
        }
    }

    fun deleteTranscript() {
        viewModelScope.launch {
            app.repository.deleteTranscript(entryId)
            _uiState.update { it.copy(transcript = "") }
        }
    }

    fun deleteDiaryText() {
        viewModelScope.launch {
            app.repository.deleteDiaryText(entryId)
            _uiState.update { it.copy(diaryText = "") }
        }
    }

    fun deleteEntry() {
        viewModelScope.launch {
            app.repository.deleteEntry(entryId)
            emitEvent(DetailEvent.NavigateBack)
            emitEvent(DetailEvent.Toast("记事已删除"))
        }
    }

    fun exportAudio() {
        viewModelScope.launch {
            val path = _uiState.value.audioPath
            if (path == null) {
                emitEvent(DetailEvent.Toast("无录音"))
                return@launch
            }
            val file = File(path)
            val name = ExportFileNames.build(
                ExportFileNames.TYPE_RECORD,
                ExportFileNames.audioExtension(file)
            )
            FileExporter.exportAudio(app, file, name)
                .onSuccess { emitEvent(DetailEvent.Toast("已保存到 Download/$it")) }
                .onFailure { emitEvent(DetailEvent.Toast(it.message ?: "下载录音失败")) }
        }
    }

    fun exportTranscript() {
        viewModelScope.launch {
            val text = _uiState.value.transcript
            if (text.isBlank()) {
                emitEvent(DetailEvent.Toast("识别稿为空"))
                return@launch
            }
            val name = ExportFileNames.build(ExportFileNames.TYPE_FIRST, "txt")
            FileExporter.exportText(app, text, name)
                .onSuccess { emitEvent(DetailEvent.Toast("已保存到 Download/$it")) }
                .onFailure { emitEvent(DetailEvent.Toast(it.message ?: "下载识别稿失败")) }
        }
    }

    fun exportDiaryText() {
        viewModelScope.launch {
            val text = _uiState.value.diaryText
            if (text.isBlank()) {
                emitEvent(DetailEvent.Toast("润色稿为空"))
                return@launch
            }
            val name = ExportFileNames.build(ExportFileNames.TYPE_FINAL, "txt")
            FileExporter.exportText(app, text, name)
                .onSuccess { emitEvent(DetailEvent.Toast("已保存到 Download/$it")) }
                .onFailure { emitEvent(DetailEvent.Toast(it.message ?: "下载润色稿失败")) }
        }
    }

    companion object {
        fun factory(app: VoiceNotesApp, entryId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DiaryDetailViewModel(app, entryId) as T
            }
        }
    }
}
