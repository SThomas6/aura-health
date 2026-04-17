package com.example.mob_dev_portfolio.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SymptomLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SymptomLogEntity): Long

    @Query("SELECT * FROM symptom_logs ORDER BY startEpochMillis DESC")
    fun observeAll(): Flow<List<SymptomLogEntity>>

    @Query("SELECT COUNT(*) FROM symptom_logs")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM symptom_logs WHERE id = :id")
    suspend fun delete(id: Long)
}
