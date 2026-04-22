package com.example.smartmedicinebox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.smartmedicinebox.database.MedicineDatabase

class MedicineAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_MEDICINE_ID   = "medicine_id"
        const val EXTRA_MEDICINE_NAME = "medicine_name"
        const val CHANNEL_ID          = "medicine_channel"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val medicineId   = intent.getIntExtra(EXTRA_MEDICINE_ID, -1)
        val medicineName = intent.getStringExtra(EXTRA_MEDICINE_NAME) ?: "Medicine"

        if (medicineId == -1) return

        CoroutineScope(Dispatchers.IO).launch {
            val db       = MedicineDatabase.getDatabase(context)
            val entity   = db.medicineDao().getMedicineById(medicineId) ?: return@launch

            // ── 1. Reset status to PENDING for today's dose ──────
            db.medicineDao().updateStatus(medicineId, "PENDING")

            // ── 2. Reschedule alarm for TOMORROW (makes it permanent/daily) ──
            withContext(Dispatchers.Main) {
                AlarmScheduler.scheduleMedicineAlarm(context, entity.toMedicine())
            }

            // ── 3. Fire notification ─────────────────────────────
            withContext(Dispatchers.Main) {
                showNotification(context, medicineId, medicineName)
            }
        }
    }

    private fun showNotification(context: Context, id: Int, name: String) {
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("💊 Time to take your medicine!")
            .setContentText("$name — open the box now")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — silent fail
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Medicine Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Daily medicine alarm notifications" }
            (context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager).createNotificationChannel(channel)
        }
    }

}