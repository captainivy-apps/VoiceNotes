package com.dafei.voicenotes.data

import android.content.Context
import com.dafei.voicenotes.data.local.DiaryDao
import com.dafei.voicenotes.data.local.DiaryEntry
import kotlinx.coroutines.flow.Flow
import java.io.File

class DiaryRepository(
    private val context: Context,
    private val dao: DiaryDao
) {
    private val recordingsDir: File
        get() = File(context.filesDir, "recordings").also { it.mkdirs() }

    fun observeAll(): Flow<List<DiaryEntry>> = dao.observeAll()

    suspend fun getAllOrdered(): List<DiaryEntry> = dao.getAllOrdered()

    suspend fun getById(id: Long): DiaryEntry? = dao.getById(id)

    suspend fun createDraft(audioPath: String, durationMs: Long): DiaryEntry {
        val now = System.currentTimeMillis()
        val id = dao.insert(
            DiaryEntry(
                createdAt = now,
                updatedAt = now,
                audioPath = audioPath,
                transcriptText = null,
                diaryText = null,
                durationMs = durationMs
            )
        )
        return dao.getById(id) ?: error("Failed to create diary entry")
    }

    suspend fun update(entry: DiaryEntry) {
        dao.update(entry.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun saveTranscript(id: Long, transcript: String) {
        val entry = dao.getById(id) ?: return
        update(entry.copy(transcriptText = transcript))
    }

    suspend fun saveDiaryText(id: Long, diaryText: String) {
        val entry = dao.getById(id) ?: return
        update(entry.copy(diaryText = diaryText))
    }

    suspend fun deleteAudio(id: Long) {
        val entry = dao.getById(id) ?: return
        entry.audioPath?.let { path ->
            File(path).takeIf { it.exists() }?.delete()
        }
        update(entry.copy(audioPath = null))
    }

    suspend fun deleteTranscript(id: Long) {
        val entry = dao.getById(id) ?: return
        update(entry.copy(transcriptText = null))
    }

    suspend fun deleteDiaryText(id: Long) {
        val entry = dao.getById(id) ?: return
        update(entry.copy(diaryText = null))
    }

    suspend fun deleteEntry(id: Long) {
        val entry = dao.getById(id) ?: return
        entry.audioPath?.let { File(it).takeIf { f -> f.exists() }?.delete() }
        dao.deleteById(id)
    }

    suspend fun deleteEntries(ids: List<Long>) {
        ids.forEach { id ->
            val entry = dao.getById(id) ?: return@forEach
            entry.audioPath?.let { File(it).takeIf { f -> f.exists() }?.delete() }
        }
        dao.deleteByIds(ids)
    }

    fun newRecordingFile(): File = File(recordingsDir, "rec_${System.currentTimeMillis()}.wav")

    suspend fun neighborIds(currentId: Long): Pair<Long?, Long?> {
        val all = dao.getAllOrdered()
        val index = all.indexOfFirst { it.id == currentId }
        if (index < 0) return null to null
        val prev = all.getOrNull(index + 1)?.id
        val next = all.getOrNull(index - 1)?.id
        return prev to next
    }
}
