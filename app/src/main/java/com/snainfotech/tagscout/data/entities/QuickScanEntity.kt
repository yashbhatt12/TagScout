package com.snainfotech.tagscout.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "quick_scans")
data class QuickScanEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val timestamp: Long,
    val filename: String,
    val uniqueTagsFound: Int,
    val totalTagsScanned: Int,
    val maxReadPerSecond: Float,
    val durationSeconds: Int,
    val antennaStrength: Int,
    val notes: String? = null
)