package com.dafei.voicenotes.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dafei.voicenotes.VoiceNotesApp
import com.dafei.voicenotes.asr.AsrModelInfo
import com.dafei.voicenotes.asr.AsrModels
import com.dafei.voicenotes.data.AppPrefs
import com.dafei.voicenotes.llm.LlmProfile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val models: List<AsrModelInfo> = AsrModels.ALL,
    val downloadedIds: Set<String> = emptySet(),
    val downloadedModels: List<AsrModelInfo> = emptyList(),
    val selectedModel: AsrModelInfo = AsrModels.ALL.first { it.id == AppPrefs.DEFAULT_ASR_MODEL },
    val downloadProgress: Map<String, Float> = emptyMap(),
    val modelDownloadTimes: Map<String, Long> = emptyMap(),
    val llmProfiles: List<LlmProfile> = emptyList(),
    val activeLlmProfileId: String = "",
    val llmEditorOpen: Boolean = false,
    val llmEditorIsNew: Boolean = false,
    val editingLlmProfileId: String = "",
    val llmProfileName: String = "",
    val llmBaseUrl: String = AppPrefs.DEFAULT_LLM_BASE_URL,
    val llmApiKey: String = "",
    val llmModel: String = AppPrefs.DEFAULT_LLM_MODEL,
    val llmSystemPrompt: String = AppPrefs.DEFAULT_LLM_SYSTEM_PROMPT,
    val keepScreenOnWhileRecording: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

