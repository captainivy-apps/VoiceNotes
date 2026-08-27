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
import kotlinx.coroutines.flow.MutableStateFlow
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

class DiaryDetailViewModel(
    private val app: VoiceNotesApp,
    private val entryId: Long
) : ViewModel() {
    private val _uiState = MutableStateFlow(DiaryDetailUiState(entryId = entryId))
    val uiState = _uiState.asStateFlow()
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

    suspend fun runAsr() {
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
            }
            .onFailure { e ->
                asrTimer.stop()
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        asrProgress = null,
                        asrElapsedMs = 0L,
                        statusMessage = "",
                        error = when (e) {
                            is OutOfMemoryError -> "音频过大，内存不足"
                            else -> e.message ?: "识别失败"
                        }
                    )
                }
            }
    }

    suspend fun copyTranscriptToDiary() {
        val text = _uiState.value.transcript
        app.repository.saveDiaryText(entryId, text)
        _uiState.update { it.copy(diaryText = text) }
    }

    suspend fun runLlm() {
        val transcript = _uiState.value.transcript
        beginLlmProcessing()
        val profileId = _uiState.value.selectedLlmProfileId
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
            }
            .onFailure { e ->
                endLlmProcessing()
                _uiState.update {
                    it.copy(error = e.message ?: "LLM 处理失败")
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

    suspend fun deleteAudio() {
        app.repository.deleteAudio(entryId)
        _uiState.update { it.copy(audioPath = null) }
    }

    suspend fun deleteTranscript() {
        app.repository.deleteTranscript(entryId)
        _uiState.update { it.copy(transcript = "") }
    }

    suspend fun deleteDiaryText() {
        app.repository.deleteDiaryText(entryId)
        _uiState.update { it.copy(diaryText = "") }
    }

    suspend fun deleteEntry() {
        app.repository.deleteEntry(entryId)
    }

    suspend fun exportAudio(): Result<String> {
        val path = _uiState.value.audioPath ?: return Result.failure(IllegalStateException("无录音"))
        val file = File(path)
        val name = ExportFileNames.build(
            ExportFileNames.TYPE_RECORD,
            ExportFileNames.audioExtension(file)
        )
        return FileExporter.exportAudio(app, file, name)
    }

    suspend fun exportTranscript(): Result<String> {
        val text = _uiState.value.transcript
        if (text.isBlank()) return Result.failure(IllegalStateException("识别稿为空"))
        val name = ExportFileNames.build(ExportFileNames.TYPE_FIRST, "txt")
        return FileExporter.exportText(app, text, name)
    }

    suspend fun exportDiaryText(): Result<String> {
        val text = _uiState.value.diaryText
        if (text.isBlank()) return Result.failure(IllegalStateException("润色稿为空"))
        val name = ExportFileNames.build(ExportFileNames.TYPE_FINAL, "txt")
        return FileExporter.exportText(app, text, name)
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
