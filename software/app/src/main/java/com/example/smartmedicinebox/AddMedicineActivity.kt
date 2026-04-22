package com.example.smartmedicinebox

import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.smartmedicinebox.database.MedicineDatabase
import com.example.smartmedicinebox.databinding.ActivityAddMedicineBinding
import com.example.smartmedicinebox.model.MedicineEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class AddMedicineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddMedicineBinding
    private var selectedHour: Int       = 8
    private var selectedMinute: Int     = 0
    private var compartmentNumber: Int  = 1
    private var bleManager: BleManager? = null

    // Edit mode state
    private var isEditMode: Boolean     = false
    private var editMedicineId: Int     = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddMedicineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ── Read intent extras ─────────────────────────────
        compartmentNumber = intent.getIntExtra("COMPARTMENT_NUMBER", 1)
        isEditMode        = intent.getBooleanExtra("EDIT_MODE", false)
        editMedicineId    = intent.getIntExtra("MEDICINE_ID", -1)

        // ── Shared BLE instance ────────────────────────────
        bleManager = AppState.getBleManager()

        // ── Setup UI ───────────────────────────────────────
        setupToolbar()
        setupTimePicker()
        setupCategorySpinner()
        setupQuantityChips()
        setupStockChips()
        setupSaveButton()

        // ── Apply edit data AFTER UI is ready ──────────────
        if (isEditMode) {
            applyEditMode()
        } else {
            // Normal add mode — show compartment badge
            binding.tvCompartmentBadge.text = "📦 Compartment $compartmentNumber"
        }
    }

    // ── TOOLBAR ───────────────────────────────────────────────
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Title changes based on mode
        supportActionBar?.title = if (isEditMode) "Edit Medicine" else "Add Medicine"
    }

    // ── TIME PICKER ───────────────────────────────────────────
    private fun setupTimePicker() {
        val calendar   = Calendar.getInstance()
        selectedHour   = calendar.get(Calendar.HOUR_OF_DAY)
        selectedMinute = calendar.get(Calendar.MINUTE)
        updateTimeDisplay()

        binding.btnPickTime.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hour, minute ->
                    selectedHour   = hour
                    selectedMinute = minute
                    updateTimeDisplay()
                },
                selectedHour,
                selectedMinute,
                true
            ).show()
        }
    }

    private fun setupStockChips() {
        listOf(
            binding.stockChip7  to "7",
            binding.stockChip14 to "14",
            binding.stockChip30 to "30",
            binding.stockChip60 to "60"
        ).forEach { (chip, value) ->
            chip.setOnClickListener {
                binding.etStock.setText(value)
            }
        }
    }

    private fun updateTimeDisplay() {
        binding.tvSelectedTime.text = String.format(
            Locale.getDefault(),
            "%02d:%02d",
            selectedHour,
            selectedMinute
        )
    }

    // ── CATEGORY SPINNER ──────────────────────────────────────
    private fun setupCategorySpinner() {
        val categories = listOf("Tablet 💊", "Syrup 🧴", "Capsule 💉")
        val adapter    = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            categories
        )
        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCategory.adapter = adapter

        binding.spinnerCategory.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    updateQuantityUI(position)
                }
                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
    }

    // ── QUANTITY UI ───────────────────────────────────────────
    private fun updateQuantityUI(categoryPosition: Int) {
        when (categoryPosition) {
            0 -> {
                binding.tvQuantityLabel.text = "Quantity (tablets) *"
                binding.tvUnit.text          = "tablet(s)"
                binding.etQuantity.hint      = "e.g. 1"
                binding.etQuantity.inputType =
                    android.text.InputType.TYPE_CLASS_NUMBER
                binding.chip1.text = "1"
                binding.chip2.text = "2"
                binding.chip3.text = "5"
                binding.chip4.text = "10"
                binding.chip5.text = "15"
                binding.tvStockUnit.text = "tablets"
            }
            1 -> {
                binding.tvQuantityLabel.text = "Quantity (ml) *"
                binding.tvUnit.text          = "ml"
                binding.etQuantity.hint      = "e.g. 5"
                binding.etQuantity.inputType =
                    android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                binding.chip1.text = "2.5"
                binding.chip2.text = "5"
                binding.chip3.text = "10"
                binding.chip4.text = "15"
                binding.chip5.text = "20"
                binding.tvStockUnit.text = "ml"
            }
            2 -> {
                binding.tvQuantityLabel.text = "Quantity (capsules) *"
                binding.tvUnit.text          = "capsule(s)"
                binding.etQuantity.hint      = "e.g. 1"
                binding.etQuantity.inputType =
                    android.text.InputType.TYPE_CLASS_NUMBER
                binding.chip1.text = "1"
                binding.chip2.text = "2"
                binding.chip3.text = "3"
                binding.chip4.text = "4"
                binding.chip5.text = "5"
                binding.tvStockUnit.text = "capsules"
            }
        }
        // Only clear quantity if user changed category manually
        // Don't clear during edit mode pre-fill
        if (!isEditMode) {
            binding.etQuantity.setText("")
        }
    }

    // ── QUANTITY CHIPS ────────────────────────────────────────
    private fun setupQuantityChips() {
        listOf(
            binding.chip1,
            binding.chip2,
            binding.chip3,
            binding.chip4,
            binding.chip5
        ).forEach { chip ->
            chip.setOnClickListener {
                binding.etQuantity.setText(chip.text)
            }
        }
    }

    // ── SAVE BUTTON ───────────────────────────────────────────
    private fun setupSaveButton() {
        // Button text changes based on mode
        binding.btnSaveMedicine.text =
            if (isEditMode) "UPDATE MEDICINE" else "SAVE MEDICINE"

        binding.btnSaveMedicine.setOnClickListener {
            if (validateForm()) saveMedicine()
        }
    }

    // ── APPLY EDIT MODE ───────────────────────────────────────
    // Called AFTER all UI is set up so spinners are ready
    private fun applyEditMode() {
        val editName     = intent.getStringExtra("MEDICINE_NAME")     ?: ""
        val editTime     = intent.getStringExtra("MEDICINE_TIME")     ?: ""
        val editQuantity = intent.getStringExtra("MEDICINE_QUANTITY") ?: ""
        val editCategory = intent.getStringExtra("MEDICINE_CATEGORY") ?: "TABLET"

        // Compartment badge — show which box is being edited
        binding.tvCompartmentBadge.text = "✏️ Editing Compartment $compartmentNumber"

        // Fill name
        binding.etMedicineName.setText(editName)

        // Fill time
        val timeParts = editTime.split(":")
        if (timeParts.size == 2) {
            selectedHour   = timeParts[0].toIntOrNull() ?: 8
            selectedMinute = timeParts[1].toIntOrNull() ?: 0
            updateTimeDisplay()
        }

        // Fill category — this also calls updateQuantityUI()
        val categoryPos = when (editCategory) {
            "TABLET"  -> 0
            "SYRUP"   -> 1
            "CAPSULE" -> 2
            else      -> 0
        }
        binding.spinnerCategory.setSelection(categoryPos)

        // Fill quantity — safely strip unit suffix
        // e.g. "2 tablet(s)" → "2" | "5 ml" → "5" | "1 capsule(s)" → "1"
        val quantityNum = editQuantity
            .replace("tablet(s)", "", ignoreCase = true)
            .replace("capsule(s)", "", ignoreCase = true)
            .replace("ml", "", ignoreCase = true)
            .trim()
        binding.etQuantity.setText(quantityNum)

        val editStock = intent.getIntExtra("MEDICINE_STOCK", 0)
        binding.etStock.setText(editStock.toString())
    }

    // ── FORM VALIDATION ───────────────────────────────────────
    private fun validateForm(): Boolean {
        val name     = binding.etMedicineName.text.toString().trim()
        val quantity = binding.etQuantity.text.toString().trim()
        val stock    = binding.etStock.text.toString().trim()  // ← NEW

        if (name.isEmpty()) {
            binding.etMedicineName.error = "Medicine name is required"
            binding.etMedicineName.requestFocus()
            return false
        }
        if (name.length < 2) {
            binding.etMedicineName.error = "Name must be at least 2 characters"
            binding.etMedicineName.requestFocus()
            return false
        }
        if (quantity.isEmpty()) {
            binding.etQuantity.error = "Quantity is required"
            binding.etQuantity.requestFocus()
            return false
        }
        if (quantity.toDoubleOrNull() == null || quantity.toDouble() <= 0) {
            binding.etQuantity.error = "Enter a valid quantity"
            binding.etQuantity.requestFocus()
            return false
        }
        // ── NEW: stock validation ──────────────────────────────
        if (stock.isEmpty()) {
            binding.etStock.error = "Stock count is required"
            binding.etStock.requestFocus()
            return false
        }
        if (stock.toIntOrNull() == null || stock.toInt() < 0) {
            binding.etStock.error = "Enter a valid stock number"
            binding.etStock.requestFocus()
            return false
        }
        return true
    }

    // ── SAVE / UPDATE MEDICINE ────────────────────────────────
    private fun saveMedicine() {
        val name     = binding.etMedicineName.text.toString().trim()
        val quantity = binding.etQuantity.text.toString().trim()
        val stock    = binding.etStock.text.toString().trim().toIntOrNull() ?: 0
        val time     = String.format(
            Locale.getDefault(),
            "%02d:%02d",
            selectedHour,
            selectedMinute
        )

        val categoryPos      = binding.spinnerCategory.selectedItemPosition
        val category         = when (categoryPos) {
            0    -> "TABLET"
            1    -> "SYRUP"
            else -> "CAPSULE"
        }
        val unit             = when (categoryPos) {
            0    -> "tablet(s)"
            1    -> "ml"
            else -> "capsule(s)"
        }
        val quantityWithUnit = "$quantity $unit"

        lifecycleScope.launch {
            try {
                val db = MedicineDatabase.getDatabase(this@AddMedicineActivity)

                if (isEditMode && editMedicineId != -1) {
                    // ── UPDATE existing medicine ───────────
                    val updated = MedicineEntity(
                        id                = editMedicineId,
                        name              = name,
                        time              = time,
                        quantity          = quantityWithUnit,
                        category          = category,
                        compartmentNumber = compartmentNumber,
                        status            = "PENDING",
                        currentStock      = stock
                    )

                    withContext(Dispatchers.IO) {
                        db.medicineDao().updateMedicine(updated)
                    }

                    // Cancel old alarm + schedule new one
                    AlarmScheduler.cancelMedicineAlarm(
                        this@AddMedicineActivity, editMedicineId)
                    AlarmScheduler.scheduleMedicineAlarm(
                        this@AddMedicineActivity, updated.toMedicine())

                    // Update ESP32 alarm
                    val ble = AppState.getBleManager()
                    if (ble != null && ble.isConnected()) {
                        ble.setAlarm(
                            slot   = compartmentNumber,
                            hour   = selectedHour,
                            minute = selectedMinute
                        )
                    }

                    Toast.makeText(
                        this@AddMedicineActivity,
                        "✅ '$name' updated successfully!",
                        Toast.LENGTH_SHORT
                    ).show()

                } else {
                    // ── INSERT new medicine ────────────────
                    val entity = MedicineEntity(
                        id                = 0,
                        name              = name,
                        time              = time,
                        quantity          = quantityWithUnit,
                        category          = category,
                        compartmentNumber = compartmentNumber,
                        status            = "PENDING",
                        currentStock      = stock
                    )

                    withContext(Dispatchers.IO) {
                        db.medicineDao().insertMedicine(entity)
                    }

                    // Get saved entity with real ID for alarm
                    val saved = withContext(Dispatchers.IO) {
                        db.medicineDao().getAllMedicinesOnce()
                            .lastOrNull {
                                it.name              == name &&
                                        it.time              == time &&
                                        it.compartmentNumber == compartmentNumber
                            }
                    }

                    saved?.let {
                        AlarmScheduler.scheduleMedicineAlarm(
                            this@AddMedicineActivity,
                            it.toMedicine()
                        )
                    }

                    // Send to ESP32
                    val ble = AppState.getBleManager()
                    if (ble != null && ble.isConnected()) {
                        ble.setAlarm(
                            slot   = compartmentNumber,
                            hour   = selectedHour,
                            minute = selectedMinute
                        )
                        Toast.makeText(
                            this@AddMedicineActivity,
                            "✅ '$name' saved & sent to Medicine Box!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@AddMedicineActivity,
                            "💊 '$name' added to Box $compartmentNumber!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                finish()

            } catch (e: Exception) {
                Toast.makeText(
                    this@AddMedicineActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}