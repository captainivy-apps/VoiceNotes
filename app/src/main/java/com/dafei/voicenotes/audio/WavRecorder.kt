package com.dafei.voicenotes.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class WavRecorder {
    companion object {
        const val SAMPLE_RATE = 16000
        const val FORMAT_LABEL = "WAV · 16 kHz · 单声道 · PCM 16-bit"
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_AMPLITUDE_HISTORY = 120
        private const val CHECKPOINT_INTERVAL_MS = 10_000L
    }

    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var writeThread: Thread? = null
    private var outputFile: File? = null
    private val totalPcmBytes = AtomicLong(0)
    private val amplitudeHistory = ArrayDeque<Float>()
    private val amplitudeLock = Any()
    private var displayGain = 0.08f
    private val writeError = AtomicReference<String?>(null)

    val amplitudes: List<Float>
        get() = synchronized(amplitudeLock) { amplitudeHistory.toList() }

    val lastError: String?
        get() = writeError.get()

    fun start(output: File): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf <= 0) return false
        return try {
            outputFile = output
            totalPcmBytes.set(0)
            writeError.set(null)
            synchronized(amplitudeLock) {
                amplitudeHistory.clear()
            }
            displayGain = 0.08f
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBuf * 2
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return false
            RandomAccessFile(output, "rw").use { raf ->
                raf.setLength(0)
                writeWavHeader(raf, 0)
            }
            running.set(true)
            audioRecord?.startRecording()
            writeThread = Thread { writeLoop(minBuf) }.also { it.start() }
            true
        } catch (_: SecurityException) {
            false
        } catch (e: Exception) {
            writeError.set(e.message ?: "无法启动录音")
            false
        }
    }

    fun stop(): Long {
        running.set(false)
        writeThread?.join(3000)
        writeThread = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
        val pcmBytes = totalPcmBytes.get()
        outputFile?.let { file ->
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    writeWavHeader(raf, pcmBytes)
                    syncFile(raf)
                }
            } catch (e: Exception) {
                writeError.compareAndSet(null, e.message ?: "写入 WAV 头失败")
            }
        }
        return (pcmBytes * 1000L) / (SAMPLE_RATE * 2L)
    }

    private fun writeLoop(bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        val file = outputFile ?: return
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(44)
                var lastCheckpointAt = SystemClock.elapsedRealtime()
                while (running.get()) {
                    val read = try {
                        audioRecord?.read(buffer, 0, buffer.size) ?: break
                    } catch (e: Exception) {
                        writeError.set(e.message ?: "AudioRecord 读取失败")
                        break
                    }
                    if (read <= 0) continue
                    raf.write(buffer, 0, read)
                    totalPcmBytes.addAndGet(read.toLong())
                    val amp = normalizeAmplitude(buffer, read)
                    synchronized(amplitudeLock) {
                        amplitudeHistory.addLast(amp)
                        while (amplitudeHistory.size > MAX_AMPLITUDE_HISTORY) {
                            amplitudeHistory.removeFirst()
                        }
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastCheckpointAt >= CHECKPOINT_INTERVAL_MS) {
                        checkpoint(raf, totalPcmBytes.get())
                        lastCheckpointAt = now
                    }
                }
            }
        } catch (e: Exception) {
            writeError.set(e.message ?: "录音写入失败")
            running.set(false)
        }
    }

    /** Rewrite WAV sizes and fsync so a crash loses at most ~10s of audio. */
    private fun checkpoint(raf: RandomAccessFile, pcmBytes: Long) {
        val dataPos = raf.filePointer
        writeWavHeader(raf, pcmBytes)
        raf.seek(dataPos)
        syncFile(raf)
    }

    private fun syncFile(raf: RandomAccessFile) {
        try {
            raf.fd.sync()
        } catch (_: Exception) {
            // Best-effort; header rewrite still helps most players.
        }
    }

    private fun normalizeAmplitude(buffer: ByteArray, length: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            val absSample = abs(sample)
            if (absSample > peak) peak = absSample
            i += 2
        }
        if (peak == 0) return 0f
        val raw = peak / 32768.0f
        displayGain = maxOf(raw * 1.2f, displayGain * 0.98f)
        return (raw / displayGain.coerceAtLeast(0.08f)).coerceIn(0f, 1f)
    }

    private fun writeWavHeader(raf: RandomAccessFile, pcmBytes: Long) {
        val totalDataLen = pcmBytes + 36
        val byteRate = SAMPLE_RATE * 2
        raf.seek(0)
        raf.write("RIFF".toByteArray())
        raf.write(intToLittleEndian(totalDataLen.toInt()))
        raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray())
        raf.write(intToLittleEndian(16))
        raf.write(shortToLittleEndian(1))
        raf.write(shortToLittleEndian(1))
        raf.write(intToLittleEndian(SAMPLE_RATE))
        raf.write(intToLittleEndian(byteRate))
        raf.write(shortToLittleEndian(2))
        raf.write(shortToLittleEndian(16))
        raf.write("data".toByteArray())
        raf.write(intToLittleEndian(pcmBytes.toInt()))
    }

    private fun intToLittleEndian(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xff).toByte(),
            (value shr 8 and 0xff).toByte(),
            (value shr 16 and 0xff).toByte(),
            (value shr 24 and 0xff).toByte()
        )

    private fun shortToLittleEndian(value: Int): ByteArray =
        byteArrayOf((value and 0xff).toByte(), (value shr 8 and 0xff).toByte())
}

