package com.example.mealdetection // TODO: remplacer par ton package

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Activité principale — version avec panneau paramètres.
 *
 * Écran principal :
 *   - Statut de détection (plage auto ou attente)
 *   - Switch détection manuelle (hors plage uniquement)
 *   - Bouton ⚙ PARAMÈTRES (ouvre/ferme le panneau)
 *
 * Panneau paramètres :
 *   - Switch Mode Vacances (plages identiques tous les jours)
 *   - Réglage du seuil de détection [−] N [+]
 *
 * Mode collecte : 5 taps sur le titre (développeur uniquement)
 */
class WearMainActivity : ComponentActivity() {

    // ── Machine d'états ───────────────────────────────────────────────────────
    private enum class State { IN_SCHEDULE, WAITING, MANUAL }
    private var currentState = State.WAITING

    // ── Préférences persistantes ──────────────────────────────────────────────
    private lateinit var prefs: SharedPreferences

    // ── Vues principales ──────────────────────────────────────────────────────
    private lateinit var tvStatus:         TextView
    private lateinit var switchManual:     Switch
    private lateinit var manualSection:    LinearLayout

    // ── Panneau paramètres ────────────────────────────────────────────────────
    private lateinit var paramsPanel:      LinearLayout
    private lateinit var switchVacances:   Switch
    private lateinit var tvThreshold:      TextView
    private var currentThreshold:         Int = 9
    private var paramsVisible:            Boolean = false

    // ── Mode collecte (caché) ─────────────────────────────────────────────────
    private lateinit var collectSection:   LinearLayout
    private lateinit var radioGroup:       RadioGroup
    private lateinit var btnToggleCollect: Button
    private var isCollectionRunning:      Boolean = false
    private var selectedLabel:            String = LABELS[0]
    private var titleTapCount:            Int = 0
    private var collectVisible:           Boolean = false

