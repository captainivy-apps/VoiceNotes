package com.dafei.voicenotes.llm

enum class LlmPhase {
    PREPARING,
    CONNECTING,
    SENDING,
    WAITING,
    RECEIVING,
    PARSING
}

object LlmProgressMessages {
    fun format(phase: LlmPhase, progress: Float): String {
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        return when (phase) {
            LlmPhase.PREPARING -> "正在准备润色请求… $percent%"
            LlmPhase.CONNECTING -> "正在连接 LLM 服务… $percent%"
            LlmPhase.SENDING -> "正在发送识别稿… $percent%"
            LlmPhase.WAITING -> "正在等待模型生成润色稿… $percent%"
            LlmPhase.RECEIVING -> "正在接收润色稿… $percent%"
            LlmPhase.PARSING -> "正在解析润色结果… $percent%"
        }
    }
}
