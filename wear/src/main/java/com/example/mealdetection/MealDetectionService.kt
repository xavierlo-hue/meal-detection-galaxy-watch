package com.example.mealdetection // TODO: remplacer par ton package

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MealDetectionService : Service(), SensorEventListener {

    // ── Capteurs ──────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private lateinit var wakeLock: android.os.PowerManager.WakeLock
    @Volatile private var accValues = FloatArray(3)
    @Volatile private var accReady  = false

    // ── Buffer circulaire [index][axe] — 8 secondes à 50Hz ───────────────────
    private val buffer = Array(WINDOW_SIZE) { FloatArray(N_AXES) }
    private var bufferIndex = 0
    @Volatile private var bufferFilled = false

    // ── TFLite ────────────────────────────────────────────────────────────────
    private var interpreter: Interpreter? = null

    // ── Agrégation d'épisode ──────────────────────────────────────────────────
    private val detectionTimestamps = mutableListOf<Long>()
    private var mealNotified        = false
    private var lastNotificationTime = 0L

    // ── Scheduling inférence ──────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val inferenceRunnable = object : Runnable {
        override fun run() {
            if (bufferFilled) runInference()
            handler.postDelayed(this, WINDOW_INCREASE_MS)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cycle de vie
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val powerManager = getSystemService(android.os.PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "MealDetection::InferenceWakeLock"
        )
        wakeLock.acquire()
        createNotificationChannels()
        loadModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            ACTION_INJECTION_DONE -> {
                Log.d(TAG, "✅ Injection confirmée — arrêt 45 min")
                getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_ALERT)
                handler.removeCallbacks(inferenceRunnable)
                sensorManager.unregisterListener(this)
                mealNotified = false
                detectionTimestamps.clear()

                // Programme la reprise si on est encore dans la plage horaire
                val resumeTime = System.currentTimeMillis() + INJECTION_PAUSE_MS
                val plageEnd   = MealScheduleReceiver.currentPlageEndMs()
                if (plageEnd > 0 && resumeTime < plageEnd) {
                    val resumeIntent = PendingIntent.getService(
                        this, 99,
                        Intent(this, MealDetectionService::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    getSystemService(AlarmManager::class.java)
                        .setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, resumeTime, resumeIntent
                        )
                    val heure = SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(Date(resumeTime))
                    Log.d(TAG, "⏰ Reprise programmée à $heure")
                } else {
                    Log.d(TAG, "⏰ Hors plage à la reprise — pas de redémarrage")
                }

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_NOT_EATING -> {
                Log.d(TAG, "❌ Faux positif — pas de cooldown, compteur réinitialisé")
                lastNotificationTime = 0L
                mealNotified = false
                detectionTimestamps.clear()
                getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_ALERT)
                return START_STICKY
            }
        }

        if (intent == null && !MealScheduleReceiver.isCurrentlyInSchedule()) {
            Log.d(TAG, "Redémarrage système hors plage horaire — arrêt")
            stopSelf()
            return START_NOT_STICKY
        }

        // Démarrage normal — annule tout runnable précédent avant d'en poster un nouveau
        handler.removeCallbacks(inferenceRunnable)
        startForeground(NOTIF_ID_FG, buildForegroundNotification())
        registerSensors()
        handler.postDelayed(inferenceRunnable, WINDOW_INCREASE_MS)
        Log.d(TAG, "Service démarré — détection CNN-LSTM active")
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(inferenceRunnable)  // EN PREMIER
        sensorManager.unregisterListener(this)
        interpreter?.close()
        super.onDestroy()
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Chargement du modèle TFLite
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadModel() {
        try {
            val modelBytes = assets.open(MODEL_FILE).readBytes()
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(modelBytes)
                rewind()
            }
            interpreter = Interpreter(modelBuffer)
            val inputShape  = interpreter!!.getInputTensor(0).shape()
            val outputShape = interpreter!!.getOutputTensor(0).shape()
            Log.d(TAG, "✅ Modèle CNN-LSTM chargé : $MODEL_FILE")
            Log.d(TAG, "   Input  : ${inputShape.toList()} → attendu [1, $WINDOW_SIZE, $N_AXES]")
            Log.d(TAG, "   Output : ${outputShape.toList()} → [1, 2] (autre=0, repas=1)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur chargement modèle", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Capteurs
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerSensors() {
        val acc = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gyr = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (acc == null || gyr == null) {
            Log.e(TAG, "Capteurs indisponibles")
            stopSelf()
            return
        }
        sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, gyr, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                accValues[0] = event.values[0]
                accValues[1] = event.values[1]
                accValues[2] = event.values[2]
                accReady = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!accReady) return
                buffer[bufferIndex][0] = accValues[0]
                buffer[bufferIndex][1] = accValues[1]
                buffer[bufferIndex][2] = accValues[2]
                buffer[bufferIndex][3] = event.values[0]
                buffer[bufferIndex][4] = event.values[1]
                buffer[bufferIndex][5] = event.values[2]
                bufferIndex = (bufferIndex + 1) % WINDOW_SIZE
                if (!bufferFilled && bufferIndex == 0) {
                    bufferFilled = true
                    Log.d(TAG, "Buffer 8s rempli — inférences CNN-LSTM prêtes")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Inférence CNN-LSTM
    // ─────────────────────────────────────────────────────────────────────────

    private fun runInference() {
        val interp = interpreter ?: return
        try {
            // Fenêtre ordonnée + normalisation z-score par axe
            val input = Array(1) {
                Array(WINDOW_SIZE) { i ->
                    val idx = (bufferIndex + i) % WINDOW_SIZE
                    FloatArray(N_AXES) { axis ->
                        (buffer[idx][axis] - NORM_MEAN_AXIS[axis]) / NORM_STD_AXIS[axis]
                    }
                }
            }

            val output = Array(1) { FloatArray(2) }
            interp.run(input, output)

            val probAutre = output[0][0]
            val probRepas = output[0][1]

            // Nettoyage fenêtre glissante à CHAQUE inférence
            val now = System.currentTimeMillis()
            detectionTimestamps.removeAll { now - it > EPISODE_WINDOW_MS }

            Log.d(TAG, "Inférence → repas=%.2f autre=%.2f | compteur=%d/%d"
                .format(probRepas, probAutre, detectionTimestamps.size, episodeThreshold))

            if (probRepas >= confidenceThreshold) onPositiveDetection()

        } catch (e: Exception) {
            Log.e(TAG, "Erreur inférence", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Agrégation d'épisode
    // ─────────────────────────────────────────────────────────────────────────

    private fun onPositiveDetection() {
        val now = System.currentTimeMillis()
        detectionTimestamps.add(now)

        Log.d(TAG, "Détection #${detectionTimestamps.size}/$episodeThreshold " +
            "mealNotified=$mealNotified")

        if (detectionTimestamps.size >= episodeThreshold && !mealNotified) {
            triggerMealNotification()
            mealNotified = true
            detectionTimestamps.clear()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification locale Wear OS
    // ─────────────────────────────────────────────────────────────────────────

    private fun triggerMealNotification() {
        Log.d(TAG, "🍽 Repas détecté — notification insuline")

        val injectionIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MealDetectionService::class.java)
                .apply { action = ACTION_INJECTION_DONE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notEatingIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MealDetectionService::class.java)
                .apply { action = ACTION_NOT_EATING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ALERT)
            .setContentTitle("🍽 Repas en cours ?")
            .setContentText("As-tu fait ton injection d'insuline ?")
            .setSmallIcon(R.drawable.ic_meal_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .addAction(
                android.R.drawable.checkbox_on_background,
                "Injection faite ✅", injectionIntent
            )
            .addAction(
                android.R.drawable.ic_delete,
                "Je ne mange pas ❌", notEatingIntent
            )
            .build()

        getSystemService(NotificationManager::class.java).notify(NOTIF_ID_ALERT, notification)

        val vibrator = getSystemService(Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 600, 200, 600, 200, 600), -1)
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notifications foreground
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL_DETECTION, "Détection repas",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL_ALERT, "Alerte insuline",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { enableVibration(true) }
            )
        }
    }

    private fun buildForegroundNotification(): Notification =
        NotificationCompat.Builder(this, NOTIF_CHANNEL_DETECTION)
            .setContentTitle("Détection repas active")
            .setContentText("CNN-LSTM en cours")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

    // ─────────────────────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "MealDetection"

        // Modèle
        const val MODEL_FILE = "meal_detection_cnn.tflite"

        // Capteurs — 8 secondes à 50Hz
        private const val SAMPLE_RATE        = 50
        private const val WINDOW_MS          = 8000
        const val WINDOW_SIZE                = SAMPLE_RATE * WINDOW_MS / 1000  // 400
        private const val N_AXES             = 6
        private const val WINDOW_INCREASE_MS = 7000L  // inférence toutes les 7 secondes

        // Seuils de détection
        @Volatile var confidenceThreshold = 0.97f

        // Normalisation z-score par axe (accX, accY, accZ, gyrX, gyrY, gyrZ)
        private val NORM_MEAN_AXIS = floatArrayOf(
            // accX, accY, accZ, gyrX, gyrY, gyrZ
            0.14049745f, -0.05591523f, -0.12859894f, -0.00415100f, -0.00260113f, -0.00104008f
        )

        private val NORM_STD_AXIS = floatArrayOf(
            // accX, accY, accZ, gyrX, gyrY, gyrZ
            1.62870395f, 2.22045159f, 1.54841888f, 0.94431001f, 0.48943168f, 0.69776756f
        )



        const val EPISODE_THRESHOLD_DEFAULT = 7

        @Volatile var episodeThreshold = EPISODE_THRESHOLD_DEFAULT
        private const val EPISODE_WINDOW_MS    = 90_000L   // fenêtre glissante 90 secondes

        // Pause après injection (45 min)
        private const val INJECTION_PAUSE_MS   = 45 * 60 * 1000L

        // Actions notification
        const val ACTION_INJECTION_DONE = "com.example.mealdetection.INJECTION_DONE"
        const val ACTION_NOT_EATING     = "com.example.mealdetection.NOT_EATING"

        // IDs notifications
        private const val NOTIF_ID_FG            = 20
        private const val NOTIF_ID_ALERT          = 21
        private const val NOTIF_CHANNEL_DETECTION = "meal_detection"
        private const val NOTIF_CHANNEL_ALERT     = "meal_alert"
    }
}
