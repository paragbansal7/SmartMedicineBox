package com.example.smartmedicinebox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.smartmedicinebox.database.MedicineDatabase

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Re-register all alarms after phone reboot
        CoroutineScope(Dispatchers.IO).launch {
            val medicines = MedicineDatabase.getDatabase(context)
                .medicineDao().getAllMedicinesOnce()
                .filter { it.status == "PENDING" }

            medicines.forEach { entity ->
                AlarmScheduler.scheduleMedicineAlarm(context, entity.toMedicine())
            }
        }
    }
}