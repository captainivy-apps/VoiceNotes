package com.dafei.voicenotes.asr

fun interface AsrProgressListener {
    fun onProgress(percent: Int)
}

enum class AsrPhase {
    LOADING_MODEL,
    READING_AUDIO,
    TRANSCRIBING
}

object AsrProgressMessages {
    fun format(phase: AsrPhase, progress: Float): String {
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        return when (phase) {
            AsrPhase.LOADING_MODEL -> "正在加载模型… $percent%"
            AsrPhase.READING_AUDIO -> "正在读取音频… $percent%"
            AsrPhase.TRANSCRIBING -> "正在识别… $percent%"
        }
    }
}
