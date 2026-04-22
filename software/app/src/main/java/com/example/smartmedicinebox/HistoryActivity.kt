package com.example.smartmedicinebox

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smartmedicinebox.database.MedicineDatabase
import com.example.smartmedicinebox.databinding.ActivityHistoryBinding
import com.example.smartmedicinebox.model.MedicineHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val historyList = mutableListOf<MedicineHistoryEntity>()
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        loadHistory()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(historyList)
        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val db = MedicineDatabase.getDatabase(this@HistoryActivity)

            // Get last 7 days of history
            val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            val history = withContext(Dispatchers.IO) {
                db.historyDao().getHistoryFrom(sevenDaysAgo)
            }

            historyList.clear()
            historyList.addAll(history)
            adapter.notifyDataSetChanged()

            // Update stats
            val taken  = history.count { it.status == "TAKEN" }
            val missed = history.count { it.status == "MISSED" }
            binding.tvTotalHistory.text  = history.size.toString()
            binding.tvTakenHistory.text  = taken.toString()
            binding.tvMissedHistory.text = missed.toString()

            // ── NEW: Show/Hide Empty State ──
            val layoutEmptyHistory = findViewById<LinearLayout>(R.id.layoutEmptyHistory)
            if (historyList.isEmpty()) {
                binding.recyclerHistory.visibility = View.GONE
                layoutEmptyHistory?.visibility = View.VISIBLE
            } else {
                binding.recyclerHistory.visibility = View.VISIBLE
                layoutEmptyHistory?.visibility = View.GONE
            }

            // Build 7-day strip
            build7DayStrip(db)
        }
    }

    private suspend fun build7DayStrip(db: MedicineDatabase) {
        binding.layoutDayStrip.removeAllViews()
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dayFormat  = SimpleDateFormat("EEE", Locale.getDefault())
        val dayNumFmt  = SimpleDateFormat("d", Locale.getDefault())

        // Build 7 days from oldest to newest
        val days = (6 downTo 0).map { offset ->
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -offset)
            cal
        }

        for (cal in days) {
            val dateStr  = dateFormat.format(cal.time)
            val dayName  = dayFormat.format(cal.time)
            val dayNum   = dayNumFmt.format(cal.time)
            val isToday  = dateStr == dateFormat.format(Calendar.getInstance().time)

            val taken  = withContext(Dispatchers.IO) {
                db.historyDao().countTakenForDate(dateStr)
            }
            val missed = withContext(Dispatchers.IO) {
                db.historyDao().countMissedForDate(dateStr)
            }

            // Build day circle view
            val dayView = LayoutInflater.from(this)
                .inflate(R.layout.item_day_circle, null)

            val tvDay    = dayView.findViewById<TextView>(R.id.tvDayName)
            val tvNum    = dayView.findViewById<TextView>(R.id.tvDayNum)
            val tvDot    = dayView.findViewById<View>(R.id.viewDot)

            tvDay.text = dayName.uppercase()
            tvNum.text = dayNum

            // Color circle based on status
            val circleDrawable = GradientDrawable()
            circleDrawable.shape = GradientDrawable.OVAL
            when {
                isToday && taken == 0 && missed == 0 -> {
                    // Today — no data yet
                    circleDrawable.setColor(Color.parseColor("#1565C0"))
                    tvNum.setTextColor(Color.WHITE)
                    tvDot.visibility = View.INVISIBLE
                }
                taken > 0 && missed == 0 -> {
                    // All taken — green
                    circleDrawable.setColor(Color.parseColor("#388E3C"))
                    tvNum.setTextColor(Color.WHITE)
                    tvDot.visibility = View.INVISIBLE
                }
                missed > 0 && taken == 0 -> {
                    // All missed — amber
                    circleDrawable.setColor(Color.parseColor("#F9A825"))
                    tvNum.setTextColor(Color.BLACK)
                    tvDot.visibility = View.INVISIBLE
                }
                taken > 0 && missed > 0 -> {
                    // Mixed — orange
                    circleDrawable.setColor(Color.parseColor("#EF6C00"))
                    tvNum.setTextColor(Color.WHITE)
                    tvDot.visibility = View.INVISIBLE
                }
                else -> {
                    // No data — grey
                    circleDrawable.setColor(Color.parseColor("#424242"))
                    tvNum.setTextColor(Color.parseColor("#9E9E9E"))
                    tvDot.visibility = View.INVISIBLE
                }
            }
            tvNum.background = circleDrawable

            val params = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            dayView.layoutParams = params
            binding.layoutDayStrip.addView(dayView)
        }
    }

    // ── HISTORY ADAPTER ───────────────────────────────────────
    inner class HistoryAdapter(
        private val items: List<MedicineHistoryEntity>
    ) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

        inner class HistoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvIcon    = view.findViewById<TextView>(R.id.tvHistoryIcon)
            val tvName    = view.findViewById<TextView>(R.id.tvHistoryName)
            val tvDetails = view.findViewById<TextView>(R.id.tvHistoryDetails)
            val tvTime    = view.findViewById<TextView>(R.id.tvHistoryTime)
            val tvStatus  = view.findViewById<TextView>(R.id.tvHistoryStatus)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            HistoryViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_history, parent, false)
            )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
            val item = items[position]

            // Icon based on category
            holder.tvIcon.text = when (item.category) {
                "TABLET"  -> "💊"
                "SYRUP"   -> "🧴"
                "CAPSULE" -> "💉"
                else      -> "💊"
            }

            holder.tvName.text = item.medicineName

            holder.tvDetails.text = "Box ${item.compartmentNumber} · " +
                    item.category.lowercase()
                        .replaceFirstChar { it.uppercase() } +
                    " · ${item.scheduledTime}"

            // Format timestamp
            val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
            holder.tvTime.text = sdf.format(Date(item.timestamp))

            // Status badge
            when (item.status) {
                "TAKEN" -> {
                    holder.tvStatus.text = "✔ TAKEN"
                    holder.tvStatus.setTextColor(Color.WHITE)
                    holder.tvStatus.background.setTint(Color.parseColor("#388E3C"))
                }
                "MISSED" -> {
                    holder.tvStatus.text = "✗ MISSED"
                    holder.tvStatus.setTextColor(Color.BLACK)
                    holder.tvStatus.background.setTint(Color.parseColor("#F9A825"))
                }
            }

            // Icon background circle
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(
                if (item.status == "TAKEN")
                    Color.parseColor("#E8F5E9")
                else
                    Color.parseColor("#FFFDE7")
            )
            holder.tvIcon.background = bg
        }
    }
}