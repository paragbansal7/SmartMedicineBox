package com.example.smartmedicinebox

import android.content.Context
import android.util.Log
import com.example.smartmedicinebox.database.MedicineDatabase
import com.example.smartmedicinebox.model.MedicineHistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

object SyncManager {

    private const val TAG = "SyncManager"

    // Accumulates LOG: lines from ESP32 during a sync session
    private val pendingLogs = mutableListOf<String>()
    private var isSyncing   = false

    fun startSync(bleManager: BleManager) {
        if (isSyncing) return
        isSyncing = true
        pendingLogs.clear()
        Log.d(TAG, "Sending SYNC_LOGS to ESP32...")
        bleManager.requestOfflineSync()
    }

    /**
     * Called from MainActivity.handleEsp32Message() for every
     * message that starts with "LOG:"
     *
     * Returns true if this message was handled by SyncManager.
     */
    fun handleLogMessage(
        msg: String,
        context: Context,
        bleManager: BleManager,
        scope: CoroutineScope,
        onSyncComplete: (Int) -> Unit   // callback with count of synced entries
    ): Boolean {

        return when {

            // ── Individual log entry ───────────────────────
            // FORMAT: LOG:LED1,1,TAKEN,2026-03-22T14:30:00
            msg.startsWith("LOG:") && !msg.startsWith("LOG:END")
                    && !msg.startsWith("LOG:EMPTY") -> {

                val payload = msg.removePrefix("LOG:")
                Log.d(TAG, "Received log entry: $payload")
                pendingLogs.add(payload)
                true
            }

            // ── No offline logs on ESP32 ───────────────────
            msg == "LOG:EMPTY" -> {
                Log.d(TAG, "ESP32 has no offline history")
                isSyncing = false
                onSyncComplete(0)
                true
            }

            // ── All logs received — save to Room DB ────────
            msg == "LOG:END" -> {
                Log.d(TAG, "LOG:END received — saving ${pendingLogs.size} entries")

                scope.launch {
                    val savedCount = saveLogsToDb(context, pendingLogs.toList())

                    // Tell ESP32 to clear its history.json
                    delay(200)
                    bleManager.confirmLogsClear()

                    isSyncing = false
                    pendingLogs.clear()
                    onSyncComplete(savedCount)
                }
                true
            }

            // ── ESP32 confirmed logs cleared ───────────────
            msg == "ACK:LOGS_CLEARED" -> {
                Log.d(TAG, "ESP32 confirmed history.json cleared")
                true
            }

            else -> false
        }
    }

    /**
     * Parses each LOG entry and saves to Room medicine_history table.
     * FORMAT: LED1,1,TAKEN,2026-03-22T14:30:00
     * Returns count of successfully saved entries.
     */
    private suspend fun saveLogsToDb(
        context: Context,
        logs: List<String>
    ): Int = withContext(Dispatchers.IO) {

        val db          = MedicineDatabase.getDatabase(context)
        val medicineDao = db.medicineDao()
        val historyDao  = db.historyDao()
        var savedCount  = 0

        for (log in logs) {
            try {
                // Parse: LED1,1,TAKEN,2026-03-22T14:30:00
                val parts = log.split(",")
                if (parts.size < 4) {
                    Log.w(TAG, "Skipping malformed log: $log")
                    continue
                }

                val ledKey    = parts[0].trim()
                val slot      = parts[1].trim().toIntOrNull() ?: continue
                val status    = parts[2].trim()
                val timestamp = parts[3].trim()

                // Find the medicine in this compartment
                val medicine  = medicineDao.getAllMedicinesOnce()
                    .firstOrNull { it.compartmentNumber == slot }

                val epochMs   = parseTimestamp(timestamp)
                val dateStr   = timestamp.split("T")
                    .firstOrNull() ?: "0000-00-00"

                // Save to history table
                val historyEntity = MedicineHistoryEntity(
                    medicineId        = medicine?.id ?: -1,
                    medicineName      = medicine?.name ?: "Box $slot",
                    compartmentNumber = slot,
                    category          = medicine?.category ?: "TABLET",
                    scheduledTime     = medicine?.time ?: "00:00",
                    status            = status,
                    timestamp         = epochMs,
                    date              = dateStr
                )
                historyDao.insertHistory(historyEntity)

                // ── Stock and status updates for TAKEN ────────────
                if (status == "TAKEN" && medicine != null) {

                    // Update DB status if still PENDING
                    if (medicine.status == "PENDING") {
                        medicineDao.updateStatus(medicine.id, "TAKEN")
                    }

                    // Extract dose and apply stock math rules
                    val oldStock   = medicine.currentStock
                    val doseAmount = extractDoseAmount(medicine.quantity)

                    when {
                        // Rule 1: Enough stock — deduct full dose
                        oldStock >= doseAmount -> {
                            medicineDao.decrementStockBy(
                                medicine.id, doseAmount)
                            Log.d(TAG,
                                "Stock: ${medicine.name} " +
                                        "$oldStock → ${oldStock - doseAmount}")
                        }
                        // Rule 2: Partial stock — drain to exactly 0
                        oldStock > 0 -> {
                            medicineDao.decrementStockBy(
                                medicine.id, oldStock)
                            Log.d(TAG,
                                "Partial stock drained: " +
                                        "${medicine.name} $oldStock → 0")
                        }
                        // Rule 3: Already 0 — nothing to decrement
                        else -> {
                            Log.d(TAG,
                                "Stock already 0 for ${medicine.name}" +
                                        " — skipping decrement")
                        }
                    }
                }

                // ── Status update for MISSED ──────────────────────
                if (status == "MISSED" && medicine != null
                    && medicine.status == "PENDING") {
                    medicineDao.updateStatus(medicine.id, "MISSED")
                    Log.d(TAG, "Marked MISSED: ${medicine.name}")
                }

                savedCount++
                Log.d(TAG, "Saved offline log: $log")

            } catch (e: Exception) {
                Log.e(TAG, "Error saving log entry '$log': ${e.message}")
            }
        }

        Log.d(TAG, "Sync complete: $savedCount / ${logs.size} saved")
        savedCount
    }

    /**
     * Extracts the numeric dose from a quantity string.
     * "5 ml" → 5 | "2 tablet(s)" → 2 | fallback → 1
     */
    private fun extractDoseAmount(quantity: String): Int {
        return try {
            quantity.trim()
                .split(" ")
                .firstOrNull()
                ?.toDoubleOrNull()
                ?.toInt()
                ?.coerceAtLeast(1)
                ?: 1
        } catch (e: Exception) {
            1
        }
    }

    /**
     * Parses "2026-03-22T14:30:00" → epoch milliseconds.
     */
    private fun parseTimestamp(ts: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            sdf.parse(ts)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}