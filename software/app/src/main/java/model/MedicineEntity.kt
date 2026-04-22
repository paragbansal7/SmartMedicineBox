package com.example.smartmedicinebox.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "medicines")
data class MedicineEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val time: String,
    val quantity: String,
    val category: String,
    val compartmentNumber: Int,
    val status: String = "PENDING",
    val currentStock: Int = 0        // ← NEW
) {
    fun toMedicine(): Medicine {
        return Medicine(
            id                = id,
            name              = name,
            time              = time,
            quantity          = quantity,
            category          = Medicine.Category.valueOf(category),
            compartmentNumber = compartmentNumber,
            status            = Medicine.Status.valueOf(status),
            currentStock      = currentStock   // ← NEW
        )
    }
}

fun Medicine.toEntity(): MedicineEntity {
    return MedicineEntity(
        id                = id,
        name              = name,
        time              = time,
        quantity          = quantity,
        category          = category.name,
        compartmentNumber = compartmentNumber,
        status            = status.name,
        currentStock      = currentStock       // ← NEW
    )
}