    // ── Vérification périodique de l'état ─────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val stateChecker = object : Runnable {
        override fun run() {
            refreshState()
            handler.postDelayed(this, 60_000L)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cycle de vie
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("meal_prefs", MODE_PRIVATE)
        MealScheduleReceiver.isVacancesMode = prefs.getBoolean("vacances_mode", false)
        currentThreshold = prefs.getInt("threshold", MealDetectionService.EPISODE_THRESHOLD_DEFAULT)
        MealDetectionService.episodeThreshold = currentThreshold
        MealDetectionService.confidenceThreshold = prefs.getFloat("confidence_threshold", 0.97f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        buildUI()
        MealScheduleReceiver.scheduleAll(this)
        initializeDetection()
        handler.postDelayed(stateChecker, 60_000L)
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        // Sync switch vacances avec la préférence sauvegardée
        switchVacances.isChecked = prefs.getBoolean("vacances_mode", false)
    }

    override fun onDestroy() {
        handler.removeCallbacks(stateChecker)
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Initialisation et états
    // ─────────────────────────────────────────────────────────────────────────

    private fun initializeDetection() {
        stopService(Intent(this, MealDetectionService::class.java))
        if (MealScheduleReceiver.isCurrentlyInSchedule()) {
            startForegroundService(Intent(this, MealDetectionService::class.java))
            setState(State.IN_SCHEDULE)
        } else {
            setState(State.WAITING)
        }
    }

    private fun refreshState() {
        val inSchedule = MealScheduleReceiver.isCurrentlyInSchedule()
        when {
            inSchedule && currentState != State.IN_SCHEDULE -> {
                if (currentState == State.MANUAL) {
                    stopService(Intent(this, MealDetectionService::class.java))
                }
                startForegroundService(Intent(this, MealDetectionService::class.java))
                setState(State.IN_SCHEDULE)
            }
            !inSchedule && currentState == State.IN_SCHEDULE -> {
                setState(State.WAITING)
            }
        }
    }

    private fun setState(state: State) {
        currentState = state
        when (state) {
            State.IN_SCHEDULE -> {
                manualSection.visibility  = View.VISIBLE
                switchManual.isChecked    = false
                switchManual.isEnabled    = false
                val modeLabel = if (prefs.getBoolean("vacances_mode", false)) "🌴 Vacances" else "Auto"
                tvStatus.text = "✅ Détection $modeLabel active\n${MealScheduleReceiver.currentPlageLabel()}"
                tvStatus.setTextColor(Color.parseColor("#81C784"))
            }
            State.WAITING -> {
                manualSection.visibility  = View.VISIBLE
                switchManual.isChecked    = false
                switchManual.isEnabled    = true
                tvStatus.text = "⏳ Prochaine détection :\n${MealScheduleReceiver.nextPlageStartLabel()}"
                tvStatus.setTextColor(Color.LTGRAY)
            }
            State.MANUAL -> {
                manualSection.visibility  = View.VISIBLE
                switchManual.isChecked    = true
                switchManual.isEnabled    = true
                tvStatus.text = "✅ Détection manuelle activée"
                tvStatus.setTextColor(Color.parseColor("#81C784"))
            }
        }
    }

    private fun startManualDetection() {
        startForegroundService(Intent(this, MealDetectionService::class.java))
        setState(State.MANUAL)
    }

    private fun stopManualDetection() {
        stopService(Intent(this, MealDetectionService::class.java))
        setState(State.WAITING)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Construction de l'interface
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(16, 28, 16, 16)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // ── Titre (5 taps → mode collecte) ────────────────────────────────────
        root.addView(TextView(this).apply {
            text = "Meal Detection"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
            setOnClickListener {
                titleTapCount++
                if (titleTapCount >= 5) {
                    titleTapCount  = 0
                    collectVisible = !collectVisible
                    collectSection.visibility = if (collectVisible) View.VISIBLE else View.GONE
                    if (collectVisible) {
                        tvStatus.text = "Mode collecte activé"
                        tvStatus.setTextColor(Color.parseColor("#FFB74D"))
                    } else {
                        refreshState()
                    }
                }
            }
        })

        // ── Statut ─────────────────────────────────────────────────────────────
        tvStatus = TextView(this).apply {
            text = "Initialisation..."
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        root.addView(tvStatus)

        spacer(root, 6)

        // ── Section détection manuelle (switch) ───────────────────────────────
        manualSection = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
            visibility  = View.GONE
        }
        manualSection.addView(TextView(this).apply {
            text     = "Détection Manuelle"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(0, 0, 10, 0)
        })
        switchManual = Switch(this).apply {
            isChecked = false
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) startManualDetection() else stopManualDetection()
            }
        }
        manualSection.addView(switchManual)
        root.addView(manualSection)

        spacer(root, 10)

        // ── Bouton PARAMÈTRES ─────────────────────────────────────────────────
        root.addView(Button(this).apply {
            text = "PARAMÈTRES"
            setTextColor(Color.WHITE)
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(130), dpToPx(36)
            ).apply { setMargins(0, 2, 0, 2) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(18).toFloat()
                setColor(Color.parseColor("#37474F"))
            }
            setPadding(dpToPx(12), 0, dpToPx(12), 0)
            setOnClickListener {
                paramsVisible = !paramsVisible
                paramsPanel.visibility = if (paramsVisible) View.VISIBLE else View.GONE
            }
        })

        spacer(root, 8)

        // ═══════════════════════════════════════════════════════════════════
        // PANNEAU PARAMÈTRES (caché par défaut)
        // ═══════════════════════════════════════════════════════════════════
        paramsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            visibility  = View.GONE
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(12, 12, 12, 12)
        }

        // Séparateur titre
        paramsPanel.addView(TextView(this).apply {
            text      = "── Paramètres ──"
            setTextColor(Color.parseColor("#888888"))
            textSize  = 9f
            gravity   = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        })

        // ── Switch Mode Vacances ──────────────────────────────────────────────
        val vacancesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
            setPadding(0, 4, 0, 4)
        }
        vacancesRow.addView(TextView(this).apply {
            text     = "Plages horaires vacances"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(0, 0, 10, 0)
        })
        switchVacances = Switch(this).apply {
            isChecked = prefs.getBoolean("vacances_mode", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("vacances_mode", isChecked).apply()
                MealScheduleReceiver.isVacancesMode = isChecked
                MealScheduleReceiver.scheduleAll(this@WearMainActivity)
                val msg = if (isChecked) "Plages horaires Vacances activées" else "Plages horaires Normales activées"
                tvStatus.text = msg
                tvStatus.setTextColor(
                    if (isChecked) Color.parseColor("#FFB74D") else Color.parseColor("#81C784")
                )
                // Redémarre le service si dans une plage active
                if (MealScheduleReceiver.isCurrentlyInSchedule()) {
                    stopService(Intent(this@WearMainActivity, MealDetectionService::class.java))
                    startForegroundService(Intent(this@WearMainActivity, MealDetectionService::class.java))
                    setState(State.IN_SCHEDULE)
                } else {
                    setState(State.WAITING)
                }
            }
        }
        vacancesRow.addView(switchVacances)
        paramsPanel.addView(vacancesRow)

        // Horaires vacances en petit texte
        paramsPanel.addView(TextView(this).apply {
            text     = "9h-11h · 13h-15h30 · 19h30-22h"
            setTextColor(Color.parseColor("#FFB74D"))
            textSize = 8f
            gravity  = Gravity.CENTER
            setPadding(0, 2, 0, 10)
        })
