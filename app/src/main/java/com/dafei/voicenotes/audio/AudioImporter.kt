package com.dafei.voicenotes.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

object AudioImporter {
    fun importToWav(context: Context, sourceUri: Uri, destFile: File): Long {
        RandomAccessFile(destFile, "rw").use { raf ->
            raf.setLength(0)
            writeWavHeader(raf, 0)
            raf.seek(44)
            val pcmBytes = decodeAndWrite(context, sourceUri, raf)
            if (pcmBytes == 0L) {
                destFile.delete()
                error("音频文件为空")
            }
            writeWavHeader(raf, pcmBytes)
            return (pcmBytes * 1000L) / (WavRecorder.SAMPLE_RATE * 2L)
        }
    }

    private fun decodeAndWrite(context: Context, uri: Uri, raf: RandomAccessFile): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                break
            }
        }
        if (trackIndex < 0) {
            extractor.release()
            error("未找到音频轨道")
        }

        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: run {
                extractor.release()
                error("不支持的音频格式")
            }

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var resampler: StreamingResampler? = null
        val writeBuf = ByteArray(8192)
        var writeBufPos = 0
        var totalPcmBytes = 0L
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false

        fun flushWriteBuf() {
            if (writeBufPos > 0) {
                raf.write(writeBuf, 0, writeBufPos)
                totalPcmBytes += writeBufPos
                writeBufPos = 0
            }
        }

        fun writeSamples(samples: ShortArray) {
            for (sample in samples) {
                if (writeBufPos + 1 >= writeBuf.size) flushWriteBuf()
                writeBuf[writeBufPos++] = (sample.toInt() and 0xFF).toByte()
                writeBuf[writeBufPos++] = (sample.toInt() shr 8 and 0xFF).toByte()
            }
        }

        fun ensureResampler(rate: Int) {
            if (resampler == null || resampler!!.fromRate != rate) {
                resampler?.flush()?.let { writeSamples(it) }
                resampler = StreamingResampler(rate, WavRecorder.SAMPLE_RATE)
            }
        }

        try {
            while (true) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        ensureResampler(sampleRate)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        if (bufferInfo.size > 0) {
                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(bytes)
                            val mono = pcmBytesToMono(bytes, channelCount)
                            ensureResampler(sampleRate)
                            writeSamples(resampler!!.feed(mono))
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }
            }
            resampler?.flush()?.let { writeSamples(it) }
            flushWriteBuf()
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return totalPcmBytes
    }

    private fun pcmBytesToMono(bytes: ByteArray, channelCount: Int): ShortArray {
        val channels = channelCount.coerceAtLeast(1)
        val frameCount = bytes.size / 2 / channels
        val mono = ShortArray(frameCount)
        for (frame in 0 until frameCount) {
            var sum = 0
            for (channel in 0 until channels) {
                val index = (frame * channels + channel) * 2
                if (index + 1 >= bytes.size) break
                val sample = (bytes[index + 1].toInt() shl 8) or (bytes[index].toInt() and 0xFF)
                sum += sample
            }
            mono[frame] = (sum / channels).toShort()
        }
        return mono
    }

    private fun writeWavHeader(raf: RandomAccessFile, pcmBytes: Long) {
        val totalDataLen = pcmBytes + 36
        val byteRate = WavRecorder.SAMPLE_RATE * 2
        raf.seek(0)
        raf.write("RIFF".toByteArray())
        raf.write(intToLittleEndian(totalDataLen.toInt()))
        raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray())
        raf.write(intToLittleEndian(16))
        raf.write(shortToLittleEndian(1))
        raf.write(shortToLittleEndian(1))
        raf.write(intToLittleEndian(WavRecorder.SAMPLE_RATE))
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

/** Linear-interpolation resampler that keeps only a small pending window across chunks. */
private class StreamingResampler(
    val fromRate: Int,
    private val toRate: Int
) {
    private val ratio = fromRate.toDouble() / toRate
    private val pending = ArrayList<Short>(4096)
    private var srcPos = 0.0

    fun feed(samples: ShortArray): ShortArray {
        if (fromRate == toRate) return samples
        if (samples.isEmpty()) return ShortArray(0)
        for (sample in samples) pending.add(sample)
        return emitAvailable()
    }

    fun flush(): ShortArray {
        if (fromRate == toRate || pending.isEmpty()) return ShortArray(0)
        // Pad one duplicate so the last fractional position can interpolate.
        pending.add(pending.last())
        val out = emitAvailable()
        pending.clear()
        srcPos = 0.0
        return out
    }

    private fun emitAvailable(): ShortArray {
        val out = ArrayList<Short>((pending.size / ratio).toInt().coerceAtLeast(0) + 8)
        while (srcPos + 1.0 < pending.size) {
            val index = srcPos.toInt()
            val fraction = srcPos - index
            val a = pending[index].toInt()
            val b = pending[index + 1].toInt()
            out.add((a + (b - a) * fraction).roundToInt().toShort())
            srcPos += ratio
        }
        val drop = srcPos.toInt().coerceAtMost(pending.size)
        if (drop > 0) {
            pending.subList(0, drop).clear()
            srcPos -= drop
        }
        return out.toShortArray()
    }
}
