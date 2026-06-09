package com.snainfotech.tagscout.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_connections")
data class DeviceConnectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val deviceId: String,
    val deviceName: String,
    val serialNumber: String,
    val firmwareVersion: String,
    val timestamp: Long,
    val connectionDuration: Int,
    val isSaved: Boolean = false
)