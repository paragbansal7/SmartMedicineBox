package com.example.smartmedicinebox

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

@SuppressLint("MissingPermission")
class BleDeviceListDialog(
    private val context: Context,
    private val onDeviceSelected: (BluetoothDevice) -> Unit
) {
    private val deviceList  = mutableListOf<BluetoothDevice>()
    private val deviceNames = mutableListOf<String>()
    private var adapter: DeviceAdapter? = null
    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var isScanning = false

    // ── Custom adapter for nicer device list ──────────────────
    inner class DeviceAdapter(context: Context) :
        ArrayAdapter<String>(context, android.R.layout.simple_list_item_2,
            android.R.id.text1, deviceNames) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val text1 = view.findViewById<TextView>(android.R.id.text1)
            val text2 = view.findViewById<TextView>(android.R.id.text2)
            val device = deviceList.getOrNull(position)
            text1.text = device?.name ?: "Unknown"
            text1.setTextColor(0xFF1565C0.toInt())
            text1.textSize = 15f
            text2.text = device?.address ?: ""
            text2.setTextColor(0xFF9E9E9E.toInt())
            text2.textSize = 12f
            return view
        }
    }

    // ── BLE Scan Callback ─────────────────────────────────────
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name   = device.name ?: return

            // Only show MedBox device
            if (name == BleConstants.DEVICE_NAME && !deviceList.contains(device)) {
                handler.post {
                    deviceList.add(device)
                    deviceNames.add(name)
                    adapter?.notifyDataSetChanged()

                    // Update dialog title to show found
                    dialog?.setTitle("🔵 Found ${deviceList.size} device(s)")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                Toast.makeText(
                    context,
                    "BLE scan failed (code $errorCode). Check Bluetooth + Location are ON.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ── Show Dialog ───────────────────────────────────────────
    fun show() {
        deviceList.clear()
        deviceNames.clear()

        val listView = ListView(context)
        adapter = DeviceAdapter(context)
        listView.adapter = adapter
        listView.setPadding(0, 8, 0, 8)

        // Scanning indicator header
        val headerView = TextView(context).apply {
            text = "⏳ Scanning for MedBox..."
            setTextColor(0xFF9E9E9E.toInt())
            textSize = 12f
            setPadding(48, 16, 48, 8)
        }
        listView.addHeaderView(headerView, null, false)

        dialog = AlertDialog.Builder(context)
            .setTitle("🔵 Scanning for devices...")
            .setView(listView)
            .setNegativeButton("Cancel") { _, _ ->
                stopScan()
            }
            .setOnDismissListener {
                stopScan()
            }
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            // position - 1 because of header view
            val actualPosition = position - 1
            if (actualPosition >= 0 && actualPosition < deviceList.size) {
                stopScan()
                dialog?.dismiss()
                val device = deviceList[actualPosition]
                Toast.makeText(
                    context,
                    "Connecting to ${device.name}...",
                    Toast.LENGTH_SHORT
                ).show()
                onDeviceSelected(device)
            }
        }

        startScan()
        dialog?.show()
    }

    // ── Start BLE Scan ────────────────────────────────────────
    private fun startScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
            isScanning = true

            // Auto stop scan after 12 seconds
            handler.postDelayed({
                if (isScanning) {
                    stopScan()
                    if (deviceList.isEmpty()) {
                        handler.post {
                            dialog?.setTitle("❌ No devices found")
                            Toast.makeText(
                                context,
                                "MedBox not found!\n" +
                                        "• Is ESP32 powered on?\n" +
                                        "• Is main.py running?\n" +
                                        "• Is Bluetooth ON?",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }, 12000)

        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Could not start scan: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Stop BLE Scan ─────────────────────────────────────────
    private fun stopScan() {
        if (isScanning) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                // ignore
            }
            isScanning = false
        }
    }
}