object WavReader {
    private const val READ_BUF_SIZE = 8192

    data class WavInfo(val dataOffset: Long, val sampleCount: Int) {
        val durationMs: Long
            get() = sampleCount * 1000L / WavRecorder.SAMPLE_RATE
    }

    fun readInfo(file: File): WavInfo =
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() > 44) { "Invalid WAV file" }
            val dataOffset = findDataOffset(raf).toLong()
            val pcmBytes = (raf.length() - dataOffset).coerceAtLeast(0)
            WavInfo(dataOffset, (pcmBytes / 2).toInt())
        }

    fun readAsFloatArray(file: File): FloatArray {
        val info = readInfo(file)
        return readFloatRange(file, 0, info.sampleCount)
    }

    /** Reads [count] samples starting at [startSample] (0-based PCM sample index). */
    fun readFloatRange(file: File, startSample: Int, count: Int): FloatArray {
        if (count <= 0) return FloatArray(0)
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() > 44) { "Invalid WAV file" }
            val dataOffset = findDataOffset(raf).toLong()
            val maxSamples = ((raf.length() - dataOffset) / 2).toInt().coerceAtLeast(0)
            val start = startSample.coerceIn(0, maxSamples)
            val n = count.coerceAtMost(maxSamples - start)
            if (n <= 0) return FloatArray(0)
            val samples = FloatArray(n)
            raf.seek(dataOffset + start * 2L)
            val buf = ByteArray(READ_BUF_SIZE)
            var filled = 0
            while (filled < n) {
                val bytesNeeded = (n - filled) * 2
                val toRead = minOf(buf.size, bytesNeeded)
                val read = raf.read(buf, 0, toRead)
                if (read <= 0) break
                var i = 0
                while (i + 1 < read && filled < n) {
                    val sample = (buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)
                    samples[filled++] = sample / 32768.0f
                    i += 2
                }
            }
            return if (filled == n) samples else samples.copyOf(filled)
        }
    }

    /**
     * Streams PCM and returns peak bars for the timeline waveform without loading
     * the full float buffer. Bar count matches [downsampleForTimeline] logic.
     */
    fun readWaveformPeaks(
        file: File,
        windowMs: Long,
        barsPerWindow: Int
    ): Pair<List<Float>, Long> {
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() > 44) { "Invalid WAV file" }
            val dataOffset = findDataOffset(raf).toLong()
            val sampleCount = ((raf.length() - dataOffset) / 2).toInt().coerceAtLeast(0)
            if (sampleCount == 0) return emptyList<Float>() to 0L
            val durationMs = sampleCount * 1000L / WavRecorder.SAMPLE_RATE
            val totalBars = if (durationMs <= windowMs) {
                barsPerWindow
            } else {
                ((durationMs.toDouble() / windowMs) * barsPerWindow).toInt()
                    .coerceAtLeast(barsPerWindow)
            }
            raf.seek(dataOffset)
            val buf = ByteArray(READ_BUF_SIZE)
            if (sampleCount <= totalBars) {
                val peaks = ArrayList<Float>(sampleCount)
                var remaining = sampleCount
                while (remaining > 0) {
                    val toRead = minOf(buf.size, remaining * 2)
                    val read = raf.read(buf, 0, toRead)
                    if (read <= 0) break
                    var i = 0
                    while (i + 1 < read) {
                        val sample = (buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)
                        peaks.add(kotlin.math.abs(sample) / 32768.0f)
                        remaining--
                        i += 2
                    }
                }
                return peaks to durationMs
            }
            val blockSize = sampleCount / totalBars
            val peaks = FloatArray(totalBars)
            var sampleIndex = 0
            var barIndex = 0
            var blockPeak = 0f
            while (sampleIndex < sampleCount && barIndex < totalBars) {
                val toRead = minOf(buf.size, (sampleCount - sampleIndex) * 2)
                val read = raf.read(buf, 0, toRead)
                if (read <= 0) break
                var i = 0
                while (i + 1 < read && barIndex < totalBars) {
                    val sample = (buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)
                    val abs = kotlin.math.abs(sample) / 32768.0f
                    if (abs > blockPeak) blockPeak = abs
                    sampleIndex++
                    i += 2
                    val barStart = barIndex * blockSize
                    val barEnd = if (barIndex == totalBars - 1) sampleCount else barStart + blockSize
                    if (sampleIndex >= barEnd) {
                        peaks[barIndex++] = blockPeak
                        blockPeak = 0f
                    }
                }
            }
            return peaks.asList() to durationMs
        }
    }

    private fun findDataOffset(raf: RandomAccessFile): Int {
        raf.seek(0)
        val header = ByteArray(12)
        if (raf.read(header) < 12) return 44
        var pos = 12L
        val chunkHdr = ByteArray(8)
        while (pos + 8 < raf.length()) {
            raf.seek(pos)
            if (raf.read(chunkHdr) < 8) break
            val chunkId = String(chunkHdr, 0, 4)
            val chunkSize = littleEndianInt(chunkHdr, 4)
            if (chunkId == "data") return (pos + 8).toInt()
            pos += 8 + chunkSize
        }
        return 44
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
