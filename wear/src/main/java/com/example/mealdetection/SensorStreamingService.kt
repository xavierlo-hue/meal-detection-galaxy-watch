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
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

class SensorStreamingService : Service(), SensorEventListener {

    // ── Capteurs ──────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager

    @Volatile private var accX = 0f
    @Volatile private var accY = 0f
    @Volatile private var accZ = 0f
    @Volatile private var accReady = false

    // ── Streaming ─────────────────────────────────────────────────────────────
    // @Volatile obligatoire : onSensorChanged tourne sur un thread différent
    // de connectAndStartStreaming — sans @Volatile, outputStream reste "null"
    // pour le thread capteur même après initialisation
    @Volatile private var outputStream: DataOutputStream? = null
    private var sampleCount = 0

    // ── Coroutines ────────────────────────────────────────────────────────────
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    // ── Label courant ─────────────────────────────────────────────────────────
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
        Log.d(TAG, "Démarrage du service avec label : $currentLabel")
        startForeground(NOTIFICATION_ID, buildNotification("Connexion au téléphone…"))
        serviceScope.launch { connectAndStartStreaming() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        serviceScope.launch {
            try {
                outputStream?.flush()
                outputStream?.close()
                Log.d(TAG, "Stream fermé proprement — $sampleCount échantillons envoyés")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur à la fermeture du stream", e)
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Connexion Data Layer
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun connectAndStartStreaming() {
        try {
            val nodes = Tasks.await(
                Wearable.getNodeClient(applicationContext).connectedNodes
            )
            if (nodes.isEmpty()) {
                Log.e(TAG, "Aucun téléphone connecté — arrêt du service")
                updateNotification("Téléphone non trouvé")
                stopSelf()
                return
            }
            val phoneNodeId = nodes.first().id
            Log.d(TAG, "Téléphone trouvé : $phoneNodeId")

            Tasks.await(
                Wearable.getMessageClient(applicationContext).sendMessage(
                    phoneNodeId,
                    PATH_START_RECORDING,
                    currentLabel.toByteArray(Charsets.UTF_8)
                )
            )
            Log.d(TAG, "Label '$currentLabel' envoyé au téléphone")

            val channelClient = Wearable.getChannelClient(applicationContext)
            val channel = Tasks.await(
                channelClient.openChannel(phoneNodeId, CHANNEL_PATH)
            )
            val rawStream = Tasks.await(channelClient.getOutputStream(channel))
            outputStream = DataOutputStream(rawStream.buffered())
            Log.d(TAG, "Canal '$CHANNEL_PATH' ouvert")

            withContext(Dispatchers.Main) { registerSensors() }
            updateNotification("$currentLabel — en cours…")

        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de la connexion Data Layer", e)
            updateNotification("Erreur de connexion")
            stopSelf()
        }
    }

    private fun registerSensors() {
        val acc = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gyr = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (acc == null || gyr == null) {
            Log.e(TAG, "Capteurs introuvables sur cet appareil")
            stopSelf()
            return
        }

        val accOk = sensorManager.registerListener(
            this, acc, SensorManager.SENSOR_DELAY_GAME
        )
        val gyrOk = sensorManager.registerListener(
            this, gyr, SensorManager.SENSOR_DELAY_GAME
        )

        if (!accOk || !gyrOk) {
            Log.e(TAG, "Échec enregistrement capteurs — permission BODY_SENSORS refusée ? acc=$accOk gyr=$gyrOk")
            stopSelf()
            return
        }
        Log.d(TAG, "Capteurs enregistrés OK — acc=$accOk gyr=$gyrOk")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SensorEventListener
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                accX = event.values[0]
                accY = event.values[1]
                accZ = event.values[2]
                accReady = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!accReady) return
                val timestampMs = event.timestamp / 1_000_000L
                writeFrame(
                    timestampMs,
                    accX, accY, accZ,
                    event.values[0], event.values[1], event.values[2]
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Écriture trame binaire (32 octets)
    // ─────────────────────────────────────────────────────────────────────────

    private fun writeFrame(
        timestampMs: Long,
        aX: Float, aY: Float, aZ: Float,
        gX: Float, gY: Float, gZ: Float
    ) {
        outputStream?.let { dos ->
            try {
                dos.writeLong(timestampMs)
                dos.writeFloat(aX); dos.writeFloat(aY); dos.writeFloat(aZ)
                dos.writeFloat(gX); dos.writeFloat(gY); dos.writeFloat(gZ)
                sampleCount++

                // Log des 3 premières trames pour confirmer que ça écrit
                if (sampleCount <= 3) {
                    Log.d(TAG, "Trame $sampleCount écrite : ts=$timestampMs acc=[$aX,$aY,$aZ]")
                }

                if (sampleCount % 25 == 0) {
                    dos.flush()
                    Log.d(TAG, "Flush — $sampleCount échantillons envoyés")
                    updateNotification("$currentLabel — $sampleCount échantillons")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur d'écriture trame n°$sampleCount", e)
            }
        } ?: Log.e(TAG, "outputStream est null — trame ignorée (problème @Volatile ?)")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification foreground
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Streaming capteurs",
                NotificationManager.IMPORTANCE_LOW
            ).also {
                getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(it)
            }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Collecte repas")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Companion object — toutes les constantes ici
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "SensorStreaming"
        const val EXTRA_LABEL = "label"
        const val PATH_START_RECORDING = "/start_recording"
        const val CHANNEL_PATH = "/sensor_data"
        private const val NOTIFICATION_ID = 10
        private const val NOTIF_CHANNEL_ID = "sensor_streaming"
    }
}