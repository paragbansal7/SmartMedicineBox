package com.example.smartmedicinebox.model

data class Medicine(
    val id: Int = 0,
    val name: String,
    val time: String,
    val quantity: String,
    val category: Category,
    val compartmentNumber: Int,
    var status: Status = Status.PENDING,
    var currentStock: Int = 0           // ← NEW
) {
    enum class Category {
        TABLET, SYRUP, CAPSULE
    }

    enum class Status {
        TAKEN, PENDING, MISSED
    }
}