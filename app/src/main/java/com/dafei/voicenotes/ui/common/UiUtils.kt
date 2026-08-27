package com.dafei.voicenotes.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun showToast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
}

fun formatDateTime(timestamp: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(timestamp))
}

fun diaryPreview(entry: com.dafei.voicenotes.data.local.DiaryEntry): String {
    val text = entry.diaryText?.trim().orEmpty().ifBlank {
        entry.transcriptText?.trim().orEmpty()
    }
    if (text.isBlank()) return "新录音"
    return text.lineSequence().firstOrNull()?.take(40) ?: "新录音"
}
