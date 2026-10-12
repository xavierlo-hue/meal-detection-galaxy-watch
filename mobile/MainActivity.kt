package com.example.repaswatch // TODO: remplacer par le package réel de ton app mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Écran minimal pour piloter la collecte de données pendant la phase
 * d'entraînement du modèle. Pas d'UI soignée ici : c'est un outil de dev,
 * pas l'app finale.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                // Sur Android 13+, sans cette permission la notification du
                // foreground service ne s'affichera pas, mais le service tournera quand même
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val statusText = TextView(this).apply {
            text = "Service arrêté"
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }

        val startButton = Button(this).apply {
            text = "Démarrer la collecte"
            setOnClickListener {
                startForegroundService(Intent(this@MainActivity, DataCollectionService::class.java))
                statusText.text = "Service actif — prêt à recevoir des sessions de la montre"
            }
        }

        val stopButton = Button(this).apply {
            text = "Arrêter la collecte"
            setOnClickListener {
                stopService(Intent(this@MainActivity, DataCollectionService::class.java))
                statusText.text = "Service arrêté"
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "Collecte de données IMU — repas vs autre"
                    textSize = 18f
                    setPadding(32, 64, 32, 32)
                })
                addView(startButton)
                addView(stopButton)
                addView(statusText)
            }
        )
    }
}
