package com.example.smartmedicinebox.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.smartmedicinebox.model.MedicineEntity
import com.example.smartmedicinebox.model.MedicineHistoryEntity

@Database(
    entities = [
        MedicineEntity::class,
        MedicineHistoryEntity::class
    ],
    version = 3,              // ← bumped from 2 → 3
    exportSchema = false
)
abstract class MedicineDatabase : RoomDatabase() {

    abstract fun medicineDao(): MedicineDao
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var INSTANCE: MedicineDatabase? = null

        // ── Migration 2 → 3 ──────────────────────────────
        // Adds currentStock column with default value 0
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE medicines ADD COLUMN currentStock INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getDatabase(context: Context): MedicineDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    MedicineDatabase::class.java,
                    "medicine_database"
                )
                    .addMigrations(MIGRATION_2_3)  // ← proper migration
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}