// ── Réglage du seuil de confiance CNN ────────────────────────────────────
        paramsPanel.addView(TextView(this).apply {
            text     = "Seuil de confiance CNN"
            setTextColor(Color.parseColor("#888888"))
            textSize = 9f
            gravity  = Gravity.CENTER
            setPadding(0, 8, 0, 6)
        })

        var currentConfidence = (prefs.getFloat("confidence_threshold", 0.97f) * 100).toInt()
        MealDetectionService.confidenceThreshold = currentConfidence / 100f

        val tvConfidence = TextView(this).apply {
            text     = "0,${"${currentConfidence}".padStart(2, '0')}"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(70), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val confidenceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
        }

        val btnConfMinus = Button(this).apply {
            text     = "−"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape    = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#37474F"))
            }
            setOnClickListener {
                if (currentConfidence > 75) {
                    currentConfidence--
                    tvConfidence.text = "0,${"${currentConfidence}".padStart(2, '0')}"
                    MealDetectionService.confidenceThreshold = currentConfidence / 100f
                    prefs.edit().putFloat("confidence_threshold", currentConfidence / 100f).apply()
                }
            }
        }

        val btnConfPlus = Button(this).apply {
            text     = "+"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape    = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#37474F"))
            }
            setOnClickListener {
                if (currentConfidence < 100) {
                    currentConfidence++
                    tvConfidence.text = "0,${"${currentConfidence}".padStart(2, '0')}"
                    MealDetectionService.confidenceThreshold = currentConfidence / 100f
                    prefs.edit().putFloat("confidence_threshold", currentConfidence / 100f).apply()
                }
            }
        }

        confidenceRow.addView(btnConfMinus)
        confidenceRow.addView(tvConfidence)
        confidenceRow.addView(btnConfPlus)
        paramsPanel.addView(confidenceRow)

        paramsPanel.addView(TextView(this).apply {
            text     = "min 0,75 — max 1,00"
            setTextColor(Color.parseColor("#555555"))
            textSize = 8f
            gravity  = Gravity.CENTER
            setPadding(0, 4, 0, 8)
        })
        // Séparateur
        paramsPanel.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.setMargins(0, 4, 0, 10) }
        })

        // ── Réglage du seuil ──────────────────────────────────────────────────
        paramsPanel.addView(TextView(this).apply {
            text     = "Seuil de détection"
            setTextColor(Color.parseColor("#888888"))
            textSize = 9f
            gravity  = Gravity.CENTER
            setPadding(0, 0, 0, 6)
        })

        val thresholdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
        }

        val btnMinus = Button(this).apply {
            text     = "−"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape    = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#37474F"))
            }
            setOnClickListener {
                if (currentThreshold > 3) {
                    currentThreshold--
                    tvThreshold.text = "$currentThreshold"
                    MealDetectionService.episodeThreshold = currentThreshold
                    prefs.edit().putInt("threshold", currentThreshold).apply()
                }
            }
        }

        tvThreshold = TextView(this).apply {
            text     = "$currentThreshold"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity  = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(60), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val btnPlus = Button(this).apply {
            text     = "+"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape    = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#37474F"))
            }
            setOnClickListener {
                if (currentThreshold < 15) {
                    currentThreshold++
                    tvThreshold.text = "$currentThreshold"
                    MealDetectionService.episodeThreshold = currentThreshold
                    prefs.edit().putInt("threshold", currentThreshold).apply()
                }
            }
        }

        thresholdRow.addView(btnMinus)
        thresholdRow.addView(tvThreshold)
        thresholdRow.addView(btnPlus)
        paramsPanel.addView(thresholdRow)

        paramsPanel.addView(TextView(this).apply {
            text     = "min 3 — max 15"
            setTextColor(Color.parseColor("#555555"))
            textSize = 8f
            gravity  = Gravity.CENTER
            setPadding(0, 4, 0, 0)
        })

        root.addView(paramsPanel)

        spacer(root, 8)

        // ═══════════════════════════════════════════════════════════════════
        // SECTION COLLECTE (5 taps sur le titre)
        // ═══════════════════════════════════════════════════════════════════
        collectSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            visibility  = View.GONE
        }
        collectSection.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.setMargins(0, 8, 0, 8) }
        })
        collectSection.addView(TextView(this).apply {
            text = "🔧 MODE COLLECTE"
            setTextColor(Color.parseColor("#FFB74D"))
            textSize = 9f
            gravity  = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 6)
        })
        radioGroup = RadioGroup(this).apply {
            orientation  = RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        LABELS.forEachIndexed { index, label ->
            radioGroup.addView(RadioButton(this).apply {
                id        = index + 100
                text      = label.replace("_", " ")
                tag       = label
                setTextColor(Color.WHITE)
                textSize  = 9f
                isChecked = (index == 0)
            })
        }
        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            selectedLabel = (group.findViewById<RadioButton>(checkedId)?.tag as? String) ?: LABELS[0]
        }
        collectSection.addView(radioGroup)
        spacer(collectSection, 6)
        btnToggleCollect = Button(this).apply {
            text = "⏺ ENREGISTRER"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(dpToPx(120), dpToPx(34))
                .apply { setMargins(0, 2, 0, 2) }
            setOnClickListener { toggleCollection() }
        }
        collectSection.addView(btnToggleCollect)
        spacer(collectSection, 4)
        collectSection.addView(TextView(this).apply {
            text = "adb pull ...watch_recordings/"
            setTextColor(Color.parseColor("#444444"))
            textSize = 7f
            gravity  = Gravity.CENTER
        })
        root.addView(collectSection)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Collecte de données
    // ─────────────────────────────────────────────────────────────────────────

    private fun toggleCollection() {
        if (!isCollectionRunning) {
            startForegroundService(
                Intent(this, WatchDataCollectionService::class.java).apply {
                    putExtra(WatchDataCollectionService.EXTRA_LABEL, selectedLabel)
                }
            )
            isCollectionRunning   = true
            btnToggleCollect.text = "⏹ STOP"
            btnToggleCollect.setBackgroundColor(Color.parseColor("#E65100"))
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            stopService(Intent(this, WatchDataCollectionService::class.java))
            isCollectionRunning   = false
            btnToggleCollect.text = "⏺ ENREGISTRER"
            btnToggleCollect.setBackgroundColor(Color.parseColor("#2E7D32"))
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilitaires UI
    // ─────────────────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun spacer(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(heightDp)
            )
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    companion object {
        val LABELS = listOf(
            "repas_fourchette", "repas_cuillere", "repas_sandwich",
            "repas_fruit", "repas_baguettes", "repas_debout",
            "boire_verre", "boire_bouteille",
            "autre_boire_eau", "autre_telephone", "autre_brossage",
            "autre_marche", "autre_courrir", "autre_ecriture",
            "autre_visage", "autre_lunettes", "autre_cheveux",
            "autre_ordinateur", "autre_repos", "autre_lavage_mains",
            "autre_telephone_oreille", "autre_telephone_regard",
            "autre_visage_gratter", "autre_visage_lunettes",
            "autre_bureau_long", "autre_maison_long","autre_magasin_courses",
            "autre_deplacement_long", "repas_dejeuner_long",
            "repas_diner_long", "repas_petit_dej_long"
        )
    }
}
