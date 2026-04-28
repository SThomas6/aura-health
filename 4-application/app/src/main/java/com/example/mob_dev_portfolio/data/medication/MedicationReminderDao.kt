package com.example.mob_dev_portfolio.data.medication

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationReminderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MedicationReminderEntity): Long

    @Update
    suspend fun update(entity: MedicationReminderEntity): Int

    @Query("DELETE FROM medication_reminders WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Ordering is by id ASC — chronological next-fire sorting has to
     * happen in the repository because the schedule layout depends on
     * the current wall clock, and encoding "next fire" in SQL would
     * force a per-query recompute on every insert/update.
     */
    @Query("SELECT * FROM medication_reminders ORDER BY id ASC")
    fun observeAll(): Flow<List<MedicationReminderEntity>>

    @Query("SELECT * FROM medication_reminders ORDER BY id ASC")
    suspend fun listAll(): List<MedicationReminderEntity>

    @Query("SELECT * FROM medication_reminders WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MedicationReminderEntity?

    @Query("SELECT * FROM medication_reminders WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<MedicationReminderEntity?>
}
