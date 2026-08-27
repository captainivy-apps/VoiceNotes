package com.dafei.voicenotes.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

object FileExporter {
    fun exportAudio(context: Context, source: File, displayName: String): Result<String> {
        val mimeType = ExportFileNames.audioMimeType(ExportFileNames.audioExtension(source))
        return exportBinary(context, source, displayName, mimeType)
    }

    fun exportText(context: Context, content: String, displayName: String): Result<String> =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: error("无法写入下载目录")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } ?: error("无法打开输出流")
                "Download/$displayName"
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val target = File(dir, displayName)
                target.writeText(content, Charsets.UTF_8)
                target.absolutePath
            }
        }

    private fun exportBinary(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String
    ): Result<String> = runCatching {
        require(source.exists()) { "文件不存在" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("无法写入下载目录")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { input -> input.copyTo(out) }
            } ?: error("无法打开输出流")
            "Download/$displayName"
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val target = File(dir, displayName)
            source.copyTo(target, overwrite = true)
            target.absolutePath
        }
    }
}
