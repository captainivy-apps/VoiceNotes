package com.dafei.voicenotes.ui.detail

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dafei.voicenotes.VoiceNotesApp
import com.dafei.voicenotes.ui.common.AsrProcessingOverlay
import com.dafei.voicenotes.audio.WavReader
import com.dafei.voicenotes.audio.WavRecorder
import com.dafei.voicenotes.llm.LlmProfile
import com.dafei.voicenotes.ui.common.DiaryTextEditor
import com.dafei.voicenotes.ui.common.LlmProfileSelector
import com.dafei.voicenotes.ui.common.WAVEFORM_BARS_PER_WINDOW
import com.dafei.voicenotes.ui.common.WAVEFORM_WINDOW_MS
import com.dafei.voicenotes.ui.common.WaveformView
import com.dafei.voicenotes.ui.common.audioFileSize
import com.dafei.voicenotes.ui.common.diaryTotalSize
import com.dafei.voicenotes.ui.common.copyToClipboard
import com.dafei.voicenotes.ui.common.formatDateTime
import com.dafei.voicenotes.ui.common.formatDuration
import com.dafei.voicenotes.ui.common.formatFileSize
import com.dafei.voicenotes.ui.common.showToast
import com.dafei.voicenotes.ui.common.textByteSize
import com.dafei.voicenotes.ui.common.waveformPlayheadFraction
import com.dafei.voicenotes.ui.common.waveformWindowSlice
import com.dafei.voicenotes.ui.common.waveformWindowStartMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private enum class DetailTab(val label: String) {
    Audio("录音"),
    Transcript("识别稿"),
    Polished("润色稿")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryDetailScreen(
    app: VoiceNotesApp,
    entryId: Long,
    onBack: () -> Unit,
    onNavigateTo: (Long) -> Unit
) {
    val context = LocalContext.current
    val vm: DiaryDetailViewModel = viewModel(
        factory = DiaryDetailViewModel.factory(app, entryId)
    )
    val uiState by vm.uiState.collectAsState()

    LaunchedEffect(Unit) {
        vm.refreshLlmProfiles()
    }
    var confirmAction by remember { mutableStateOf<ConfirmAction?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var waveformSamples by remember { mutableStateOf<List<Float>>(emptyList()) }
    var audioDurationMs by remember { mutableStateOf(0L) }
    var playbackPositionMs by remember { mutableStateOf(0L) }
    var selectedTab by remember { mutableStateOf(DetailTab.Audio) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is DetailEvent.Toast -> showToast(context, event.message)
                DetailEvent.SwitchToPolished -> selectedTab = DetailTab.Polished
                DetailEvent.NavigateBack -> onBack()
            }
        }
    }

    fun stopPlayback(showToastOnStop: Boolean = false, resetPosition: Boolean = true) {
        val wasPlaying = isPlaying
        mediaPlayer?.apply {
            try {
                if (this.isPlaying) stop()
            } catch (_: Exception) {
            }
            release()
        }
        mediaPlayer = null
        isPlaying = false
        if (resetPosition) playbackPositionMs = 0L
        if (showToastOnStop && wasPlaying) {
            showToast(context, "已停止播放")
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopPlayback() }
    }

    LaunchedEffect(selectedTab) {
        stopPlayback()
    }

    LaunchedEffect(uiState.audioPath) {
        playbackPositionMs = 0L
        val result = withContext(Dispatchers.IO) {
            val path = uiState.audioPath ?: return@withContext null
            try {
                WavReader.readWaveformPeaks(
                    File(path),
                    WAVEFORM_WINDOW_MS,
                    WAVEFORM_BARS_PER_WINDOW
                )
            } catch (_: Exception) {
                null
            }
        }
        if (result != null) {
            waveformSamples = result.first
            audioDurationMs = result.second
        } else {
            waveformSamples = emptyList()
            audioDurationMs = 0L
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaPlayer?.takeIf { it.isPlaying }?.let {
                playbackPositionMs = it.currentPosition.toLong()
            }
            delay(50)
        }
    }

    val audioSize = audioFileSize(uiState.audioPath)
    val transcriptSize = textByteSize(uiState.transcript)
    val polishedSize = textByteSize(uiState.diaryText)
    val totalSize = diaryTotalSize(uiState.audioPath, uiState.transcript, uiState.diaryText)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记事详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TooltipIconButton(
                        tooltip = "删除整篇记事",
                        onClick = { confirmAction = ConfirmAction.DeleteEntry }
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = "删除整篇记事"
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatDateTime(uiState.createdAt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (uiState.updatedAt > uiState.createdAt) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "更新于 ${formatDateTime(uiState.updatedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "整篇记事大小：${formatFileSize(totalSize)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TooltipIconButton(
                        tooltip = "上一篇",
                        onClick = {
                            uiState.prevId?.let {
                                showToast(context, "上一篇")
                                onNavigateTo(it)
                            }
                        },
                        enabled = uiState.prevId != null
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.NavigateBefore,
                            contentDescription = "上一篇"
                        )
                    }
                    TooltipIconButton(
                        tooltip = "下一篇",
                        onClick = {
                            uiState.nextId?.let {
                                showToast(context, "下一篇")
                                onNavigateTo(it)
                            }
                        },
                        enabled = uiState.nextId != null
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.NavigateNext,
                            contentDescription = "下一篇"
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (uiState.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                DetailTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label) }
                    )
                }
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            when (selectedTab) {
                DetailTab.Audio -> AudioTabContent(
                    hasAudio = !uiState.audioPath.isNullOrBlank(),
                    isProcessing = uiState.isProcessing,
                    asrProgress = uiState.asrProgress,
                    asrElapsedMs = uiState.asrElapsedMs,
                    statusMessage = uiState.statusMessage,
                    isPlaying = isPlaying,
                    waveformSamples = waveformSamples,
                    durationMs = audioDurationMs,
                    playbackPositionMs = playbackPositionMs,
                    itemSize = audioSize,
                    onTogglePlayback = {
                        if (isPlaying) {
                            stopPlayback(showToastOnStop = true)
                        } else {
                            val path = uiState.audioPath
                            if (path.isNullOrBlank()) {
                                showToast(context, "录音已删除")
                                return@AudioTabContent
                            }
                            mediaPlayer?.release()
                            try {
                                mediaPlayer = MediaPlayer().apply {
                                    setDataSource(path)
                                    prepare()
                                    setOnCompletionListener {
                                        stopPlayback()
                                        showToast(context, "播放完毕")
                                    }
                                    start()
                                }
                                mediaPlayer?.let { player ->
                                    audioDurationMs = player.duration.toLong().coerceAtLeast(audioDurationMs)
                                }
                                isPlaying = true
                                showToast(context, "正在播放录音")
                            } catch (e: Exception) {
                                mediaPlayer?.release()
                                mediaPlayer = null
                                showToast(context, "播放失败")
                            }
                        }
                    },
                    onDelete = { confirmAction = ConfirmAction.DeleteAudio },
                    onDownload = {
                        showToast(context, "正在下载录音…")
                        vm.exportAudio()
                    },
                    onRunAsr = {
                        showToast(context, "正在识别语音…")
                        vm.runAsr()
                    }
                )

                DetailTab.Transcript -> TranscriptTabContent(
                    transcript = uiState.transcript,
                    onTranscriptChange = vm::updateTranscript,
                    isProcessing = uiState.isProcessing,
                    asrProgress = uiState.asrProgress,
                    asrElapsedMs = uiState.asrElapsedMs,
                    llmProgress = uiState.llmProgress,
                    llmElapsedMs = uiState.llmElapsedMs,
                    statusMessage = uiState.statusMessage,
                    llmProfiles = uiState.llmProfiles,
                    selectedLlmProfileId = uiState.selectedLlmProfileId,
                    onLlmProfileSelected = vm::selectLlmProfile,
                    itemSize = transcriptSize,
                    onDelete = { confirmAction = ConfirmAction.DeleteTranscript },
                    onDownload = {
                        showToast(context, "正在下载识别稿…")
                        vm.exportTranscript()
                    },
                    onCopy = {
                        showToast(context, "正在复制识别稿…")
                        copyToClipboard(context, "识别稿", uiState.transcript)
                        showToast(context, "识别稿已复制")
                    },
                    onSaveAsPolished = {
                        showToast(context, "正在当作润色稿…")
                        vm.copyTranscriptToDiary()
                    },
                    onRunLlm = {
                        vm.runLlm()
                    }
                )

                DetailTab.Polished -> PolishedTabContent(
                    polishedText = uiState.diaryText,
                    onPolishedChange = vm::updateDiaryText,
                    itemSize = polishedSize,
                    onDelete = { confirmAction = ConfirmAction.DeleteDiaryText },
                    onDownload = {
                        showToast(context, "正在下载润色稿…")
                        vm.exportDiaryText()
                    },
                    onCopy = {
                        showToast(context, "正在复制润色稿…")
                        copyToClipboard(context, "润色稿", uiState.diaryText)
                        showToast(context, "润色稿已复制")
                    }
                )
            }
        }
    }

    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text("确认删除") },
            text = { Text(action.message) },
            confirmButton = {
                TextButton(onClick = {
                    when (action) {
                        ConfirmAction.DeleteAudio -> {
                            vm.deleteAudio()
                            showToast(context, "录音已删除")
                        }
                        ConfirmAction.DeleteTranscript -> {
                            vm.deleteTranscript()
                            showToast(context, "识别稿已删除")
                        }
                        ConfirmAction.DeleteDiaryText -> {
                            vm.deleteDiaryText()
                            showToast(context, "润色稿已删除")
                        }
                        ConfirmAction.DeleteEntry -> {
                            vm.deleteEntry()
                        }
                    }
                    confirmAction = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TooltipIconButton(
    tooltip: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    filled: Boolean = false,
    content: @Composable () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { Text(tooltip) },
        state = rememberTooltipState()
    ) {
        if (filled) {
            FilledIconButton(onClick = onClick, enabled = enabled) { content() }
        } else {
            IconButton(onClick = onClick, enabled = enabled) { content() }
        }
    }
}

@Composable
private fun TabSizeLabel(label: String, sizeBytes: Long, suffix: String? = null) {
    val sizePart = "$label：${formatFileSize(sizeBytes)}"
    Text(
        text = if (suffix != null) "$sizePart · $suffix" else sizePart,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun AudioTabMetadataLabel(sizeBytes: Long, formatLabel: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "录音大小：${formatFileSize(sizeBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = formatLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TabActionBar(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioTabContent(
    hasAudio: Boolean,
    isProcessing: Boolean,
    asrProgress: Float?,
    asrElapsedMs: Long,
    statusMessage: String,
    isPlaying: Boolean,
    waveformSamples: List<Float>,
    durationMs: Long,
    playbackPositionMs: Long,
    itemSize: Long,
    onTogglePlayback: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onRunAsr: () -> Unit
) {
    val windowStartMs = waveformWindowStartMs(durationMs, playbackPositionMs)
    val visibleWaveform = waveformWindowSlice(waveformSamples, durationMs, windowStartMs)
    val playheadFraction = if (isPlaying) {
        waveformPlayheadFraction(durationMs, windowStartMs, playbackPositionMs)
    } else {
        null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AudioTabMetadataLabel(
            sizeBytes = itemSize,
            formatLabel = WavRecorder.FORMAT_LABEL
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            when {
                isProcessing -> {
                    if (asrProgress != null) {
                        AsrProcessingOverlay(
                            progress = asrProgress,
                            message = statusMessage,
                            elapsedMs = asrElapsedMs,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                hasAudio -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isPlaying) {
                                    "${formatDuration(playbackPositionMs)} / ${formatDuration(durationMs)}"
                                } else {
                                    formatDuration(durationMs)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isPlaying) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        WaveformView(
                            samples = visibleWaveform,
                            playheadFraction = playheadFraction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                        Box(
                            modifier = Modifier
                                .padding(top = 16.dp)
                                .size(72.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            FilledIconButton(
                                onClick = onTogglePlayback,
                                modifier = Modifier.size(72.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "停止播放" else "试听录音",
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .height(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isPlaying) "点击停止播放" else "点击播放录音",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                else -> {
                    Text(
                        "暂无录音",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        TabActionBar {
            TooltipIconButton(
                tooltip = if (isPlaying) "停止" else "试听",
                onClick = onTogglePlayback,
                enabled = hasAudio,
                filled = true
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
            }
            TooltipIconButton(tooltip = "删除录音", onClick = onDelete, enabled = hasAudio) {
                Icon(Icons.Default.Delete, contentDescription = null)
            }
            TooltipIconButton(tooltip = "下载录音", onClick = onDownload, enabled = hasAudio) {
                Icon(Icons.Default.Download, contentDescription = null)
            }
            TooltipIconButton(
                tooltip = "重新 ASR 识别",
                onClick = onRunAsr,
                enabled = hasAudio && !isProcessing
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptTabContent(
    transcript: String,
    onTranscriptChange: (String) -> Unit,
    isProcessing: Boolean,
    asrProgress: Float?,
    asrElapsedMs: Long,
    llmProgress: Float?,
    llmElapsedMs: Long,
    statusMessage: String,
    llmProfiles: List<LlmProfile>,
    selectedLlmProfileId: String,
    onLlmProfileSelected: (String) -> Unit,
    itemSize: Long,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onCopy: () -> Unit,
    onSaveAsPolished: () -> Unit,
    onRunLlm: () -> Unit
) {
    val hasTranscript = transcript.isNotBlank()
    Column(modifier = Modifier.fillMaxSize()) {
        TabSizeLabel(label = "识别稿大小", sizeBytes = itemSize)
        if (isProcessing) {
            val progress = asrProgress ?: llmProgress
            val elapsedMs = when {
                asrProgress != null -> asrElapsedMs
                llmProgress != null -> llmElapsedMs
                else -> 0L
            }
            if (progress != null) {
                AsrProcessingOverlay(
                    progress = progress,
                    message = statusMessage,
                    elapsedMs = elapsedMs,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(statusMessage, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        DiaryTextEditor(
            value = transcript,
            onValueChange = onTranscriptChange,
            placeholder = "识别稿将显示在这里，可编辑",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LlmProfileSelector(
            profiles = llmProfiles,
            selectedId = selectedLlmProfileId,
            onSelected = onLlmProfileSelected,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        TabActionBar {
            TooltipIconButton(tooltip = "删除识别稿", onClick = onDelete, enabled = hasTranscript) {
                Icon(Icons.Default.Delete, contentDescription = null)
            }
            TooltipIconButton(tooltip = "下载识别稿", onClick = onDownload, enabled = hasTranscript) {
                Icon(Icons.Default.Download, contentDescription = null)
            }
            TooltipIconButton(tooltip = "复制识别稿", onClick = onCopy, enabled = hasTranscript) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
            }
            TooltipIconButton(tooltip = "直接当作润色稿", onClick = onSaveAsPolished, enabled = hasTranscript) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
            TooltipIconButton(
                tooltip = "LLM 加工识别稿",
                onClick = onRunLlm,
                enabled = hasTranscript && !isProcessing
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PolishedTabContent(
    polishedText: String,
    onPolishedChange: (String) -> Unit,
    itemSize: Long,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onCopy: () -> Unit
) {
    val hasPolished = polishedText.isNotBlank()
    Column(modifier = Modifier.fillMaxSize()) {
        TabSizeLabel(label = "润色稿大小", sizeBytes = itemSize)
        DiaryTextEditor(
            value = polishedText,
            onValueChange = onPolishedChange,
            placeholder = "润色稿将显示在这里，可编辑",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        TabActionBar {
            TooltipIconButton(tooltip = "删除润色稿", onClick = onDelete, enabled = hasPolished) {
                Icon(Icons.Default.Delete, contentDescription = null)
            }
            TooltipIconButton(tooltip = "下载润色稿", onClick = onDownload, enabled = hasPolished) {
                Icon(Icons.Default.Download, contentDescription = null)
            }
            TooltipIconButton(tooltip = "复制润色稿", onClick = onCopy, enabled = hasPolished) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
            }
        }
    }
}

private enum class ConfirmAction(val message: String) {
    DeleteAudio("确定删除录音吗？此操作不可恢复。"),
    DeleteTranscript("确定删除识别稿吗？此操作不可恢复。"),
    DeleteDiaryText("确定删除润色稿吗？此操作不可恢复。"),
    DeleteEntry("确定删除整篇记事及所有内容吗？此操作不可恢复。")
}
