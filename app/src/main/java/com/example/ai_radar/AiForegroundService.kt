package com.example.ai_radar

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.TensorImage
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class AiForegroundService : Service() {

    private val CHANNEL_ID = "AI_Radar_Channel"
    private val NOTIFICATION_ID = 1
    private var mediaProjection: MediaProjection? = null
    private var tflite: Interpreter? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        try {
            tflite = Interpreter(loadModelFile())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = assets.openFd("ai_detector.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Servisi tipiyle beraber başlat
        val notification = createNotification("Radar Aktif", "Analiz için dokunun")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2. Projeksiyon verisini al
        val projectionData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("projection_intent", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("projection_intent")
        }

        if (projectionData != null && mediaProjection == null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(Activity.RESULT_OK, projectionData)
        }

        // 3. Bildirimden gelen tık işlemi
        if (intent?.action == "ACTION_TRIGGER_ANALYSIS") {
            performSafeAnalysis()
        }

        return START_STICKY
    }

    private fun performSafeAnalysis() {
        val projection = mediaProjection ?: return
        val metrics = resources.displayMetrics
        val targetWidth = metrics.widthPixels / 2
        val targetHeight = metrics.heightPixels / 2

        updateNotificationText("Analiz Ediliyor...", "Görüntü işleniyor...")

        mainHandler.postDelayed({
            try {
                val reader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 1)
                val virtualDisplay = projection.createVirtualDisplay(
                    "RadarScan", targetWidth, targetHeight, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, mainHandler
                )

                mainHandler.postDelayed({
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * targetWidth

                        val bitmap = Bitmap.createBitmap(
                            targetWidth + rowPadding / pixelStride,
                            targetHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)

                        image.close()
                        virtualDisplay.release()
                        reader.close()

                        Thread {
                            val score = runInference(bitmap)
                            mainHandler.post {
                                updateNotificationText("Analiz Tamamlandı", "Yapaylık Skoru: %$score")
                            }
                        }.start()
                    } else {
                        virtualDisplay.release()
                        reader.close()
                        updateNotificationText("Hata", "Görüntü alınamadı.")
                    }
                }, 400)
            } catch (e: Exception) {
                updateNotificationText("Hata", "Sistem reddetti.")
            }
        }, 100)
    }

    private fun runInference(bitmap: Bitmap): Int {
        val model = tflite ?: return 0
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
            val tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
            tensorImage.load(scaled)
            val output = Array(1) { FloatArray(1000) }
            model.run(tensorImage.buffer, output)
            ((output[0].maxOrNull() ?: 0f) * 100).toInt()
        } catch (e: Exception) { 0 }
    }

    private fun createNotification(title: String, text: String): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "AI Radar", NotificationManager.IMPORTANCE_HIGH)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val intent = Intent(this, AnalysisReceiver::class.java)
        // Hatanın çözümü: FLAG doğrudan burada tanımlandı
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotificationText(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(title, text))
    }

    override fun onDestroy() {
        mediaProjection?.stop()
        tflite?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}