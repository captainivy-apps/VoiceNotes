package com.dafei.voicenotes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DiaryEntry>>

    @Query("SELECT * FROM diary_entries ORDER BY createdAt DESC")
    suspend fun getAllOrdered(): List<DiaryEntry>

    @Query("SELECT * FROM diary_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DiaryEntry?

    @Insert
    suspend fun insert(entry: DiaryEntry): Long

    @Update
    suspend fun update(entry: DiaryEntry)

    @Query("DELETE FROM diary_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM diary_entries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
