package com.snainfotech.tagscout.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.snainfotech.tagscout.data.entities.InventoryScanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryScanDao {

    @Insert
    suspend fun insertInventoryScan(scan: InventoryScanEntity): Long

    @Query("SELECT * FROM inventory_scans ORDER BY timestamp DESC")
    fun getAllInventoryScans(): Flow<List<InventoryScanEntity>>

    @Query("SELECT * FROM inventory_scans ORDER BY timestamp DESC LIMIT 10")
    fun getRecentScans(): Flow<List<InventoryScanEntity>>

    @Query("SELECT * FROM inventory_scans WHERE id = :scanId")
    suspend fun getInventoryScanById(scanId: Long): InventoryScanEntity?

    @Delete
    suspend fun deleteInventoryScan(scan: InventoryScanEntity)

    @Query("DELETE FROM inventory_scans WHERE timestamp < :timeInMillis")
    suspend fun deleteScansOlderThan(timeInMillis: Long)
}