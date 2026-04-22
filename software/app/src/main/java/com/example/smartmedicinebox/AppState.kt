package com.example.smartmedicinebox

import android.content.Context

object AppState {
    private var bleManager: BleManager? = null

    fun init(context: Context) {
        if (bleManager == null) {
            bleManager = BleManager(context.applicationContext)
        }
    }

    fun getBleManager(): BleManager? = bleManager
}