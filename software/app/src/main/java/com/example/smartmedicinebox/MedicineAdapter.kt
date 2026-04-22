package com.example.smartmedicinebox

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.smartmedicinebox.databinding.ItemMedicineBinding
import com.example.smartmedicinebox.model.Medicine
import java.util.Calendar
import java.util.Locale

class MedicineAdapter(
    private val medicines: MutableList<Medicine>,
    private val onMarkTaken: (Medicine) -> Unit,
    private val onDelete: (Medicine) -> Unit
) : RecyclerView.Adapter<MedicineAdapter.MedicineViewHolder>() {

    inner class MedicineViewHolder(val binding: ItemMedicineBinding)
        : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MedicineViewHolder {
        val binding = ItemMedicineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MedicineViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MedicineViewHolder, position: Int) {
        val medicine = medicines[position]
        val b = holder.binding

        b.tvMedicineName.text = medicine.name

        val categoryIcon = when (medicine.category) {
            Medicine.Category.TABLET  -> "💊 Tablet"
            Medicine.Category.SYRUP   -> "🧴 Syrup"
            Medicine.Category.CAPSULE -> "💉 Capsule"
        }
        b.tvSubInfo.text = "${medicine.time} · $categoryIcon · Compartment ${medicine.compartmentNumber}"

        // Time left calculation
        val timeLeftText = getTimeLeftText(medicine)
        b.tvTimeLeft.text = timeLeftText

        // Status styling
        when (medicine.status) {
            Medicine.Status.TAKEN -> {
                b.tvStatus.text = "✔ TAKEN"
                b.tvStatus.setTextColor(Color.parseColor("#2E7D32"))
                b.tvStatus.background.setTint(Color.parseColor("#E8F5E9"))
                b.statusStripe.setBackgroundColor(Color.parseColor("#388E3C"))
                b.btnMarkTaken.visibility = View.GONE
                b.tvTimeLeft.text = "Taken ✓"
                b.tvTimeLeft.setTextColor(Color.parseColor("#388E3C"))
            }
            Medicine.Status.PENDING -> {
                b.tvStatus.text = "⏳ PENDING"
                b.tvStatus.setTextColor(Color.parseColor("#C62828"))
                b.tvStatus.background.setTint(Color.parseColor("#FFEBEE"))
                b.statusStripe.setBackgroundColor(Color.parseColor("#D32F2F"))
                b.btnMarkTaken.visibility = View.VISIBLE
                b.tvTimeLeft.setTextColor(Color.parseColor("#1565C0"))
            }
            Medicine.Status.MISSED -> {
                b.tvStatus.text = "✗ MISSED"
                b.tvStatus.setTextColor(Color.parseColor("#E65100"))
                b.tvStatus.background.setTint(Color.parseColor("#FFFDE7"))
                b.statusStripe.setBackgroundColor(Color.parseColor("#F9A825"))
                b.btnMarkTaken.visibility = View.VISIBLE
                b.tvTimeLeft.setTextColor(Color.parseColor("#F57F17"))
            }
        }

        b.btnMarkTaken.setOnClickListener { onMarkTaken(medicine) }
        b.btnDelete.setOnClickListener { onDelete(medicine) }
    }

    private fun getTimeLeftText(medicine: Medicine): String {
        if (medicine.status == Medicine.Status.TAKEN) return "Taken ✓"

        val parts = medicine.time.split(":")
        if (parts.size != 2) return ""

        val now = Calendar.getInstance()
        val medCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, parts[0].toInt())
            set(Calendar.MINUTE, parts[1].toInt())
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val diffMs = medCal.timeInMillis - now.timeInMillis

        return when {
            diffMs < 0 -> {
                val agoMin = (-diffMs / 60000).toInt()
                if (agoMin < 60) "Missed ${agoMin}m ago"
                else "Missed ${agoMin / 60}h ago"
            }
            diffMs < 3600000 -> {
                val mins = (diffMs / 60000).toInt()
                "in ${mins}m"
            }
            else -> {
                val hours = (diffMs / 3600000).toInt()
                val mins = ((diffMs % 3600000) / 60000).toInt()
                String.format(Locale.getDefault(), "in %dh %02dm", hours, mins)
            }
        }
    }

    override fun getItemCount() = medicines.size

    fun updateList(newList: List<Medicine>) {
        medicines.clear()
        medicines.addAll(newList)
        notifyDataSetChanged()
    }
}