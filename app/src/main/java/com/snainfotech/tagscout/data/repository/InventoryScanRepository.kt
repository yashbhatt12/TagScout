package com.snainfotech.tagscout.data.repository

import com.snainfotech.tagscout.data.dao.InventoryScanDao
import com.snainfotech.tagscout.data.entities.InventoryScanEntity
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

class InventoryScanRepository(private val inventoryScanDao: InventoryScanDao) {

    fun getAllScans(): Flow<List<InventoryScanEntity>> {
        return inventoryScanDao.getAllInventoryScans()
    }

    fun getRecentScans(): Flow<List<InventoryScanEntity>> {
        return inventoryScanDao.getRecentScans()
    }

    suspend fun saveScan(
        uploadedFilename: String,
        resultFilename: String,
        expectedItems: Int,
        foundItems: Int,
        missingItems: Int,
        matchPercentage: Float
    ): Long {
        val scan = InventoryScanEntity(
            timestamp = System.currentTimeMillis(),
            uploadedFilename = uploadedFilename,
            filename = resultFilename,
            expectedItems = expectedItems,
            foundItems = foundItems,
            missingItems = missingItems,
            matchPercentage = matchPercentage
        )
        return inventoryScanDao.insertInventoryScan(scan)
    }

    suspend fun deleteScan(scan: InventoryScanEntity) {
        inventoryScanDao.deleteInventoryScan(scan)
    }

    suspend fun deleteOldScans() {
        val ninetyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90)
        inventoryScanDao.deleteScansOlderThan(ninetyDaysAgo)
    }
}