class SettingsViewModel(private val app: VoiceNotesApp) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toasts = _toasts.asSharedFlow()

    init {
        refresh()
    }

    private fun refresh() {
        val downloaded = AsrModels.ALL.filter { app.modelDownloader.isDownloaded(it) }
        val selectedId = AppPrefs.getSelectedAsrModel(app)
        val selected = AsrModels.find(selectedId)
            ?: downloaded.firstOrNull()
            ?: AsrModels.ALL.first { it.id == AppPrefs.DEFAULT_ASR_MODEL }
        val downloadTimes = AsrModels.ALL.mapNotNull { model ->
            app.modelDownloader.downloadedAt(model)?.let { model.id to it }
        }.toMap()
        val profiles = AppPrefs.getLlmProfiles(app)
        val activeId = AppPrefs.getActiveLlmProfileId(app)
        val previous = _uiState.value
        _uiState.update {
            val base = it.copy(
                downloadedIds = downloaded.map { m -> m.id }.toSet(),
                downloadedModels = downloaded,
                modelDownloadTimes = downloadTimes,
                selectedModel = selected,
                llmProfiles = profiles,
                activeLlmProfileId = activeId,
                keepScreenOnWhileRecording = AppPrefs.getKeepScreenOnWhileRecording(app)
            )
            if (previous.llmEditorOpen) {
                base.copy(
                    llmEditorOpen = true,
                    llmEditorIsNew = previous.llmEditorIsNew,
                    editingLlmProfileId = previous.editingLlmProfileId,
                    llmProfileName = previous.llmProfileName,
                    llmBaseUrl = previous.llmBaseUrl,
                    llmApiKey = previous.llmApiKey,
                    llmModel = previous.llmModel,
                    llmSystemPrompt = previous.llmSystemPrompt
                )
            } else {
                base
            }
        }
    }

    fun selectModel(modelId: String) {
        val model = AsrModels.find(modelId) ?: return
        if (!app.modelDownloader.isDownloaded(model)) {
            _uiState.update { it.copy(error = "请先下载该模型") }
            return
        }
        AppPrefs.setSelectedAsrModel(app, modelId)
        refresh()
    }

    fun downloadModel(modelId: String, useMirror: Boolean = false) {
        val model = AsrModels.find(modelId) ?: return
        val sourceLabel = if (useMirror) "镜像" else ""
        _uiState.update {
            it.copy(
                error = null,
                message = if (sourceLabel.isEmpty()) {
                    "开始下载 ${model.displayName}"
                } else {
                    "开始从${sourceLabel}下载 ${model.displayName}"
                }
            )
        }
        viewModelScope.launch {
            app.modelDownloader.download(model, useMirror) { progress ->
                _uiState.update { state ->
                    state.copy(downloadProgress = state.downloadProgress + (modelId to progress))
                }
            }.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        downloadProgress = state.downloadProgress - modelId,
                        message = "${model.displayName} 下载完成"
                    )
                }
                if (AsrModels.find(AppPrefs.getSelectedAsrModel(app))?.let { !app.modelDownloader.isDownloaded(it) } != false) {
                    AppPrefs.setSelectedAsrModel(app, modelId)
                }
                refresh()
                _toasts.emit("${model.displayName} 下载完成")
            }.onFailure { e ->
                val message = downloadFailureMessage(e)
                _uiState.update {
                    it.copy(
                        downloadProgress = it.downloadProgress - modelId,
                        error = message
                    )
                }
                _toasts.emit(message)
            }
        }
    }

    fun deleteModel(modelId: String) {
        val model = AsrModels.find(modelId) ?: return
        app.modelDownloader.delete(model)
        if (AppPrefs.getSelectedAsrModel(app) == modelId) {
            val fallback = AsrModels.ALL.firstOrNull { app.modelDownloader.isDownloaded(it) }?.id
                ?: AppPrefs.DEFAULT_ASR_MODEL
            AppPrefs.setSelectedAsrModel(app, fallback)
        }
        refresh()
    }

    fun setActiveLlmProfile(profileId: String) {
        if (_uiState.value.llmProfiles.none { it.id == profileId }) return
        AppPrefs.setActiveLlmProfileId(app, profileId)
        _uiState.update { it.copy(activeLlmProfileId = profileId, message = null, error = null) }
    }

    fun openAddLlmProfileDialog() {
        val count = _uiState.value.llmProfiles.size
        val newProfile = LlmProfile.createDefault(name = "配置 ${count + 1}")
        _uiState.update {
            it.copy(
                llmEditorOpen = true,
                llmEditorIsNew = true,
                editingLlmProfileId = newProfile.id,
                llmProfileName = newProfile.name,
                llmBaseUrl = newProfile.baseUrl,
                llmApiKey = newProfile.apiKey,
                llmModel = newProfile.model,
                llmSystemPrompt = newProfile.systemPrompt,
                message = null,
                error = null
            )
        }
    }

    fun openEditLlmProfileDialog(profileId: String) {
        val profile = _uiState.value.llmProfiles.find { it.id == profileId } ?: return
        _uiState.update {
            it.copy(
                llmEditorOpen = true,
                llmEditorIsNew = false,
                editingLlmProfileId = profile.id,
                llmProfileName = profile.name,
                llmBaseUrl = profile.baseUrl,
                llmApiKey = profile.apiKey,
                llmModel = profile.model,
                llmSystemPrompt = profile.systemPrompt,
                message = null,
                error = null
            )
        }
    }

    fun closeLlmProfileEditor() {
        _uiState.update {
            it.copy(
                llmEditorOpen = false,
                llmEditorIsNew = false,
                editingLlmProfileId = "",
                message = null,
                error = null
            )
        }
    }

    fun deleteLlmProfile(profileId: String) {
        val state = _uiState.value
        if (state.llmProfiles.size <= 1) {
            _uiState.update { it.copy(error = "至少保留一组 LLM 配置") }
            return
        }
        val profiles = state.llmProfiles.filter { it.id != profileId }
        AppPrefs.saveLlmProfiles(app, profiles)
        refresh()
        _uiState.update { it.copy(message = "已删除配置", error = null) }
    }

    fun updateLlmProfileName(value: String) {
        _uiState.update { it.copy(llmProfileName = value) }
    }

    fun updateLlmBaseUrl(value: String) {
        _uiState.update { it.copy(llmBaseUrl = value) }
    }

    fun updateLlmApiKey(value: String) {
        _uiState.update { it.copy(llmApiKey = value) }
    }

    fun updateLlmModel(value: String) {
        _uiState.update { it.copy(llmModel = value) }
    }

    fun updateLlmSystemPrompt(value: String) {
        _uiState.update { it.copy(llmSystemPrompt = value) }
    }

    fun setKeepScreenOnWhileRecording(enabled: Boolean) {
        AppPrefs.setKeepScreenOnWhileRecording(app, enabled)
        _uiState.update { it.copy(keepScreenOnWhileRecording = enabled) }
    }

    fun saveLlmSettings() {
        val state = _uiState.value
        val editingId = state.editingLlmProfileId
        if (editingId.isBlank()) {
            _uiState.update { it.copy(error = "配置无效") }
            return
        }
        val updated = draftLlmProfile(state)
        val profiles = if (state.llmEditorIsNew) {
            state.llmProfiles + updated
        } else {
            state.llmProfiles.map { if (it.id == editingId) updated else it }
        }
        AppPrefs.saveLlmProfiles(app, profiles)
        val message = if (state.llmEditorIsNew) "已添加「${updated.name}」" else "「${updated.name}」已保存"
        closeLlmProfileEditor()
        refresh()
        _uiState.update { it.copy(message = message, error = null) }
    }

    fun testLlm() {
        val state = _uiState.value
        viewModelScope.launch {
            app.llmClient.testConnection(draftLlmProfile(state).toConfig())
                .onSuccess { _toasts.emit("连接成功") }
                .onFailure { _toasts.emit(it.message ?: "连接失败") }
        }
    }

    private fun draftLlmProfile(state: SettingsUiState): LlmProfile = LlmProfile(
        id = state.editingLlmProfileId,
        name = state.llmProfileName.trim().ifBlank { "未命名" },
        baseUrl = state.llmBaseUrl.trim().ifBlank { AppPrefs.DEFAULT_LLM_BASE_URL },
        apiKey = state.llmApiKey.trim(),
        model = state.llmModel.trim().ifBlank { AppPrefs.DEFAULT_LLM_MODEL },
        systemPrompt = state.llmSystemPrompt.ifBlank { AppPrefs.DEFAULT_LLM_SYSTEM_PROMPT }
    )

    private fun downloadFailureMessage(e: Throwable): String {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: "下载失败"
        return "$detail。请检查网络连接、Wi‑Fi/移动数据、代理或 VPN 设置后重试。"
    }

    companion object {
        fun factory(app: VoiceNotesApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(app) as T
            }
        }
    }
}
