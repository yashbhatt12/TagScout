package com.snainfotech.tagscout.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.snainfotech.tagscout.data.entities.DeviceConnectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceConnectionDao {

    @Insert
    suspend fun insertConnection(connection: DeviceConnectionEntity): Long

    @Query("SELECT * FROM device_connections ORDER BY timestamp DESC")
    fun getAllConnections(): Flow<List<DeviceConnectionEntity>>

    @Query("SELECT * FROM device_connections WHERE isSaved = 1 ORDER BY timestamp DESC")
    fun getSavedDevices(): Flow<List<DeviceConnectionEntity>>

    @Query("SELECT * FROM device_connections WHERE deviceId = :deviceId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestConnectionForDevice(deviceId: String): DeviceConnectionEntity?

    @Update
    suspend fun updateConnection(connection: DeviceConnectionEntity)

    @Delete
    suspend fun deleteConnection(connection: DeviceConnectionEntity)
}