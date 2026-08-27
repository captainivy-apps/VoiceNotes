package com.dafei.voicenotes.asr

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dafei.voicenotes.audio.WavReader
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

class WhisperContext private constructor(private var ptr: Long) {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    suspend fun transcribe(
        audio: FloatArray,
        language: String = "zh",
        progressListener: AsrProgressListener? = null
    ): String =
        withContext(dispatcher) {
            require(ptr != 0L)
            val threads = WhisperCpuConfig.preferredThreadCount
            WhisperLib.fullTranscribe(ptr, threads, language, audio, progressListener)
            buildString {
                val count = WhisperLib.getTextSegmentCount(ptr)
                for (i in 0 until count) {
                    append(WhisperLib.getTextSegment(ptr, i))
                }
            }.trim()
        }

    suspend fun release() = withContext(dispatcher) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0
        }
    }

    companion object {
        suspend fun create(modelPath: String): WhisperContext = withContext(
            Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        ) {
            val ptr = WhisperLib.initContext(modelPath)
            if (ptr == 0L) error("无法加载 ASR 模型: $modelPath")
            WhisperContext(ptr)
        }
    }
}

class WhisperLib private constructor() {
    companion object {
        init {
            when {
                isArmEabiV7a() && cpuInfo()?.contains("vfpv4") == true -> {
                    System.loadLibrary("whisper_vfpv4")
                }
                isArmEabiV8a() && cpuInfo()?.contains("fphp") == true -> {
                    System.loadLibrary("whisper_v8fp16_va")
                }
                else -> System.loadLibrary("whisper")
            }
            Log.d("WhisperLib", "Loaded native whisper, ABI=${Build.SUPPORTED_ABIS.firstOrNull()}")
        }

        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(
            contextPtr: Long,
            numThreads: Int,
            language: String,
            audioData: FloatArray,
            progressListener: AsrProgressListener?
        )
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getSystemInfo(): String

        private fun isArmEabiV7a(): Boolean = Build.SUPPORTED_ABIS.firstOrNull() == "armeabi-v7a"
        private fun isArmEabiV8a(): Boolean = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        private fun cpuInfo(): String? = try {
            File("/proc/cpuinfo").readText()
        } catch (_: Exception) {
            null
        }
    }
}

object WhisperCpuConfig {
    val preferredThreadCount: Int
        get() = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
}

class WhisperEngine(
    private val downloader: ModelDownloader
) {
    private var context: WhisperContext? = null
    private var loadedModelId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun transcribe(
        modelId: String,
        wavFile: File,
        language: String = "zh",
        onProgress: ((Float, AsrPhase) -> Unit)? = null
    ): Result<String> = runCatching {
        val model = AsrModels.find(modelId) ?: error("未知模型: $modelId")
        if (!downloader.isDownloaded(model)) error("模型未下载，请先在设置页下载")
        val modelPath = downloader.modelFile(model).absolutePath

        val report: (Float, AsrPhase) -> Unit = { progress, phase ->
            onProgress?.let { cb -> mainHandler.post { cb(progress, phase) } }
        }

        val needLoad = loadedModelId != modelId || context == null
        if (needLoad) {
            report(0f, AsrPhase.LOADING_MODEL)
            ensureContext(modelId, modelPath)
            report(0.10f, AsrPhase.LOADING_MODEL)
        } else {
            report(0.10f, AsrPhase.LOADING_MODEL)
        }

        report(0.10f, AsrPhase.READING_AUDIO)
        val info = WavReader.readInfo(wavFile)
        if (info.sampleCount == 0) error("录音文件为空")
        report(0.15f, AsrPhase.READING_AUDIO)

        val engine = context!!
        if (info.sampleCount <= SEGMENT_SAMPLES) {
            val audio = WavReader.readFloatRange(wavFile, 0, info.sampleCount)
            val listener = onProgress?.let {
                AsrProgressListener { whisperPercent ->
                    val overall = 0.15f + whisperPercent / 100f * 0.85f
                    report(overall, AsrPhase.TRANSCRIBING)
                }
            }
            return@runCatching engine.transcribe(audio, language, listener)
        }

        // Long audio: transcribe ~5 min windows with 1s overlap to keep float buffers small.
        val step = SEGMENT_SAMPLES - OVERLAP_SAMPLES
        val segmentStarts = ArrayList<Int>()
        var start = 0
        while (start < info.sampleCount) {
            segmentStarts.add(start)
            if (start + SEGMENT_SAMPLES >= info.sampleCount) break
            start += step
        }
        val segmentCount = segmentStarts.size
        val parts = ArrayList<String>(segmentCount)
        for ((index, segStart) in segmentStarts.withIndex()) {
            val segEnd = minOf(segStart + SEGMENT_SAMPLES, info.sampleCount)
            val audio = WavReader.readFloatRange(wavFile, segStart, segEnd - segStart)
            val listener = onProgress?.let {
                AsrProgressListener { whisperPercent ->
                    val segmentProgress = (index + whisperPercent / 100f) / segmentCount
                    val overall = 0.15f + segmentProgress * 0.85f
                    report(overall, AsrPhase.TRANSCRIBING)
                }
            }
            val text = engine.transcribe(audio, language, listener)
            if (text.isNotBlank()) parts.add(text)
            report(0.15f + (index + 1f) / segmentCount * 0.85f, AsrPhase.TRANSCRIBING)
        }
        parts.joinToString("\n").trim()
    }

    companion object {
        /** ~5 minutes at 16 kHz. */
        private const val SEGMENT_SAMPLES = 16_000 * 300
        /** ~1 second overlap between segments. */
        private const val OVERLAP_SAMPLES = 16_000
    }

    private suspend fun ensureContext(modelId: String, modelPath: String) {
        if (loadedModelId == modelId && context != null) return
        context?.release()
        context = WhisperContext.create(modelPath)
        loadedModelId = modelId
    }

    suspend fun release() {
        context?.release()
        context = null
        loadedModelId = null
    }
}
