package com.dafei.voicenotes.ui.common

import com.dafei.voicenotes.data.local.DiaryEntry
import java.io.File
import java.util.Locale

fun audioFileSize(path: String?): Long =
    path?.let { File(it).takeIf(File::exists)?.length() ?: 0L } ?: 0L

fun textByteSize(text: String?): Long =
    text?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L

fun diaryTotalSize(entry: DiaryEntry): Long =
    diaryTotalSize(entry.audioPath, entry.transcriptText, entry.diaryText)

fun diaryTotalSize(audioPath: String?, transcript: String?, diaryText: String?): Long =
    audioFileSize(audioPath) + textByteSize(transcript) + textByteSize(diaryText)

fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024.0))
}
