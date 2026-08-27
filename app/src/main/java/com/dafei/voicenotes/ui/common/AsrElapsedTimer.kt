package com.dafei.voicenotes.ui.common

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AsrElapsedTimer(private val scope: CoroutineScope) {
    private var job: Job? = null

    fun start(onTick: (Long) -> Unit) {
        stop()
        val startAt = SystemClock.elapsedRealtime()
        job = scope.launch {
            while (isActive) {
                onTick(SystemClock.elapsedRealtime() - startAt)
                delay(100)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
