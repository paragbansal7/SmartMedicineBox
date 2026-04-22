package com.example.smartmedicinebox.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.smartmedicinebox.model.MedicineHistoryEntity

@Dao
interface HistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: MedicineHistoryEntity)

    // Get all history ordered by newest first
    @Query("SELECT * FROM medicine_history ORDER BY timestamp DESC")
    suspend fun getAllHistory(): List<MedicineHistoryEntity>

    // Get history for last 7 days
    @Query("""
        SELECT * FROM medicine_history 
        WHERE timestamp >= :fromTimestamp 
        ORDER BY timestamp DESC
    """)
    suspend fun getHistoryFrom(fromTimestamp: Long): List<MedicineHistoryEntity>

    // Get history for a specific date
    @Query("SELECT * FROM medicine_history WHERE date = :date ORDER BY timestamp DESC")
    suspend fun getHistoryForDate(date: String): List<MedicineHistoryEntity>

    // Get history for specific medicine
    @Query("""
        SELECT * FROM medicine_history 
        WHERE medicineId = :medicineId 
        ORDER BY timestamp DESC
    """)
    suspend fun getHistoryForMedicine(medicineId: Int): List<MedicineHistoryEntity>

    // Count taken for a date
    @Query("SELECT COUNT(*) FROM medicine_history WHERE date = :date AND status = 'TAKEN'")
    suspend fun countTakenForDate(date: String): Int

    // Count missed for a date
    @Query("SELECT COUNT(*) FROM medicine_history WHERE date = :date AND status = 'MISSED'")
    suspend fun countMissedForDate(date: String): Int

    // Delete history older than 30 days
    @Query("DELETE FROM medicine_history WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOldHistory(cutoffTimestamp: Long)

    // Get all distinct dates that have history
    @Query("SELECT DISTINCT date FROM medicine_history ORDER BY date DESC")
    suspend fun getAllDates(): List<String>

    // Decrement stock by a specific amount, minimum 0
    @Query("""
    UPDATE medicines 
    SET currentStock = MAX(0, currentStock - :amount) 
    WHERE id = :id
""")
    suspend fun decrementStockBy(id: Int, amount: Int)
}