package com.dafei.voicenotes.ui.common

import com.dafei.voicenotes.llm.LlmPhase
import com.dafei.voicenotes.llm.LlmProgressMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LlmProgressAnimator(private val scope: CoroutineScope) {
    private var waitJob: Job? = null

    fun startWaitingPulse(onTick: (progress: Float, message: String) -> Unit) {
        waitJob?.cancel()
        waitJob = scope.launch {
            var p = 0.35f
            while (isActive) {
                delay(800)
                if (p < 0.88f) {
                    p = (p + 0.04f).coerceAtMost(0.88f)
                    onTick(p, LlmProgressMessages.format(LlmPhase.WAITING, p))
                }
            }
        }
    }

    fun stop() {
        waitJob?.cancel()
        waitJob = null
    }
}
