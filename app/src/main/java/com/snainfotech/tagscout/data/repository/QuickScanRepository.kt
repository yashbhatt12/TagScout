package com.snainfotech.tagscout.data.repository

import com.snainfotech.tagscout.data.dao.QuickScanDao
import com.snainfotech.tagscout.data.entities.QuickScanEntity
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

class QuickScanRepository(private val quickScanDao: QuickScanDao) {

    fun getAllScans(): Flow<List<QuickScanEntity>> {
        return quickScanDao.getAllQuickScans()
    }

    fun getRecentScans(): Flow<List<QuickScanEntity>> {
        return quickScanDao.getRecentScans()
    }

    suspend fun saveScan(
        filename: String,
        uniqueTagsFound: Int,
        totalTagsScanned: Int,
        maxReadPerSecond: Float,
        durationSeconds: Int,
        antennaStrength: Int
    ): Long {
        val scan = QuickScanEntity(
            timestamp = System.currentTimeMillis(),
            filename = filename,
            uniqueTagsFound = uniqueTagsFound,
            totalTagsScanned = totalTagsScanned,
            maxReadPerSecond = maxReadPerSecond,
            durationSeconds = durationSeconds,
            antennaStrength = antennaStrength
        )
        return quickScanDao.insertQuickScan(scan)
    }

    suspend fun deleteScan(scan: QuickScanEntity) {
        quickScanDao.deleteQuickScan(scan)
    }

    suspend fun deleteOldScans() {
        val ninetyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90)
        quickScanDao.deleteScansOlderThan(ninetyDaysAgo)
    }
}