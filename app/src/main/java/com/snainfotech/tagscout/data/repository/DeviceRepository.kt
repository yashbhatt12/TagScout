package com.snainfotech.tagscout.data.repository

import com.snainfotech.tagscout.data.dao.DeviceConnectionDao
import com.snainfotech.tagscout.data.entities.DeviceConnectionEntity
import kotlinx.coroutines.flow.Flow

class DeviceRepository(private val deviceConnectionDao: DeviceConnectionDao) {

    fun getSavedDevices(): Flow<List<DeviceConnectionEntity>> {
        return deviceConnectionDao.getSavedDevices()
    }

    fun getAllConnections(): Flow<List<DeviceConnectionEntity>> {
        return deviceConnectionDao.getAllConnections()
    }

    suspend fun recordConnection(
        deviceId: String,
        deviceName: String,
        serialNumber: String,
        firmwareVersion: String,
        durationSeconds: Int = 0,
        isSaved: Boolean = false
    ): Long {
        val connection = DeviceConnectionEntity(
            timestamp = System.currentTimeMillis(),
            deviceId = deviceId,
            deviceName = deviceName,
            serialNumber = serialNumber,
            firmwareVersion = firmwareVersion,
            connectionDuration = durationSeconds,
            isSaved = isSaved
        )
        return deviceConnectionDao.insertConnection(connection)
    }

    suspend fun saveDevice(connection: DeviceConnectionEntity) {
        val updated = connection.copy(isSaved = true)
        deviceConnectionDao.updateConnection(updated)
    }

    suspend fun forgetDevice(connection: DeviceConnectionEntity) {
        deviceConnectionDao.deleteConnection(connection)
    }

    suspend fun getLatestConnectionForDevice(deviceId: String): DeviceConnectionEntity? {
        return deviceConnectionDao.getLatestConnectionForDevice(deviceId)
    }
}