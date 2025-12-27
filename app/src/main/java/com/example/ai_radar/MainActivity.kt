package com.example.ai_radar

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val CHANNEL_ID = "AI_Radar_Channel"

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startAiService(result.data!!)
        } else {
            Toast.makeText(this, "Ekran izni verilmedi.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        checkNotificationPermission()

        setContent {
            var isServiceRunning by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                isServiceRunning = isServiceRunning(AiForegroundService::class.java)
            }

            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "AI Radar", style = MaterialTheme.typography.headlineLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Bildirim panelinden anlık AI tespiti yapmak için aşağıdaki anahtarı açın.")

                    Spacer(modifier = Modifier.height(32.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = if (isServiceRunning) "Radar Durumu: AÇIK" else "Radar Durumu: KAPALI")

                        Switch(
                            checked = isServiceRunning,
                            onCheckedChange = { isChecked ->
                                if (isChecked) {
                                    requestScreenCapture()
                                    isServiceRunning = true
                                } else {
                                    stopAiService()
                                    isServiceRunning = false
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()){}.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    // --- GÜNCELLENEN KRİTİK KISIM ---
    private fun startAiService(resultData: Intent) {
        // 1. Önce servisi sadece başlatıyoruz (Kayıt işlemi için)
        val serviceIntent = Intent(this, AiForegroundService::class.java).apply {
            putExtra("projection_intent", resultData)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        // 2. Android 14+ için küçük bir gecikme ekliyoruz.
        // Servis "Foreground" olarak sisteme tam kaydedilmeden MediaProjection başlamamalı.
        Handler(Looper.getMainLooper()).postDelayed({
            val wakeUpIntent = Intent(this, AiForegroundService::class.java).apply {
                // Servis içindeki onStartCommand'ı tekrar tetikler ama projection verisini bozmaz
                action = "ACTION_SERVICE_READY"
            }
            startService(wakeUpIntent)
        }, 500)
    }
    // --------------------------------

    private fun stopAiService() {
        stopService(Intent(this, AiForegroundService::class.java))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AI Radar Servisi", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}