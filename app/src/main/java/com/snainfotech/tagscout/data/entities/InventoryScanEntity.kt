package com.snainfotech.tagscout.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inventory_scans")
data class InventoryScanEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val timestamp: Long,
    val filename: String,
    val uploadedFilename: String,
    val expectedItems: Int,
    val foundItems: Int,
    val missingItems: Int,
    val matchPercentage: Float,
    val notes: String? = null
)