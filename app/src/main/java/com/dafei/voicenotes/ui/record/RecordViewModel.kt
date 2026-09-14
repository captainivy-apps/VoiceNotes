package com.dafei.voicenotes.ui.record

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dafei.voicenotes.VoiceNotesApp
import com.dafei.voicenotes.asr.AsrPhase
import com.dafei.voicenotes.asr.AsrProgressMessages
import com.dafei.voicenotes.audio.AudioImporter
import com.dafei.voicenotes.data.AppPrefs
import com.dafei.voicenotes.llm.LlmPhase
import com.dafei.voicenotes.llm.LlmProfile
import com.dafei.voicenotes.llm.LlmProgressMessages
import com.dafei.voicenotes.llm.llmConfigFromPrefs
import com.dafei.voicenotes.ui.common.AsrElapsedTimer
import com.dafei.voicenotes.ui.common.LlmProgressAnimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class RecordUiState(
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
    val audioPath: String? = null,
    val durationMs: Long = 0,
    val transcript: String? = null,
    val entryId: Long? = null,
    val awaitingAsrPrompt: Boolean = false,
    val llmProfiles: List<LlmProfile> = emptyList(),
    val selectedLlmProfileId: String = "",
    val statusMessage: String = "",
    val asrProgress: Float? = null,
    val asrElapsedMs: Long = 0L,
    val llmProgress: Float? = null,
    val llmElapsedMs: Long = 0L,
    val error: String? = null
)

class RecordViewModel(
    private val app: VoiceNotesApp
) : ViewModel() {
    private val _uiState = MutableStateFlow(RecordUiState())
    val uiState = _uiState.asStateFlow()
    private val asrTimer = AsrElapsedTimer(viewModelScope)
    private val llmAnimator = LlmProgressAnimator(viewModelScope)

    init {
        refreshLlmProfiles()
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

    fun startRecording(path: String) {
        _uiState.update {
            it.copy(
                isRecording = true,
                audioPath = path,
                transcript = null,
                entryId = null,
                awaitingAsrPrompt = false,
                error = null,
                durationMs = 0,
                statusMessage = ""
            )
        }
    }

    fun onRecordingFailed(message: String) {
        _uiState.update {
            it.copy(
                isRecording = false,
                audioPath = null,
                error = message,
                statusMessage = ""
            )
        }
    }

    fun stopRecording(durationMs: Long) {
        val path = _uiState.value.audioPath ?: return
        _uiState.update {
            it.copy(
                isRecording = false,
                isProcessing = true,
                durationMs = durationMs,
                statusMessage = "正在保存录音…"
            )
        }
        viewModelScope.launch {
            runCatching {
                finalizeAudio(path, durationMs)
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        error = e.message ?: "保存录音失败"
                    )
                }
            }
        }
    }

    fun importAudio(context: Context, uri: Uri) {
        _uiState.update {
            it.copy(
                isRecording = false,
                isProcessing = true,
                statusMessage = "正在导入录音…",
                audioPath = null,
                transcript = null,
                entryId = null,
                awaitingAsrPrompt = false,
                durationMs = 0,
                error = null
            )
        }
        viewModelScope.launch {
            runCatching {
                val destFile = app.repository.newRecordingFile()
                val durationMs = withContext(Dispatchers.IO) {
                    try {
                        AudioImporter.importToWav(context, uri, destFile)
                    } catch (oom: OutOfMemoryError) {
                        destFile.delete()
                        throw Exception("音频过大，内存不足", oom)
                    }
                }
                finalizeAudio(destFile.absolutePath, durationMs)
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        error = e.message ?: "导入录音失败"
                    )
                }
            }
        }
    }

    private suspend fun finalizeAudio(path: String, durationMs: Long) {
        val id = app.repository.createDraft(path, durationMs).id
        _uiState.update {
            it.copy(
                isProcessing = false,
                audioPath = path,
                durationMs = durationMs,
                entryId = id,
                awaitingAsrPrompt = true,
                statusMessage = ""
            )
        }
    }

    fun dismissAsrPrompt() {
        _uiState.update { it.copy(awaitingAsrPrompt = false) }
    }

    fun startTranscription() {
        val path = _uiState.value.audioPath ?: return
        val entryId = _uiState.value.entryId ?: return
        _uiState.update {
            it.copy(
                awaitingAsrPrompt = false,
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
        viewModelScope.launch {
            runCatching {
                val modelId = AppPrefs.getSelectedAsrModel(app)
                app.whisperEngine.transcribe(modelId, File(path)) { progress, phase ->
                    _uiState.update {
                        it.copy(
                            asrProgress = progress,
                            statusMessage = AsrProgressMessages.format(phase, progress)
                        )
                    }
                }.getOrThrow()
            }.onSuccess { transcript ->
                asrTimer.stop()
                app.repository.saveTranscript(entryId, transcript)
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        asrProgress = null,
                        asrElapsedMs = 0L,
                        transcript = transcript,
                        statusMessage = ""
                    )
                }
            }.onFailure { e ->
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
    }

    fun updateTranscript(text: String) {
        _uiState.update { it.copy(transcript = text) }
    }

    fun saveDirect(onSaved: (Long) -> Unit) {
        val state = _uiState.value
        val transcript = state.transcript ?: return
        val entryId = state.entryId ?: return
        viewModelScope.launch {
            app.repository.saveTranscript(entryId, transcript)
            app.repository.saveDiaryText(entryId, transcript)
            onSaved(entryId)
            reset()
        }
    }

    fun saveWithLlm(onSaved: (Long) -> Unit) {
        val state = _uiState.value
        val transcript = state.transcript ?: return
        val entryId = state.entryId ?: return
        beginLlmProcessing()
        val profileId = _uiState.value.selectedLlmProfileId
        viewModelScope.launch {
            val result = app.llmClient.polishTranscript(
                llmConfigFromPrefs(app, profileId),
                transcript
            ) { progress, phase ->
                updateLlmProgress(progress, phase)
            }
            endLlmProcessing()
            result.onSuccess { diary ->
                app.repository.saveTranscript(entryId, transcript)
                app.repository.saveDiaryText(entryId, diary)
                onSaved(entryId)
                reset()
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        llmProgress = null,
                        llmElapsedMs = 0L,
                        statusMessage = "",
                        error = e.message ?: "LLM 处理失败"
                    )
                }
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

    fun reset() {
        _uiState.value = RecordUiState()
    }

    companion object {
        fun factory(app: VoiceNotesApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return RecordViewModel(app) as T
            }
        }
    }
}
