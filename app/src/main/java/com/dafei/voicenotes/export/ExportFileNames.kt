package com.dafei.voicenotes.export

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExportFileNames {
    const val TYPE_RECORD = "record"
    const val TYPE_FIRST = "first"
    const val TYPE_FINAL = "final"

    fun build(type: String, extension: String, timestampMs: Long = System.currentTimeMillis()): String {
        val ext = extension.removePrefix(".")
        return "voicenotes-$type-${formatTimestamp(timestampMs)}.$ext"
    }

    fun audioExtension(source: File): String =
        source.extension.lowercase(Locale.getDefault()).ifBlank { "wav" }

    fun audioMimeType(extension: String): String = when (extension.lowercase(Locale.getDefault())) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    private fun formatTimestamp(timestampMs: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
        return fmt.format(Date(timestampMs))
    }
}
