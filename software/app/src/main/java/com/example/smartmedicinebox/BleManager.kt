package com.example.smartmedicinebox

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Calendar
import java.util.Locale
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    var onMessageReceived: ((String) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Start discovering services, but DO NOT say we are fully connected yet!
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    bluetoothGatt = null
                    Handler(Looper.getMainLooper()).post {
                        onConnectionChanged?.invoke(false)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                rxCharacteristic = service?.getCharacteristic(RX_CHAR_UUID)

                val txChar = service?.getCharacteristic(TX_CHAR_UUID)
                txChar?.let { char ->
                    gatt.setCharacteristicNotification(char, true)
                    val descriptor = char.getDescriptor(CCCD_UUID)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(
                            descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                }

                // Services are discovered and channels are open. Now tell the app we are ready!
                Handler(Looper.getMainLooper()).post {
                    onConnectionChanged?.invoke(true)
                }
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val msg = characteristic.value?.toString(Charsets.UTF_8) ?: return
            Handler(Looper.getMainLooper()).post {
                onMessageReceived?.invoke(msg.trim())
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val msg = value.toString(Charsets.UTF_8)
            Handler(Looper.getMainLooper()).post {
                onMessageReceived?.invoke(msg.trim())
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    fun isConnected(): Boolean = bluetoothGatt != null

    @Suppress("DEPRECATION")
    fun sendMessage(message: String): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = rxCharacteristic ?: return false

        // SAFETY NET: Ensure every single message ends with a newline character
        val safeMessage = if (message.endsWith("\n")) message else "$message\n"
        val payloadBytes = safeMessage.toByteArray(Charsets.UTF_8)

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                char,
                payloadBytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            char.value = payloadBytes
            gatt.writeCharacteristic(char)
        }
    }

    // ── HELPER FUNCTIONS ──────────────────────────────────────

    fun syncTime(): Boolean {
        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val weekday = if (dow == Calendar.SUNDAY) 7 else dow - 1

        val payload = String.format(
            Locale.getDefault(),
            "TIME:%04d,%02d,%02d,%d,%02d,%02d,%02d,0\n",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            weekday,
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND)
        )
        return sendMessage(payload)
    }

    fun setAlarm(slot: Int, hour: Int, minute: Int): Boolean {
        val payload = String.format(
            Locale.getDefault(),
            "ALARM:LED%d,%02d,%02d\n",
            slot, hour, minute
        )
        return sendMessage(payload)
    }

    fun clearAllAlarms(): Boolean = sendMessage("CLEAR:ALL\n")

    fun clearAlarm(slot: Int): Boolean = sendMessage("CLEAR:LED$slot\n")

    fun requestStatus(): Boolean = sendMessage("STATUS\n")

    // Send SYNC_LOGS request to ESP32
    fun requestOfflineSync(): Boolean = sendMessage("SYNC_LOGS\n")

    // Tell ESP32 to clear its history.json after we saved everything
    fun confirmLogsClear(): Boolean = sendMessage("CLEAR_LOGS\n")
}