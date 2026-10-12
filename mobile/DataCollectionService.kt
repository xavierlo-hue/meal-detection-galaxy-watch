package com.example.repaswatch // TODO: remplacer par le package réel de ton app mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileWriter

/**
 * Service de premier plan qui écoute les canaux de streaming ouverts par la montre
 * et écrit les échantillons accéléromètre/gyroscope reçus dans des fichiers CSV
 * compatibles avec le format d'upload automatique d'Edge Impulse (label.nom.csv).
 *
 * Démarré explicitement depuis MainActivity au début d'une session de collecte,
 * et arrêté quand la session est terminée.
 */
class DataCollectionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var channelClient: ChannelClient
    private lateinit var messageClient: MessageClient

    // Label courant reçu de la montre, mis à jour avant chaque ouverture de canal
    @Volatile
    private var currentLabel: String = "non_etiquette"

    @Volatile
    private var totalSamplesReceived: Int = 0

    // Reçoit le label envoyé par la montre juste avant qu'elle ouvre le canal de streaming
    private val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        if (event.path == PATH_START_RECORDING) {
            currentLabel = String(event.data, Charsets.UTF_8)
            Log.d(TAG, "Label reçu de la montre : $currentLabel")
        }
    }

    // Réagit à l'ouverture/fermeture d'un canal de streaming par la montre
    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            Log.d(TAG, "Canal ouvert, début de session : $currentLabel")
            serviceScope.launch {
                receiveAndWriteCsv(channel)
            }
        }

        override fun onChannelClosed(
            channel: ChannelClient.Channel,
            closeReason: Int,
            appSpecificErrorCode: Int
        ) {
            Log.d(TAG, "Canal fermé (raison=$closeReason)")
        }
    }

    override fun onCreate() {
        super.onCreate()
        channelClient = Wearable.getChannelClient(applicationContext)
        messageClient = Wearable.getMessageClient(applicationContext)

        messageClient.addListener(messageListener)
        channelClient.registerChannelCallback(channelCallback)

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY : si le système tue le service par manque de mémoire,
        // il tentera de le relancer automatiquement
        return START_STICKY
    }

    override fun onDestroy() {
        messageClient.removeListener(messageListener)
        channelClient.unregisterChannelCallback(channelCallback)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Lit le flux binaire reçu de la montre et l'écrit en CSV.
     * Format binaire attendu par échantillon (32 octets) :
     *   8 octets  -> timestamp (Long, millisecondes)
     *   4 octets  -> accX (Float)
     *   4 octets  -> accY (Float)
     *   4 octets  -> accZ (Float)
     *   4 octets  -> gyrX (Float)
     *   4 octets  -> gyrY (Float)
     *   4 octets  -> gyrZ (Float)
     */
    private suspend fun receiveAndWriteCsv(channel: ChannelClient.Channel) {
        val label = currentLabel
        val timestamp = System.currentTimeMillis()
        val outputDir = getExternalFilesDir("recordings")
        val outputFile = File(outputDir, "$label.$timestamp.csv")

        var localCount = 0

        try {
            val inputStream = Tasks.await(channelClient.getInputStream(channel))
            val dataInput = DataInputStream(BufferedInputStream(inputStream))

            FileWriter(outputFile).use { writer ->
                writer.append("timestamp,accX,accY,accZ,gyrX,gyrY,gyrZ\n")
                try {
                    while (true) {
                        val ts = dataInput.readLong()
                        val accX = dataInput.readFloat()
                        val accY = dataInput.readFloat()
                        val accZ = dataInput.readFloat()
                        val gyrX = dataInput.readFloat()
                        val gyrY = dataInput.readFloat()
                        val gyrZ = dataInput.readFloat()
                        writer.append("$ts,$accX,$accY,$accZ,$gyrX,$gyrY,$gyrZ\n")
                        localCount++
                    }
                } catch (eof: EOFException) {
                    // Fin normale du flux : la montre a fermé le canal, on arrête de lire
                }
            }
            dataInput.close()
            totalSamplesReceived += localCount
            Log.d(TAG, "Session '$label' enregistrée : $localCount échantillons -> ${outputFile.name}")
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur pendant la réception du flux", e)
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "data_collection_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Collecte de données",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Collecte en cours")
            .setContentText("Échantillons reçus : $totalSamplesReceived")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val TAG = "DataCollectionService"
        private const val NOTIFICATION_ID = 42
        const val PATH_START_RECORDING = "/start_recording"
    }
}
