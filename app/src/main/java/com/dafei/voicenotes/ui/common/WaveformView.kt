package com.dafei.voicenotes.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

const val WAVEFORM_WINDOW_MS = 30_000L
const val WAVEFORM_BARS_PER_WINDOW = 120

@Composable
fun WaveformView(
    samples: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = Color(0xFF6750A4),
    emptyColor: Color = Color.LightGray,
    playheadFraction: Float? = null,
    playheadColor: Color = Color(0xFFE53935)
) {
    Canvas(modifier = modifier) {
        val midY = size.height / 2f
        val barWidth = if (samples.isEmpty()) 0f else size.width / samples.size.coerceAtLeast(1)
        if (samples.isEmpty()) {
            drawLine(
                color = emptyColor,
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 2f
            )
        } else {
            samples.forEachIndexed { index, amp ->
                val x = index * barWidth
                val h = amp * size.height * 0.8f
                drawLine(
                    color = barColor,
                    start = Offset(x, midY - h / 2),
                    end = Offset(x, midY + h / 2),
                    strokeWidth = barWidth.coerceAtLeast(2f)
                )
            }
        }
        playheadFraction?.let { fraction ->
            val x = fraction.coerceIn(0f, 1f) * size.width
            drawLine(
                color = playheadColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f
            )
        }
    }
}

fun downsampleForWaveform(samples: FloatArray, maxBars: Int = 120): List<Float> {
    if (samples.isEmpty()) return emptyList()
    if (samples.size <= maxBars) return samples.map { abs(it) }
    val blockSize = samples.size / maxBars
    return (0 until maxBars).map { i ->
        val start = i * blockSize
        val end = minOf(start + blockSize, samples.size)
        var peak = 0f
        for (j in start until end) peak = maxOf(peak, abs(samples[j]))
        peak
    }
}

fun downsampleForTimeline(samples: FloatArray, sampleRate: Int): List<Float> {
    if (samples.isEmpty()) return emptyList()
    val durationMs = samples.size * 1000L / sampleRate
    val totalBars = if (durationMs <= WAVEFORM_WINDOW_MS) {
        WAVEFORM_BARS_PER_WINDOW
    } else {
        ((durationMs.toDouble() / WAVEFORM_WINDOW_MS) * WAVEFORM_BARS_PER_WINDOW).toInt()
            .coerceAtLeast(WAVEFORM_BARS_PER_WINDOW)
    }
    return downsampleForWaveform(samples, totalBars)
}

fun waveformWindowStartMs(durationMs: Long, playbackPositionMs: Long): Long {
    if (durationMs <= WAVEFORM_WINDOW_MS) return 0L
    val maxStart = durationMs - WAVEFORM_WINDOW_MS
    val followOffset = (WAVEFORM_WINDOW_MS * 0.7).toLong()
    return (playbackPositionMs - followOffset).coerceIn(0L, maxStart)
}

fun waveformWindowSlice(
    allBars: List<Float>,
    durationMs: Long,
    windowStartMs: Long
): List<Float> {
    if (allBars.isEmpty()) return emptyList()
    if (durationMs <= WAVEFORM_WINDOW_MS) return allBars
    val totalBars = allBars.size
    val startBar = ((windowStartMs.toDouble() / durationMs) * totalBars).toInt()
        .coerceIn(0, (totalBars - WAVEFORM_BARS_PER_WINDOW).coerceAtLeast(0))
    val endBar = minOf(startBar + WAVEFORM_BARS_PER_WINDOW, totalBars)
    return allBars.subList(startBar, endBar)
}

fun waveformPlayheadFraction(
    durationMs: Long,
    windowStartMs: Long,
    playbackPositionMs: Long
): Float {
    if (durationMs <= 0) return 0f
    val windowDurationMs = minOf(WAVEFORM_WINDOW_MS, durationMs - windowStartMs)
    if (windowDurationMs <= 0) return 0f
    return ((playbackPositionMs - windowStartMs).toFloat() / windowDurationMs).coerceIn(0f, 1f)
}
