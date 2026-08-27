package com.dafei.voicenotes.asr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelDownloader(context: Context) {
    private val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun modelFile(model: AsrModelInfo): File = File(modelsDir, model.fileName)

    fun isDownloaded(model: AsrModelInfo): Boolean {
        val file = modelFile(model)
        return file.exists() && file.length() > 1024 * 1024
    }

    fun downloadedAt(model: AsrModelInfo): Long? {
        val file = modelFile(model)
        return if (isDownloaded(model)) file.lastModified() else null
    }

    suspend fun download(
        model: AsrModelInfo,
        useMirror: Boolean = false,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val url = if (useMirror) {
                model.mirrorDownloadUrl ?: error("该模型暂无镜像")
            } else {
                model.downloadUrl
            }
            val target = modelFile(model)
            val temp = File(target.parentFile, "${target.name}.download")
            downloadFromUrl(url, temp, onProgress)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target
        }
    }

    private fun downloadFromUrl(
        url: String,
        temp: File,
        onProgress: (Float) -> Unit
    ) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败: HTTP ${response.code}")
            val body = response.body ?: error("下载失败: 空响应")
            val total = body.contentLength().coerceAtLeast(1L)
            body.byteStream().use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
    }

    fun delete(model: AsrModelInfo): Boolean {
        val file = modelFile(model)
        return !file.exists() || file.delete()
    }
}
