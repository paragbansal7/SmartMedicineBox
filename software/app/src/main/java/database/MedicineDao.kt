package com.example.smartmedicinebox.database

import androidx.room.*
import com.example.smartmedicinebox.model.MedicineEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicineDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedicine(medicine: MedicineEntity)

    @Update
    suspend fun updateMedicine(medicine: MedicineEntity)

    @Delete
    suspend fun deleteMedicine(medicine: MedicineEntity)

    @Query("SELECT * FROM medicines ORDER BY time ASC")
    fun getAllMedicines(): Flow<List<MedicineEntity>>

    @Query("SELECT * FROM medicines ORDER BY time ASC")
    suspend fun getAllMedicinesOnce(): List<MedicineEntity>

    @Query("SELECT * FROM medicines WHERE id = :id")
    suspend fun getMedicineById(id: Int): MedicineEntity?

    @Query("UPDATE medicines SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Int, status: String)

    @Query("SELECT * FROM medicines WHERE time = :time AND status = 'PENDING'")
    suspend fun getPendingMedicinesAtTime(time: String): List<MedicineEntity>

    // ── NEW: Decrement stock by a specific amount, minimum 0 ──────────────
    @Query("""
        UPDATE medicines 
        SET currentStock = MAX(0, currentStock - :amount) 
        WHERE id = :id
    """)
    suspend fun decrementStockBy(id: Int, amount: Int)

    // ── NEW: Get current stock for a medicine ─────────────
    @Query("SELECT currentStock FROM medicines WHERE id = :id")
    suspend fun getStock(id: Int): Int

    // ── NEW: Update stock directly ────────────────────────
    @Query("UPDATE medicines SET currentStock = :stock WHERE id = :id")
    suspend fun updateStock(id: Int, stock: Int)
}