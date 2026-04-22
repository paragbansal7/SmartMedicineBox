package com.example.smartmedicinebox.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "medicine_history")
data class MedicineHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val medicineId: Int,
    val medicineName: String,
    val compartmentNumber: Int,
    val category: String,
    val scheduledTime: String,      // HH:MM
    val status: String,             // "TAKEN" or "MISSED"
    val timestamp: Long,            // System.currentTimeMillis()
    val date: String                // "2026-03-22" for grouping by day
)