package com.snainfotech.tagscout.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.snainfotech.tagscout.data.entities.QuickScanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QuickScanDao {

    @Insert
    suspend fun insertQuickScan(scan: QuickScanEntity): Long

    @Query("SELECT * FROM quick_scans ORDER BY timestamp DESC")
    fun getAllQuickScans(): Flow<List<QuickScanEntity>>

    @Query("SELECT * FROM quick_scans ORDER BY timestamp DESC LIMIT 10")
    fun getRecentScans(): Flow<List<QuickScanEntity>>

    @Query("SELECT * FROM quick_scans WHERE id = :scanId")
    suspend fun getQuickScanById(scanId: Long): QuickScanEntity?

    @Delete
    suspend fun deleteQuickScan(scan: QuickScanEntity)

    @Query("DELETE FROM quick_scans WHERE timestamp < :timeInMillis")
    suspend fun deleteScansOlderThan(timeInMillis: Long)
}