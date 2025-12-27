package com.example.ai_radar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AnalysisReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, AiForegroundService::class.java).apply {
            action = "ACTION_TRIGGER_ANALYSIS"
        }
        context.startService(serviceIntent)
    }
}