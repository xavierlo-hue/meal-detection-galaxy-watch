package com.example.mealdetection // TODO: remplacer par ton package

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter

/**
 * Service de collecte de données RAW directement sur la montre.
 *
 * Contrairement à l'ancien SensorStreamingService qui passait par Bluetooth
 * (ce qui réduisait la fréquence à ~33Hz et lissait les amplitudes),
 * ce service écrit directement en CSV sur le stockage de la montre à vrai 50Hz.
 *
 * Les CSV sont ensuite récupérés par ADB et uploadés sur Edge Impulse
 * pour réentraîner le modèle sur des données identiques à la production.
 *
 * Usage :
 *   1. Sélectionne le label dans WearMainActivity
 *   2. Lance ce service → il enregistre en CSV sur la montre
 *   3. Stop → fichier fermé proprement
 *   4. Récupère les fichiers via ADB :
 *      adb pull /sdcard/Android/data/com.example.mealdetection/files/watch_recordings/
 */
class WatchDataCollectionService : Service(), SensorEventListener {

    // ── Capteurs ──────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager

    @Volatile private var accValues = FloatArray(3)
    @Volatile private var accReady  = false

    // ── Fichier CSV ───────────────────────────────────────────────────────────
    private var fileWriter: FileWriter? = null
    private var sampleCount = 0
    private var currentLabel = "non_etiquete"

    // ─────────────────────────────────────────────────────────────────────────
    // Cycle de vie
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentLabel = intent?.getStringExtra(EXTRA_LABEL) ?: "non_etiquete"
        Log.d(TAG, "Démarrage collecte watch : label=$currentLabel")

        startForeground(NOTIF_ID, buildNotification("Démarrage…"))
        openCsvFile()
        registerSensors()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        try {
            fileWriter?.flush()
            fileWriter?.close()
            Log.d(TAG, "✅ Fichier fermé — $sampleCount échantillons enregistrés")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur fermeture fichier", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Fichier CSV
    // ─────────────────────────────────────────────────────────────────────────

    private fun openCsvFile() {
        try {
            // Dossier de sortie sur le stockage de la montre
            val outputDir = getExternalFilesDir("watch_recordings")
            outputDir?.mkdirs()

            val timestamp = System.currentTimeMillis()
            val fileName  = "$currentLabel.$timestamp.csv"
            val file      = File(outputDir, fileName)

            fileWriter = FileWriter(file)
            fileWriter?.write("timestamp,accX,accY,accZ,gyrX,gyrY,gyrZ\n")

            Log.d(TAG, "📄 Fichier ouvert : ${file.absolutePath}")
            updateNotification("$currentLabel — enregistrement…")

        } catch (e: Exception) {
            Log.e(TAG, "Erreur ouverture fichier CSV", e)
            stopSelf()
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

        // SENSOR_DELAY_GAME ≈ 50Hz — même réglage que MealDetectionService
        sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, gyr, SensorManager.SENSOR_DELAY_GAME)
        Log.d(TAG, "Capteurs enregistrés à ~50Hz")
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
                val timestampMs = event.timestamp / 1_000_000L
                writeSample(
                    timestampMs,
                    accValues[0], accValues[1], accValues[2],
                    event.values[0], event.values[1], event.values[2]
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun writeSample(
        ts: Long,
        aX: Float, aY: Float, aZ: Float,
        gX: Float, gY: Float, gZ: Float
    ) {
        try {
            fileWriter?.write("$ts,$aX,$aY,$aZ,$gX,$gY,$gZ\n")
            sampleCount++

            // Flush toutes les 50 lignes (~1 seconde) + update notification
            if (sampleCount % 50 == 0) {
                fileWriter?.flush()
                updateNotification("$currentLabel — $sampleCount échantillons")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur écriture ligne $sampleCount", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification foreground
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Collecte montre",
                NotificationManager.IMPORTANCE_LOW
            ).also {
                getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(it)
            }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("⏺ Collecte en cours")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    // ─────────────────────────────────────────────────────────────────────────
    companion object {
        private const val TAG             = "WatchDataCollection"
        const  val EXTRA_LABEL            = "label"
        private const val NOTIF_ID        = 30
        private const val NOTIF_CHANNEL_ID = "watch_collection"
    }
}
