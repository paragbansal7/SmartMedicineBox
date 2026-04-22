package com.example.smartmedicinebox

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import com.example.smartmedicinebox.database.MedicineDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MedicineTakenReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val medicineId = intent.getIntExtra(
            MedicineAlarmReceiver.EXTRA_MEDICINE_ID, -1
        )
        if (medicineId == -1) return

        // Update status to TAKEN in DB
        CoroutineScope(Dispatchers.IO).launch {
            val db = MedicineDatabase.getDatabase(context)
            db.medicineDao().updateStatus(medicineId, "TAKEN")
        }

        // Stop vibration
        stopVibration(context)

        // Dismiss notification
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(medicineId)
    }

    private fun stopVibration(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator.cancel()
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.cancel()
        }
    }
}