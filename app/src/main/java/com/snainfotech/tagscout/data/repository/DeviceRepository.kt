package com.snainfotech.tagscout.data.repository

import com.snainfotech.tagscout.data.dao.DeviceConnectionDao
import com.snainfotech.tagscout.data.entities.DeviceConnectionEntity
import kotlinx.coroutines.flow.Flow

class DeviceRepository(private val deviceConnectionDao: DeviceConnectionDao) {

    companion object {
        const val MAX_SAVED_DEVICES = 10
    }

    fun getSavedDevices(): Flow<List<DeviceConnectionEntity>> {
        return deviceConnectionDao.getSavedDevices()
    }

    fun getAllConnections(): Flow<List<DeviceConnectionEntity>> {
        return deviceConnectionDao.getAllConnections()
    }

    // Count saved devices (for the 10-device limit)
    suspend fun countSavedDevices(): Int {
        return deviceConnectionDao.countSavedDevices()
    }

    // Check if a device is already saved
    suspend fun isDeviceSaved(deviceId: String): Boolean {
        val existing = deviceConnectionDao.getLatestConnectionForDevice(deviceId)
        return existing != null && existing.isSaved
    }

    // Save a NEW device. Returns true on success, false if limit reached.
    suspend fun saveNewDevice(
        deviceId: String,
        deviceName: String,
        serialNumber: String,
        firmwareVersion: String
    ): Boolean {
        // Check if already saved
        val existing = deviceConnectionDao.getLatestConnectionForDevice(deviceId)
        if (existing != null && existing.isSaved) {
            // Already saved — just update last connection time
            val updated = existing.copy(timestamp = System.currentTimeMillis())
            deviceConnectionDao.updateConnection(updated)
            return true
        }

        // Check limit
        if (deviceConnectionDao.countSavedDevices() >= MAX_SAVED_DEVICES) {
            return false  // Limit reached
        }

        // Save new device
        val connection = DeviceConnectionEntity(
            timestamp = System.currentTimeMillis(),
            deviceId = deviceId,
            deviceName = deviceName,
            serialNumber = serialNumber,
            firmwareVersion = firmwareVersion,
            connectionDuration = 0,
            isSaved = true
        )
        deviceConnectionDao.insertConnection(connection)
        return true
    }

    // Existing method — kept for backward compatibility
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

    // Delete a saved device by ID
    suspend fun forgetDeviceById(deviceId: String) {
        val existing = deviceConnectionDao.getLatestConnectionForDevice(deviceId)
        if (existing != null) {
            deviceConnectionDao.deleteConnection(existing)
        }
    }

    suspend fun getLatestConnectionForDevice(deviceId: String): DeviceConnectionEntity? {
        return deviceConnectionDao.getLatestConnectionForDevice(deviceId)
    }
}