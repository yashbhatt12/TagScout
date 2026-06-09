package com.snainfotech.tagscout.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.snainfotech.tagscout.data.dao.DeviceConnectionDao
import com.snainfotech.tagscout.data.dao.InventoryScanDao
import com.snainfotech.tagscout.data.dao.QuickScanDao
import com.snainfotech.tagscout.data.entities.DeviceConnectionEntity
import com.snainfotech.tagscout.data.entities.InventoryScanEntity
import com.snainfotech.tagscout.data.entities.QuickScanEntity

@Database(
    entities = [
        QuickScanEntity::class,
        InventoryScanEntity::class,
        DeviceConnectionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun quickScanDao(): QuickScanDao
    abstract fun inventoryScanDao(): InventoryScanDao
    abstract fun deviceConnectionDao(): DeviceConnectionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tagscout_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}