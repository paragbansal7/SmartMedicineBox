package com.example.smartmedicinebox

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.smartmedicinebox.database.MedicineDatabase
import com.example.smartmedicinebox.databinding.ActivityMainBinding
import com.example.smartmedicinebox.model.Medicine
import com.example.smartmedicinebox.model.MedicineEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private val medicineList: MutableList<Medicine> = mutableListOf()
    private val missedTimers = mutableMapOf<Int, Runnable>()

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderCompartments()
            updateNextMedicineBanner()
            handler.postDelayed(this, 60_000)
        }
    }

    private val compartmentIds = listOf(
        R.id.comp1, R.id.comp2, R.id.comp3,
        R.id.comp4, R.id.comp5, R.id.comp6
    )

    // ── LIFECYCLE ─────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnHistory.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        setupBle()
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        loadMedicines()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    // ── MISSED TIMER ──────────────────────────────────────────
    private fun startMissedTimer(medicine: Medicine) {
        missedTimers[medicine.id]?.let { handler.removeCallbacks(it) }

        val runnable = Runnable {
            lifecycleScope.launch {
                val db = MedicineDatabase.getDatabase(this@MainActivity)
                val current = withContext(Dispatchers.IO) {
                    db.medicineDao().getMedicineById(medicine.id)
                }
                if (current?.status == "PENDING") {
                    withContext(Dispatchers.IO) {
                        db.medicineDao().updateStatus(medicine.id, "MISSED")
                    }
                    saveHistory(medicine, "MISSED")
                    loadMedicines()
                    Toast.makeText(
                        this@MainActivity,
                        "⚠️ ${medicine.name} was MISSED!",
                        Toast.LENGTH_LONG
                    ).show()
                }
                missedTimers.remove(medicine.id)
            }
        }

        handler.postDelayed(runnable, 5 * 60 * 1000L)
        missedTimers[medicine.id] = runnable
    }

    private fun cancelMissedTimer(medicineId: Int) {
        missedTimers[medicineId]?.let {
            handler.removeCallbacks(it)
            missedTimers.remove(medicineId)
        }
    }

    // ── SAVE HISTORY ──────────────────────────────────────────
    private suspend fun saveHistory(medicine: Medicine, status: String) {
        try {
            val db       = MedicineDatabase.getDatabase(this@MainActivity)
            val calendar = Calendar.getInstance()
            val dateStr  = String.format(
                Locale.getDefault(),
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            val history = com.example.smartmedicinebox.model.MedicineHistoryEntity(
                medicineId        = medicine.id,
                medicineName      = medicine.name,
                compartmentNumber = medicine.compartmentNumber,
                category          = medicine.category.name,
                scheduledTime     = medicine.time,
                status            = status,
                timestamp         = System.currentTimeMillis(),
                date              = dateStr
            )
            withContext(Dispatchers.IO) {
                db.historyDao().insertHistory(history)
            }
        } catch (e: Exception) {
            println("saveHistory error: ${e.message}")
        }
    }

    // ── BLE SETUP ─────────────────────────────────────────────
    private fun setupBle() {
        AppState.init(this)
        bleManager = AppState.getBleManager()!!

        bleManager.onConnectionChanged = { connected ->
            runOnUiThread { updateBleUI(connected) }
        }
        bleManager.onMessageReceived = { msg ->
            runOnUiThread { handleEsp32Message(msg) }
        }

        binding.tvBleStatus.setOnClickListener {
            if (bleManager.isConnected()) showDisconnectDialog()
            else startBleConnect()
        }
        binding.layoutBleBanner.setOnClickListener {
            if (!bleManager.isConnected()) startBleConnect()
        }

        updateBleUI(false)
    }

    @SuppressLint("MissingPermission")
    private fun startBleConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                != android.content.pm.PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.BLUETOOTH_SCAN,
                        android.Manifest.permission.BLUETOOTH_CONNECT,
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    ), 200
                )
                return
            }
        }
        Toast.makeText(this, "Scanning for MedBox...", Toast.LENGTH_SHORT).show()
        BleDeviceListDialog(this) { device ->
            bleManager.connect(device)
        }.show()
    }

    private fun showDisconnectDialog() {
        AlertDialog.Builder(this)
            .setTitle("Disconnect Hardware?")
            .setMessage("Disconnect from MedBox?")
            .setPositiveButton("Disconnect") { _, _ ->
                bleManager.disconnect()
                updateBleUI(false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── BLE UI ────────────────────────────────────────────────
    private fun updateBleUI(connected: Boolean) {
        val isDark = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        if (connected) {
            binding.tvBleStatus.text = "● Connected"
            binding.tvBleStatus.setTextColor(0xFFA5D6A7.toInt())
            binding.tvBleStatus.setBackgroundColor(0xFF1B5E20.toInt())

            binding.layoutBleBanner.setBackgroundColor(
                getColor(R.color.bleBannerConnectedBg))
            binding.tvBannerIcon.text  = "🔵"
            binding.tvBannerTitle.text = "HARDWARE CONNECTED"
            binding.tvBannerTitle.setTextColor(
                getColor(R.color.bleBannerConnectedTitle))
            binding.tvBannerDesc.text  = "MedBox · Tap to sync time"
            binding.tvBannerDesc.setTextColor(
                getColor(R.color.bleBannerConnectedDesc))

            binding.layoutBleBanner.setOnClickListener {
                bleManager.syncTime()
                Toast.makeText(this,
                    "✅ Time synced to ESP32!", Toast.LENGTH_SHORT).show()
            }

            bleManager.syncTime()
            resendAllAlarms()

            handler.postDelayed({
                if (bleManager.isConnected()) {
                    SyncManager.startSync(bleManager)
                }
            }, 2000)

        } else {
            binding.tvBleStatus.text = "● Connect"
            binding.tvBleStatus.setTextColor(0xFFEF9A9A.toInt())
            binding.tvBleStatus.setBackgroundColor(
                if (isDark) 0x33EF9A9A.toInt() else 0x26FFFFFF.toInt())

            binding.layoutBleBanner.setBackgroundColor(
                getColor(R.color.bleBannerDisconnectedBg))
            binding.tvBannerIcon.text  = "⚠️"
            binding.tvBannerTitle.text = "HARDWARE NOT CONNECTED"
            binding.tvBannerTitle.setTextColor(
                getColor(R.color.bleBannerDisconnectedTitle))
            binding.tvBannerDesc.text  = "Tap Connect above to pair ESP32"
            binding.tvBannerDesc.setTextColor(
                getColor(R.color.bleBannerDisconnectedDesc))

            binding.layoutBleBanner.setOnClickListener { startBleConnect() }
        }
    }

    // ── ESP32 MESSAGE HANDLER ─────────────────────────────────
    private fun handleEsp32Message(msg: String) {
        val handledBySync = SyncManager.handleLogMessage(
            msg        = msg,
            context    = this,
            bleManager = bleManager,
            scope      = lifecycleScope,
            onSyncComplete = { count ->
                if (count > 0) {
                    Toast.makeText(
                        this,
                        "✅ Synced $count offline event(s) from ESP32!",
                        Toast.LENGTH_LONG
                    ).show()
                    loadMedicines()
                }
            }
        )
        if (handledBySync) return

        when {
            msg.startsWith("TAKEN:") -> {
                val slot = msg.removePrefix("TAKEN:")
                    .trim().removePrefix("LED").toIntOrNull()
                if (slot != null) markCompartmentTaken(slot)
            }
            msg.startsWith("MISSED:") -> {
                val slot = msg.removePrefix("MISSED:")
                    .trim().removePrefix("LED").toIntOrNull()
                if (slot != null) markCompartmentMissed(slot)
            }
            msg.startsWith("ALARM:") -> {
                val slot = msg.removePrefix("ALARM:")
                    .trim().removePrefix("LED").toIntOrNull()
                if (slot != null) {
                    lifecycleScope.launch {
                        val db = MedicineDatabase.getDatabase(this@MainActivity)
                        val medicine = withContext(Dispatchers.IO) {
                            db.medicineDao().getAllMedicinesOnce()
                                .firstOrNull {
                                    it.compartmentNumber == slot &&
                                            it.status == "PENDING"
                                }
                        }
                        medicine?.let {
                            startMissedTimer(it.toMedicine())
                            Toast.makeText(
                                this@MainActivity,
                                "⏰ Alarm: ${it.name} (Box $slot)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            msg.startsWith("ACK:TIME_SET") ->
                Toast.makeText(this,
                    "✅ Time synced to ESP32", Toast.LENGTH_SHORT).show()

            msg.startsWith("ACK:ALARM_SET") -> {
                val ledKey = msg.removePrefix("ACK:ALARM_SET:").trim()
                Toast.makeText(this,
                    "✅ Alarm set: $ledKey", Toast.LENGTH_SHORT).show()
            }
            msg.startsWith("ACK:CLEAR_ALL") ->
                Toast.makeText(this,
                    "🗑 All alarms cleared on ESP32", Toast.LENGTH_SHORT).show()

            msg.startsWith("TIME:") -> loadMedicines()

            msg.startsWith("ERR:") ->
                Toast.makeText(this,
                    "❌ ESP32 Error: $msg", Toast.LENGTH_SHORT).show()
        }
    }

    // ── MARK COMPARTMENT TAKEN ────────────────────────────────
    private fun markCompartmentTaken(slot: Int) {
        lifecycleScope.launch {
            try {
                val db = MedicineDatabase.getDatabase(this@MainActivity)

                val medicine = withContext(Dispatchers.IO) {
                    db.medicineDao().getAllMedicinesOnce()
                        .firstOrNull {
                            it.compartmentNumber == slot &&
                                    it.status == "PENDING"
                        }
                }

                if (medicine != null) {
                    val oldStock   = medicine.currentStock
                    val doseAmount = extractDoseAmount(medicine.quantity)

                    withContext(Dispatchers.IO) {
                        db.medicineDao().updateStatus(medicine.id, "TAKEN")

                        when {
                            // ── Rule 1: Enough stock for full dose ──
                            oldStock >= doseAmount -> {
                                db.medicineDao().decrementStockBy(
                                    medicine.id, doseAmount)
                            }
                            // ── Rule 2: Some stock left but less than dose ──
                            oldStock > 0 -> {
                                db.medicineDao().decrementStockBy(
                                    medicine.id, oldStock) // drain to exactly 0
                            }
                            // ── Rule 3: Already at 0 — no decrement needed ──
                            else -> { }
                        }
                    }

                    val newStock = withContext(Dispatchers.IO) {
                        db.medicineDao().getStock(medicine.id)
                    }

                    cancelMissedTimer(medicine.id)
                    saveHistory(medicine.toMedicine(), "TAKEN")
                    AlarmScheduler.cancelMedicineAlarm(
                        this@MainActivity, medicine.id)
                    rescheduleAlarmForTomorrow(medicine.toMedicine())
                    loadMedicines()

                    // ── Toast logic ───────────────────────────────
                    when {
                        // Had less stock than dose — partial dose warning
                        oldStock in 1 until doseAmount -> {
                            Toast.makeText(
                                this@MainActivity,
                                "⚠️ WARNING: Only $oldStock left of " +
                                        "${medicine.name}! Full dose not taken. " +
                                        "OUT OF STOCK!",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        // Got full dose but now out of stock
                        newStock == 0 -> {
                            Toast.makeText(
                                this@MainActivity,
                                "⚠️ ${medicine.name} is OUT OF STOCK! " +
                                        "Please refill.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        // Normal — stock remaining
                        else -> {
                            Toast.makeText(
                                this@MainActivity,
                                "✅ ${medicine.name} taken! " +
                                        "Stock left: $newStock",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                println("markCompartmentTaken error: ${e.message}")
            }
        }
    }

    /**
     * Extracts the numeric dose from a quantity string.
     * Examples:
     * "5 ml"        → 5
     * "2 tablet(s)" → 2
     * "1.5 ml"      → 1 (floor to Int)
     * "bad string"  → 1 (default)
     */
    private fun extractDoseAmount(quantity: String): Int {
        return try {
            quantity.trim()
                .split(" ")
                .firstOrNull()
                ?.toDoubleOrNull()
                ?.toInt()
                ?.coerceAtLeast(1)   // never return 0 or negative
                ?: 1
        } catch (e: Exception) {
            1  // safe default
        }
    }

    // ── MARK COMPARTMENT MISSED ───────────────────────────────
    private fun markCompartmentMissed(slot: Int) {
        lifecycleScope.launch {
            try {
                val db = MedicineDatabase.getDatabase(this@MainActivity)
                val medicine = withContext(Dispatchers.IO) {
                    db.medicineDao().getAllMedicinesOnce()
                        .firstOrNull {
                            it.compartmentNumber == slot &&
                                    it.status == "PENDING"
                        }
                }
                if (medicine != null) {
                    withContext(Dispatchers.IO) {
                        db.medicineDao().updateStatus(medicine.id, "MISSED")
                    }
                    cancelMissedTimer(medicine.id)
                    saveHistory(medicine.toMedicine(), "MISSED")
                    AlarmScheduler.cancelMedicineAlarm(
                        this@MainActivity, medicine.id)
                    loadMedicines()
                    Toast.makeText(
                        this@MainActivity,
                        "⚠️ ${medicine.name} was MISSED!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                println("markCompartmentMissed error: ${e.message}")
            }
        }
    }

    // ── RESEND ALL ALARMS TO ESP32 ────────────────────────────
    private fun resendAllAlarms() {
        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                MedicineDatabase.getDatabase(this@MainActivity)
                    .medicineDao().getAllMedicinesOnce()
                    .filter { it.status == "PENDING" }
            }
            pending.forEach { entity ->
                val timeParts = entity.time.split(":")
                if (timeParts.size == 2) {
                    bleManager.setAlarm(
                        slot   = entity.compartmentNumber,
                        hour   = timeParts[0].toInt(),
                        minute = timeParts[1].toInt()
                    )
                    delay(100)
                }
            }
            if (pending.isNotEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "${pending.size} alarm(s) synced to ESP32",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ── LOAD MEDICINES ────────────────────────────────────────
    private fun loadMedicines() {
        lifecycleScope.launch {
            val entities = withContext(Dispatchers.IO) {
                MedicineDatabase.getDatabase(this@MainActivity)
                    .medicineDao().getAllMedicinesOnce()
            }
            medicineList.clear()
            medicineList.addAll(entities.map { it.toMedicine() })
            renderCompartments()
            updateSummary()
            updateNextMedicineBanner()
        }
    }

    // ── RENDER COMPARTMENT GRID ───────────────────────────────
    private fun renderCompartments() {
        val medicineByCompartment =
            medicineList.associateBy { it.compartmentNumber }

        for (i in 1..6) {
            val frameLayout = findViewById<android.widget.FrameLayout>(
                compartmentIds[i - 1])
            frameLayout.removeAllViews()

            val view      = layoutInflater.inflate(
                R.layout.item_compartment, frameLayout, false)
            val tvBoxNum  = view.findViewById<TextView>(R.id.tvBoxNumber)
            val tvIcon    = view.findViewById<TextView>(R.id.tvCompIcon)
            val tvName    = view.findViewById<TextView>(R.id.tvCompName)
            val tvTime    = view.findViewById<TextView>(R.id.tvCompTime)
            val tvStock   = view.findViewById<TextView>(R.id.tvCompStock)

            tvBoxNum.text = "BOX $i"
            val medicine  = medicineByCompartment[i]
            val isDark    = resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES

            if (medicine == null) {
                val color = if (isDark) 0xFF616161.toInt()
                else 0xFFBDBDBD.toInt()
                tvIcon.text = "+"
                tvIcon.setTextColor(color)
                tvName.text = "Empty"
                tvName.setTextColor(color)
                tvTime.text = "Tap to add"
                tvTime.setTextColor(color)
                tvBoxNum.setTextColor(color)
                tvStock.visibility = View.GONE
                setCompBackground(view, "EMPTY")

                frameLayout.setOnClickListener { view ->
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    val intent = Intent(this, AddMedicineActivity::class.java)
                    intent.putExtra("COMPARTMENT_NUMBER", i)
                    startActivity(intent)
                }

            } else {
                tvIcon.text = when (medicine.category) {
                    Medicine.Category.TABLET  -> "💊"
                    Medicine.Category.SYRUP   -> "🧴"
                    Medicine.Category.CAPSULE -> "💉"
                }

                tvStock.visibility = View.VISIBLE
                val unitStr = when (medicine.category) {
                    Medicine.Category.SYRUP -> "ml left"
                    else -> "left"
                }
                tvStock.text = "${medicine.currentStock} $unitStr"

                if (medicine.currentStock == 0) {
                    tvStock.setTextColor(0xFFE53935.toInt())
                } else if (medicine.currentStock <= 3) {
                    tvStock.setTextColor(0xFFF57F17.toInt())
                } else {
                    tvStock.setTextColor(
                        if (isDark) 0xFF9E9E9E.toInt()
                        else 0xFF757575.toInt()
                    )
                }

                when (medicine.status) {
                    Medicine.Status.TAKEN -> {
                        tvName.text = medicine.name
                        tvIcon.setTextColor(0xFF388E3C.toInt())
                        tvName.setTextColor(0xFF1B5E20.toInt())
                        tvTime.text = "✓ Taken"
                        tvTime.setTextColor(0xFF388E3C.toInt())
                        tvBoxNum.setTextColor(0xFF2E7D32.toInt())
                        setCompBackground(view, "TAKEN")
                    }
                    Medicine.Status.PENDING -> {
                        tvName.text = if (medicine.currentStock == 0)
                            "⚠️ ${medicine.name}" else medicine.name
                        tvIcon.setTextColor(0xFF1565C0.toInt())
                        tvName.setTextColor(
                            if (medicine.currentStock == 0)
                                0xFFE53935.toInt()
                            else 0xFF0D47A1.toInt()
                        )
                        tvTime.text = getTimeLeft(medicine.time)
                        tvTime.setTextColor(0xFF1976D2.toInt())
                        tvBoxNum.setTextColor(0xFF1565C0.toInt())
                        setCompBackground(
                            view,
                            if (medicine.currentStock == 0) "MISSED"
                            else "PENDING"
                        )
                    }
                    Medicine.Status.MISSED -> {
                        tvName.text = medicine.name
                        tvIcon.setTextColor(0xFFF57F17.toInt())
                        tvName.setTextColor(0xFFE65100.toInt())
                        tvTime.text = "Missed"
                        tvTime.setTextColor(0xFFFF8F00.toInt())
                        tvBoxNum.setTextColor(0xFFF57F17.toInt())
                        setCompBackground(view, "MISSED")
                    }
                }

                frameLayout.setOnClickListener { view ->
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    showMedicineDialog(medicine)
                }
            }

            frameLayout.addView(view)
        }
    }

    // ── COMPARTMENT BACKGROUND ────────────────────────────────
    private fun setCompBackground(view: View, status: String) {
        val isDark = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val (bgColor, strokeColor) = when (status) {
            "TAKEN" ->
                (if (isDark) 0xFF1B3A1C.toInt() else 0xFFE8F5E9.toInt()) to
                        (if (isDark) 0xFF2E7D32.toInt() else 0xFFA5D6A7.toInt())
            "PENDING" ->
                (if (isDark) 0xFF0D2A4A.toInt() else 0xFFE3F2FD.toInt()) to
                        (if (isDark) 0xFF1565C0.toInt() else 0xFF90CAF9.toInt())
            "MISSED" ->
                (if (isDark) 0xFF3A2E00.toInt() else 0xFFFFFDE7.toInt()) to
                        (if (isDark) 0xFFF9A825.toInt() else 0xFFFFF176.toInt())
            else ->
                (if (isDark) 0xFF2A2A2A.toInt() else 0xFFF5F5F5.toInt()) to
                        (if (isDark) 0xFF424242.toInt() else 0xFFE0E0E0.toInt())
        }

        view.background =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                setStroke(4, strokeColor)
                cornerRadius = 36f
            }
    }

    // ── TIME LEFT STRING ──────────────────────────────────────
    private fun getTimeLeft(time: String): String {
        val parts = time.split(":")
        if (parts.size != 2) return time
        val now    = Calendar.getInstance()
        val medCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, parts[0].toInt())
            set(Calendar.MINUTE,      parts[1].toInt())
            set(Calendar.SECOND,      0)
        }
        val diff = medCal.timeInMillis - now.timeInMillis
        return when {
            diff <= 0      -> time
            diff < 3600000 -> "in ${diff / 60000}m"
            else           -> String.format(
                Locale.getDefault(), "in %dh %02dm",
                diff / 3600000, (diff % 3600000) / 60000
            )
        }
    }

    // ── MEDICINE DETAIL DIALOG ────────────────────────────────
    private fun showMedicineDialog(medicine: Medicine) {
        val dialogView = layoutInflater.inflate(
            R.layout.dialog_medicine_detail, null)

        val tvIcon        = dialogView.findViewById<TextView>(R.id.tvDialogIcon)
        val tvName        = dialogView.findViewById<TextView>(R.id.tvDialogName)
        val tvSubtitle    = dialogView.findViewById<TextView>(R.id.tvDialogSubtitle)
        val tvStatus      = dialogView.findViewById<TextView>(R.id.tvDialogStatus)
        val tvTime        = dialogView.findViewById<TextView>(R.id.tvDialogTime)
        val tvQuantity    = dialogView.findViewById<TextView>(R.id.tvDialogQuantity)
        val tvCompartment = dialogView.findViewById<TextView>(R.id.tvDialogCompartment)
        val tvTimeLeft    = dialogView.findViewById<TextView>(R.id.tvDialogTimeLeft)
        val tvStock       = dialogView.findViewById<TextView>(R.id.tvDialogStock)
        val tvStockWarn   = dialogView.findViewById<TextView>(R.id.tvStockWarning)
        val btnDelete     = dialogView.findViewById<LinearLayout>(R.id.btnDialogDelete)
        val btnMarkTaken  = dialogView.findViewById<LinearLayout>(R.id.btnDialogMarkTaken)
        val btnEdit       = dialogView.findViewById<LinearLayout>(R.id.btnDialogEdit)
        val btnClose      = dialogView.findViewById<LinearLayout>(R.id.btnDialogClose)

        // Fill data
        tvIcon.text = when (medicine.category) {
            Medicine.Category.TABLET  -> "💊"
            Medicine.Category.SYRUP   -> "🧴"
            Medicine.Category.CAPSULE -> "💉"
        }
        tvName.text = medicine.name
        tvSubtitle.text = "Box ${medicine.compartmentNumber} · " +
                medicine.category.name.lowercase()
                    .replaceFirstChar { it.uppercase() }
        tvTime.text        = medicine.time
        tvQuantity.text    = medicine.quantity
        tvCompartment.text = "Box ${medicine.compartmentNumber}"
        tvTimeLeft.text    = getTimeLeft(medicine.time)

        // Stock display
        tvStock.text = when (medicine.category) {
            Medicine.Category.TABLET  -> "${medicine.currentStock} tablet(s)"
            Medicine.Category.SYRUP   -> "${medicine.currentStock} ml"
            Medicine.Category.CAPSULE -> "${medicine.currentStock} capsule(s)"
        }
        when {
            medicine.currentStock == 0 -> {
                tvStockWarn.visibility = View.VISIBLE
                tvStockWarn.text = "⚠️ OUT OF STOCK"
                tvStock.setTextColor(0xFFE53935.toInt())
            }
            medicine.currentStock <= 3 -> {
                tvStockWarn.visibility = View.VISIBLE
                tvStockWarn.text = "⚠️ LOW STOCK"
                tvStock.setTextColor(0xFFF57F17.toInt())
            }
            else -> {
                tvStockWarn.visibility = View.GONE
                tvStock.setTextColor(getColor(R.color.inputText))
            }
        }

        // Status badge
        when (medicine.status) {
            Medicine.Status.TAKEN -> {
                tvStatus.text = "✔ Taken"
                tvStatus.setTextColor(0xFF2E7D32.toInt())
                tvStatus.background.setTint(0xFFE8F5E9.toInt())
                btnMarkTaken.isEnabled = false
                btnMarkTaken.alpha     = 0.4f
            }
            Medicine.Status.PENDING -> {
                tvStatus.text = "⏳ Pending"
                tvStatus.setTextColor(0xFFC62828.toInt())
                tvStatus.background.setTint(0xFFFFEBEE.toInt())
            }
            Medicine.Status.MISSED -> {
                tvStatus.text = "✗ Missed"
                tvStatus.setTextColor(0xFFE65100.toInt())
                tvStatus.background.setTint(0xFFFFF3E0.toInt())
            }
        }

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(
            this, R.style.ThemeOverlay_App_BottomSheetDialog)
        dialog.setContentView(dialogView)
        dialog.behavior.state =
            com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED

        // Delete
        btnDelete.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            dialog.dismiss()
            showDeleteDialog(medicine)
        }

        // Mark Taken
        btnMarkTaken.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) // <-- Haptic restored!
            if (medicine.status != Medicine.Status.TAKEN) {
                dialog.dismiss()
                lifecycleScope.launch {

                    val oldStock   = medicine.currentStock
                    val doseAmount = extractDoseAmount(medicine.quantity)

                    withContext(Dispatchers.IO) {
                        val dao = MedicineDatabase
                            .getDatabase(this@MainActivity).medicineDao()

                        dao.updateStatus(medicine.id, "TAKEN")

                        when {
                            // ── Rule 1: Enough stock for full dose ──
                            oldStock >= doseAmount -> {
                                dao.decrementStockBy(medicine.id, doseAmount)
                            }
                            // ── Rule 2: Partial stock remaining ──────
                            oldStock > 0 -> {
                                dao.decrementStockBy(medicine.id, oldStock)
                            }
                            // ── Rule 3: Already 0 — nothing to do ───
                            else -> { }
                        }
                    }

                    val newStock = withContext(Dispatchers.IO) {
                        MedicineDatabase.getDatabase(this@MainActivity)
                            .medicineDao().getStock(medicine.id)
                    }

                    cancelMissedTimer(medicine.id)
                    saveHistory(medicine, "TAKEN")
                    AlarmScheduler.cancelMedicineAlarm(
                        this@MainActivity, medicine.id)
                    rescheduleAlarmForTomorrow(medicine)

                    if (bleManager.isConnected()) {
                        bleManager.clearAlarm(medicine.compartmentNumber)
                    }

                    loadMedicines()

                    // ── Toast logic ───────────────────────────────────
                    when {
                        // Had less stock than dose — partial dose warning
                        oldStock in 1 until doseAmount -> {
                            Toast.makeText(
                                this@MainActivity,
                                "⚠️ WARNING: Only $oldStock left of " +
                                        "${medicine.name}! Full dose not taken. " +
                                        "OUT OF STOCK!",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        // Got full dose but now out of stock
                        newStock == 0 -> {
                            Toast.makeText(
                                this@MainActivity,
                                "⚠️ ${medicine.name} is OUT OF STOCK! " +
                                        "Please refill.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        // Normal — stock remaining
                        else -> {
                            Toast.makeText(
                                this@MainActivity,
                                "✅ ${medicine.name} taken! " +
                                        "Stock left: $newStock",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }

        // Edit
        btnEdit.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            dialog.dismiss()
            val intent = Intent(this, AddMedicineActivity::class.java).apply {
                putExtra("COMPARTMENT_NUMBER", medicine.compartmentNumber)
                putExtra("EDIT_MODE",          true)
                putExtra("MEDICINE_ID",        medicine.id)
                putExtra("MEDICINE_NAME",      medicine.name)
                putExtra("MEDICINE_TIME",      medicine.time)
                putExtra("MEDICINE_QUANTITY",  medicine.quantity)
                putExtra("MEDICINE_CATEGORY",  medicine.category.name)
                putExtra("MEDICINE_STOCK",     medicine.currentStock)
            }
            startActivity(intent)
        }

        btnClose.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
        }
        dialog.show()
    }

    // ── DELETE DIALOG ─────────────────────────────────────────
    private fun showDeleteDialog(medicine: Medicine) {
        AlertDialog.Builder(this)
            .setTitle("Delete Medicine")
            .setMessage("Remove '${medicine.name}' from " +
                    "Box ${medicine.compartmentNumber}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val entity = MedicineEntity(
                            id                = medicine.id,
                            name              = medicine.name,
                            time              = medicine.time,
                            quantity          = medicine.quantity,
                            category          = medicine.category.name,
                            compartmentNumber = medicine.compartmentNumber,
                            status            = medicine.status.name,
                            currentStock      = medicine.currentStock
                        )
                        MedicineDatabase.getDatabase(this@MainActivity)
                            .medicineDao().deleteMedicine(entity)
                    }
                    AlarmScheduler.cancelMedicineAlarm(
                        this@MainActivity, medicine.id)
                    if (bleManager.isConnected()) {
                        bleManager.clearAlarm(medicine.compartmentNumber)
                    }
                    loadMedicines()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── NEXT MEDICINE BANNER ──────────────────────────────────
    private fun updateNextMedicineBanner() {
        val now      = Calendar.getInstance()
        val upcoming = medicineList
            .filter { it.status == Medicine.Status.PENDING }
            .mapNotNull { med ->
                val parts = med.time.split(":")
                if (parts.size != 2) return@mapNotNull null
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                    set(Calendar.MINUTE,      parts[1].toInt())
                    set(Calendar.SECOND,      0)
                }
                val diff = cal.timeInMillis - now.timeInMillis
                if (diff > 0) Pair(med, diff) else null
            }
            .minByOrNull { it.second }

        if (upcoming != null) {
            val (med, diff) = upcoming
            val hours   = (diff / 3600000).toInt()
            val mins    = ((diff % 3600000) / 60000).toInt()
            val timeStr = if (hours > 0)
                String.format(Locale.getDefault(),
                    "in %dh %02dm", hours, mins)
            else "in ${mins}m"
            binding.tvNextMedicine.text =
                "${med.name} (Box ${med.compartmentNumber}) — $timeStr"
            binding.layoutNextMedicine.visibility = View.VISIBLE
        } else {
            binding.layoutNextMedicine.visibility = View.GONE
        }
    }

    // ── SUMMARY STATS ─────────────────────────────────────────
    private fun updateSummary() {
        binding.tvTotalMedicines.text =
            medicineList.size.toString()
        binding.tvTakenCount.text =
            medicineList.count { it.status == Medicine.Status.TAKEN }.toString()
        binding.tvPendingCount.text =
            medicineList.count { it.status == Medicine.Status.PENDING }.toString()
        binding.tvMissedCount.text =
            medicineList.count { it.status == Medicine.Status.MISSED }.toString()
    }

    // ── RESCHEDULE FOR TOMORROW ───────────────────────────────
    private fun rescheduleAlarmForTomorrow(medicine: Medicine) {
        AlarmScheduler.cancelMedicineAlarm(this, medicine.id)
        AlarmScheduler.scheduleMedicineAlarm(this, medicine)
    }

    // ── NOTIFICATION PERMISSION ───────────────────────────────
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }
}