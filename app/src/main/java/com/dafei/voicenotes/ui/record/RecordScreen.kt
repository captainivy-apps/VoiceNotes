package com.dafei.voicenotes.ui.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dafei.voicenotes.VoiceNotesApp
import com.dafei.voicenotes.audio.RecordingService
import com.dafei.voicenotes.audio.RecordingServiceClient
import com.dafei.voicenotes.data.AppPrefs
import com.dafei.voicenotes.ui.common.AsrProcessingOverlay
import com.dafei.voicenotes.ui.common.LlmProfileSelector
import com.dafei.voicenotes.ui.common.WaveformView
import com.dafei.voicenotes.ui.common.formatDuration
import com.dafei.voicenotes.ui.common.showToast
import kotlinx.coroutines.delay

@Composable
fun RecordScreen(app: VoiceNotesApp, onSaved: (Long) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val vm: RecordViewModel = viewModel(factory = RecordViewModel.factory(app))
    val uiState by vm.uiState.collectAsState()
    val session by RecordingService.session.collectAsState()

    LaunchedEffect(Unit) {
        vm.refreshLlmProfiles()
    }

    var recordingService by remember { mutableStateOf<RecordingService?>(null) }
    val serviceClient = remember {
        RecordingServiceClient(context.applicationContext) { service ->
            recordingService = service
        }
    }

    DisposableEffect(session.isRecording) {
        if (session.isRecording) {
            serviceClient.bind()
        } else {
            serviceClient.unbind()
        }
        onDispose {
            serviceClient.unbind()
        }
    }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (!granted) showToast(context, "需要麦克风权限才能录音")
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasMicPermission = results[Manifest.permission.RECORD_AUDIO] == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission =
                results[Manifest.permission.POST_NOTIFICATIONS] == true
        }
        if (!hasMicPermission) {
            showToast(context, "需要麦克风权限才能录音")
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importAudio(context, uri)
        }
    }

    val waveform = remember { mutableStateListOf<Float>() }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val isRecording = session.isRecording || uiState.isRecording

    LaunchedEffect(session.isRecording, session.outputPath) {
        if (session.isRecording && session.outputPath != null && !uiState.isRecording) {
            waveform.clear()
            vm.startRecording(session.outputPath!!)
        }
    }

    LaunchedEffect(session.completedPath, session.completedDurationMs) {
        val completed = RecordingService.consumeCompleted() ?: return@LaunchedEffect
        val (path, duration) = completed
        if (!uiState.isRecording) {
            vm.startRecording(path)
        }
        vm.stopRecording(duration)
    }

    LaunchedEffect(session.error) {
        val err = session.error ?: return@LaunchedEffect
        showToast(context, err)
        if (uiState.isRecording && !session.isRecording) {
            vm.onRecordingFailed(err)
        }
        RecordingService.clearError()
    }

    LaunchedEffect(session.isRecording) {
        while (session.isRecording) {
            val amps = recordingService?.amplitudes().orEmpty()
            waveform.clear()
            waveform.addAll(amps)
            val started = session.startedAtElapsedRealtime
            elapsedMs = if (started > 0L) {
                (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            } else {
                elapsedMs + 100
            }
            delay(100)
        }
    }

    LaunchedEffect(uiState.entryId, uiState.transcript, uiState.isRecording) {
        if (uiState.entryId == null && uiState.transcript == null && !uiState.isRecording) {
            waveform.clear()
        }
    }

    val keepScreenOn = isRecording && AppPrefs.getKeepScreenOnWhileRecording(context)
    DisposableEffect(view, keepScreenOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepScreenOn
        onDispose {
            view.keepScreenOn = previous
        }
    }

    fun requestPermissionsAndStart() {
        if (!hasMicPermission) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                )
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val file = app.repository.newRecordingFile()
        waveform.clear()
        elapsedMs = 0L
        RecordingService.startRecording(context, file.absolutePath)
        vm.startRecording(file.absolutePath)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("语音记事", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (isRecording) formatDuration(elapsedMs) else formatDuration(uiState.durationMs),
            style = MaterialTheme.typography.titleLarge
        )

        WaveformView(
            samples = waveform,
            barColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        )

        if (uiState.isProcessing) {
            val progress = uiState.asrProgress ?: uiState.llmProgress
            val processingElapsedMs = when {
                uiState.asrProgress != null -> uiState.asrElapsedMs
                uiState.llmProgress != null -> uiState.llmElapsedMs
                else -> 0L
            }
            if (progress != null) {
                AsrProcessingOverlay(
                    progress = progress,
                    message = uiState.statusMessage,
                    elapsedMs = processingElapsedMs
                )
            } else {
                CircularProgressIndicator()
                Text(uiState.statusMessage)
            }
        } else if (uiState.transcript == null && uiState.entryId != null) {
            Text("录音已保存", style = MaterialTheme.typography.titleMedium)
            Text(
                formatDuration(uiState.durationMs),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = vm::startTranscription,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("开始语音识别")
            }
            OutlinedButton(
                onClick = vm::reset,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("开始录制新的记事")
            }
        } else if (uiState.transcript == null) {
            FilledIconButton(
                onClick = {
                    if (isRecording) {
                        RecordingService.stopRecording(context)
                    } else {
                        requestPermissionsAndStart()
                    }
                },
                modifier = Modifier.size(96.dp)
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
            }
            Text(if (isRecording) "点击停止录音" else "点击开始录音")

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    filePickerLauncher.launch(arrayOf("audio/*"))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isRecording
            ) {
                Icon(
                    imageVector = Icons.Default.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("导入录音文件")
            }
            Text(
                "支持 WAV、MP3、M4A 等常见格式",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        uiState.transcript?.let { transcript ->
            OutlinedTextField(
                value = transcript,
                onValueChange = vm::updateTranscript,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("识别稿") },
                minLines = 5
            )
            Button(
                onClick = {
                    vm.saveDirect(onSaved)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("直接保存为润色稿")
            }
            LlmProfileSelector(
                profiles = uiState.llmProfiles,
                selectedId = uiState.selectedLlmProfileId,
                onSelected = vm::selectLlmProfile
            )
            OutlinedButton(
                onClick = {
                    vm.saveWithLlm(onSaved)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isProcessing
            ) {
                Text("LLM 加工后保存")
            }
            OutlinedButton(
                onClick = vm::reset,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("开始录制新的记事")
            }
        }

        uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (uiState.awaitingAsrPrompt) {
        AlertDialog(
            onDismissRequest = vm::dismissAsrPrompt,
            title = { Text("开始语音识别？") },
            text = {
                Text("音频已保存。是否立即使用 ASR 模型将语音转为文字？")
            },
            confirmButton = {
                TextButton(onClick = vm::startTranscription) {
                    Text("立即识别")
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissAsrPrompt) {
                    Text("稍后")
                }
            }
        )
    }
}
