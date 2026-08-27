package com.dafei.voicenotes.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "diary_entries")
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val audioPath: String?,
    val transcriptText: String?,
    val diaryText: String?,
    val durationMs: Long = 0
)
