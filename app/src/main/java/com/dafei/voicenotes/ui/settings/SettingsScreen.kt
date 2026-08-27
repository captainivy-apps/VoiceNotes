package com.dafei.voicenotes.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dafei.voicenotes.VoiceNotesApp
import com.dafei.voicenotes.asr.AsrModelInfo
import com.dafei.voicenotes.data.AppPrefs
import com.dafei.voicenotes.llm.LlmProfile
import com.dafei.voicenotes.ui.common.formatDateTime
import com.dafei.voicenotes.ui.common.showToast
import kotlinx.coroutines.launch

private enum class SettingsTab(val label: String) {
    Asr("录制"),
    Llm("LLM 配置"),
    Help("帮助")
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(app: VoiceNotesApp) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
    val uiState by vm.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(SettingsTab.Asr) }
    var showKeepScreenOnWarning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        PrimaryTabRow(
            selectedTabIndex = selectedTab.ordinal,
            modifier = Modifier.padding(top = 12.dp)
        ) {
            SettingsTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.label) }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (selectedTab) {
                SettingsTab.Asr -> AsrSettingsTab(
                    uiState = uiState,
                    onKeepScreenOnChange = { enabled ->
                        if (enabled) {
                            showKeepScreenOnWarning = true
                        } else {
                            vm.setKeepScreenOnWhileRecording(false)
                        }
                    },
                    onDownload = { modelId, useMirror ->
                        scope.launch {
                            vm.downloadModel(modelId, useMirror)
                            val state = vm.uiState.value
                            state.message?.let { showToast(context, it) }
                            state.error?.let { showToast(context, it) }
                        }
                    },
                    onDelete = vm::deleteModel,
                    onSelect = vm::selectModel
                )

                SettingsTab.Llm -> LlmSettingsTab(
                    uiState = uiState,
                    onAddProfile = vm::openAddLlmProfileDialog,
                    onEditProfile = vm::openEditLlmProfileDialog,
                    onCloseEditor = vm::closeLlmProfileEditor,
                    onDeleteProfile = vm::deleteLlmProfile,
                    onSetActiveProfile = vm::setActiveLlmProfile,
                    onProfileNameChange = vm::updateLlmProfileName,
                    onBaseUrlChange = vm::updateLlmBaseUrl,
                    onApiKeyChange = vm::updateLlmApiKey,
                    onModelChange = vm::updateLlmModel,
                    onSystemPromptChange = vm::updateLlmSystemPrompt,
                    onTestConnection = {
                        scope.launch {
                            vm.testLlm().onSuccess {
                                showToast(context, "连接成功")
                            }.onFailure {
                                showToast(context, it.message ?: "连接失败")
                            }
                        }
                    },
                    onSave = vm::saveLlmSettings
                )

                SettingsTab.Help -> HelpTabContent()
            }
        }
    }

    if (showKeepScreenOnWarning) {
        AlertDialog(
            onDismissRequest = { showKeepScreenOnWarning = false },
            title = { Text("开启屏幕常亮？") },
            text = { Text("录音期间保持屏幕常亮会增加手机耗电。是否继续开启？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.setKeepScreenOnWhileRecording(true)
                        showKeepScreenOnWarning = false
                    }
                ) { Text("开启") }
            },
            dismissButton = {
                TextButton(onClick = { showKeepScreenOnWarning = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AsrSettingsTab(
    uiState: SettingsUiState,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onDownload: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    Text("录制", style = MaterialTheme.typography.titleMedium)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("录音时保持屏幕常亮", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "仅在正在录音时生效，结束录音后自动恢复系统默认行为。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = uiState.keepScreenOnWhileRecording,
            onCheckedChange = onKeepScreenOnChange
        )
    }
    Text("ASR 模型", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "在已下载的模型中点击单选按钮切换当前使用的模型",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = "中文识别稿的标点与模型大小相关，建议使用 Small 及以上；完整排版可依赖 LLM 润色得到润色稿。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    uiState.models.forEach { model ->
        ModelCard(
            model = model,
            downloaded = uiState.downloadedIds.contains(model.id),
            selected = uiState.selectedModel.id == model.id,
            recommended = model.id == AppPrefs.DEFAULT_ASR_MODEL,
            downloadedAt = uiState.modelDownloadTimes[model.id],
            progress = uiState.downloadProgress[model.id],
            hasMirrorDownload = model.mirrorDownloadUrl != null,
            onDownload = { onDownload(model.id, false) },
            onMirrorDownload = { onDownload(model.id, true) },
            onDelete = { onDelete(model.id) },
            onSelect = { onSelect(model.id) }
        )
    }

    uiState.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun LlmSettingsTab(
    uiState: SettingsUiState,
    onAddProfile: () -> Unit,
    onEditProfile: (String) -> Unit,
    onCloseEditor: () -> Unit,
    onDeleteProfile: (String) -> Unit,
    onSetActiveProfile: (String) -> Unit,
    onProfileNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit
) {
    var profileToDelete by remember { mutableStateOf<LlmProfile?>(null) }

    Text("LLM 配置", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "可保存多组配置（如 OpenAI、Ollama、DeepSeek）。单选设为默认；通过弹窗添加或编辑。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onAddProfile) { Text("添加配置") }
    }
    uiState.llmProfiles.forEach { profile ->
        LlmProfileCard(
            profile = profile,
            active = uiState.activeLlmProfileId == profile.id,
            canDelete = uiState.llmProfiles.size > 1,
            onSetActive = { onSetActiveProfile(profile.id) },
            onEdit = { onEditProfile(profile.id) },
            onDelete = { profileToDelete = profile }
        )
    }

    if (uiState.llmEditorOpen) {
        LlmProfileEditorDialog(
            isNew = uiState.llmEditorIsNew,
            profileName = uiState.llmProfileName,
            baseUrl = uiState.llmBaseUrl,
            apiKey = uiState.llmApiKey,
            model = uiState.llmModel,
            systemPrompt = uiState.llmSystemPrompt,
            onDismiss = onCloseEditor,
            onProfileNameChange = onProfileNameChange,
            onBaseUrlChange = onBaseUrlChange,
            onApiKeyChange = onApiKeyChange,
            onModelChange = onModelChange,
            onSystemPromptChange = onSystemPromptChange,
            onTestConnection = onTestConnection,
            onSave = onSave
        )
    }

    uiState.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    profileToDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("删除配置？") },
            text = { Text("确定删除「${profile.name}」？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProfile(profile.id)
                        profileToDelete = null
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun LlmProfileEditorDialog(
    isNew: Boolean,
    profileName: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    systemPrompt: String,
    onDismiss: () -> Unit,
    onProfileNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .heightIn(max = screenHeight * 0.9f)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isNew) "添加配置" else "编辑配置",
                    style = MaterialTheme.typography.titleLarge
                )
                OutlinedTextField(
                    value = profileName,
                    onValueChange = onProfileNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("配置名称") },
                    placeholder = { Text("例如：OpenAI、本地 Ollama") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    placeholder = { Text("http://192.168.1.100:11434 或 https://api.openai.com") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key（本地 LLM 可留空）") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = onModelChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = onSystemPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("System Prompt") },
                    minLines = 3
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    OutlinedButton(onClick = onTestConnection) { Text("测试连接") }
                    Button(onClick = onSave) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun LlmProfileCard(
    profile: LlmProfile,
    active: Boolean,
    canDelete: Boolean,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (active) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (active) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "默认配置（录制/详情页初始选中）",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(profile.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        profile.model,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        profile.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RadioButton(selected = active, onClick = onSetActive)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("编辑") }
                if (canDelete) {
                    OutlinedButton(onClick = onDelete) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun HelpTabContent() {
    Text("语音记事是什么", style = MaterialTheme.typography.titleMedium)
    Text(
        "语音记事是一款本地优先的语音记录工具。你说话，应用会先保存录音，再通过 ASR 生成识别稿，并可用 LLM 整理成更通顺的润色稿。",
        style = MaterialTheme.typography.bodyMedium
    )

    Text("功能特点", style = MaterialTheme.typography.titleMedium)
    Text("• 录音、识别稿、润色稿三段式流程", style = MaterialTheme.typography.bodyMedium)
    Text("• 记事列表按时间管理，可多选删除", style = MaterialTheme.typography.bodyMedium)
    Text("• 详情页支持试听、编辑、复制、下载与上一篇/下一篇切换", style = MaterialTheme.typography.bodyMedium)
    Text("• ASR 模型可下载到本地后离线识别", style = MaterialTheme.typography.bodyMedium)
    Text("• 可配置 OpenAI 兼容接口进行文本润色", style = MaterialTheme.typography.bodyMedium)

    Text("快速上手", style = MaterialTheme.typography.titleMedium)
    Text("1. 在“录制”页开始录音或上传音频文件。", style = MaterialTheme.typography.bodyMedium)
    Text("2. 录音保存后点击“开始语音识别”。", style = MaterialTheme.typography.bodyMedium)
    Text("3. 可直接保存为润色稿，或先用 LLM 加工后保存。", style = MaterialTheme.typography.bodyMedium)
    Text("4. 到“记事”页查看历史记录，点击可进入详情编辑和导出。", style = MaterialTheme.typography.bodyMedium)

    Text("设置建议", style = MaterialTheme.typography.titleMedium)
    Text("• 首次使用先下载一个 ASR 模型并设为当前模型。", style = MaterialTheme.typography.bodyMedium)
    Text("• 可添加多组 LLM 配置（弹窗编辑）并设默认；录制与详情页可临时切换。", style = MaterialTheme.typography.bodyMedium)
    Text("• 填写 Base URL / API Key / Model Name 后先测试连接再保存。", style = MaterialTheme.typography.bodyMedium)

    Text("常见问题", style = MaterialTheme.typography.titleMedium)
    Text("• 识别失败：请检查模型是否下载完成，或重试录音文件。", style = MaterialTheme.typography.bodyMedium)
    Text("• LLM 连接失败：确认 Base URL、API Key 和网络/代理设置。", style = MaterialTheme.typography.bodyMedium)
    Text("• 找不到文件：导出内容默认保存在系统 Download 目录。", style = MaterialTheme.typography.bodyMedium)

    Text(
        "如果你只想快速记录：录音 -> 识别 -> 直接保存为润色稿 就可以完成一次记事。",
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ModelCard(
    model: AsrModelInfo,
    downloaded: Boolean,
    selected: Boolean,
    recommended: Boolean,
    downloadedAt: Long?,
    progress: Float?,
    hasMirrorDownload: Boolean,
    onDownload: () -> Unit,
    onMirrorDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "当前使用的模型",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                        if (recommended) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = "推荐",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                    Text(
                        "${model.sizeLabel} · ${if (downloaded) "已下载" else "未下载"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (downloadedAt != null) {
                        Text(
                            text = "下载于 ${formatDateTime(downloadedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                RadioButton(selected = selected, onClick = onSelect, enabled = downloaded)
            }
            progress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (downloaded) {
                    OutlinedButton(onClick = onDelete) { Text("删除") }
                } else if (progress == null) {
                    if (hasMirrorDownload) {
                        Button(onClick = onDownload) { Text("下载") }
                        OutlinedButton(onClick = onMirrorDownload) { Text("镜像下载") }
                    } else {
                        Button(onClick = onDownload) { Text("下载") }
                    }
                }
            }
        }
    }
}
