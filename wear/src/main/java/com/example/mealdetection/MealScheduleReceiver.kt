package com.example.mealdetection // TODO: remplacer par ton package

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

/**
 * BroadcastReceiver — gestion des plages horaires automatiques.
 *
 * Deux modes sélectionnables depuis l'app :
 *
 *   Normal   → SCHEDULE_BY_DAY  (semaine ≠ week-end)
 *   Vacances → SCHEDULE_VACANCES (identique tous les jours)
 *              9h-11h · 13h-15h30 · 19h30-22h
 */
class MealScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> {
                Log.d(TAG, "⏰ Plage démarrée — activation détection")
                context.startForegroundService(
                    Intent(context, MealDetectionService::class.java)
                )
                scheduleAll(context)
            }
            ACTION_STOP -> {
                Log.d(TAG, "⏰ Plage terminée — arrêt détection")
                context.stopService(Intent(context, MealDetectionService::class.java))
                scheduleAll(context)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "🔄 Redémarrage — reprogrammation des alarmes")
                scheduleAll(context)
            }
        }
    }

    companion object {
        private const val TAG = "MealSchedule"

        const val ACTION_START = "com.example.mealdetection.SCHEDULE_START"
        const val ACTION_STOP  = "com.example.mealdetection.SCHEDULE_STOP"

        // ── Planning semaine normale ──────────────────────────────────────────
        private val SCHEDULE_BY_DAY = mapOf(
            Calendar.MONDAY    to listOf(
                Pair(Pair(6, 20),  Pair(8, 30)),
                Pair(Pair(12, 30), Pair(15, 0)),
                Pair(Pair(19, 30), Pair(21, 30))
            ),
            Calendar.TUESDAY   to listOf(
                Pair(Pair(6, 20),  Pair(8, 30)),
                Pair(Pair(12, 30), Pair(15, 0)),
                Pair(Pair(19, 30), Pair(21, 30))
            ),
            Calendar.WEDNESDAY to listOf(
                Pair(Pair(6, 20),  Pair(8, 30)),
                Pair(Pair(12, 30), Pair(15, 0)),
                Pair(Pair(19, 30), Pair(21, 30))
            ),
            Calendar.THURSDAY  to listOf(
                Pair(Pair(6, 20),  Pair(8, 30)),
                Pair(Pair(12, 30), Pair(15, 0)),
                Pair(Pair(19, 30), Pair(21, 30))
            ),
            Calendar.FRIDAY    to listOf(
                Pair(Pair(6, 20),  Pair(8, 30)),
                Pair(Pair(12, 30), Pair(15, 0)),
                Pair(Pair(19, 30), Pair(21, 30))
            ),
            Calendar.SATURDAY  to listOf(
                Pair(Pair(9, 0),   Pair(11, 0)),
                Pair(Pair(13, 0),  Pair(15, 30)),
                Pair(Pair(19, 30), Pair(22, 0))
            ),
            Calendar.SUNDAY    to listOf(
                Pair(Pair(9, 0),   Pair(11, 0)),
                Pair(Pair(13, 0),  Pair(15, 30)),
                Pair(Pair(19, 30), Pair(22, 0))
            )
        )

        // ── Planning vacances (identique tous les jours) ──────────────────────
        private val SCHEDULE_VACANCES = listOf(
            Pair(Pair(9, 0),   Pair(11, 0)),    // Petit-déjeuner
            Pair(Pair(13, 0),  Pair(15, 30)),   // Déjeuner
            Pair(Pair(19, 30), Pair(22, 0))     // Dîner
        )

        // ── Retourne le planning actif selon le mode ──────────────────────────
        private fun activePlages(context: Context, dayOfWeek: Int): List<Pair<Pair<Int,Int>, Pair<Int,Int>>> {
            val vacances = context.getSharedPreferences("meal_prefs", Context.MODE_PRIVATE)
                .getBoolean("vacances_mode", false)
            return if (vacances) SCHEDULE_VACANCES
                   else SCHEDULE_BY_DAY[dayOfWeek] ?: emptyList()
        }

        // ── Programme toutes les alarmes ──────────────────────────────────────
        fun scheduleAll(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            var requestCode  = 0

            // Programme les 7 prochains jours
            val now = Calendar.getInstance()
            for (daysAhead in 0..6) {
                val checkDay = (now.get(Calendar.DAY_OF_WEEK) - 1 + daysAhead) % 7 + 1
                val plages   = activePlages(context, checkDay)

                plages.forEach { plage ->
                    val startH = plage.first.first
                    val startM = plage.first.second
                    val stopH  = plage.second.first
                    val stopM  = plage.second.second

                    val startTime = nextOccurrenceInDays(startH, startM, daysAhead)
                    val stopTime  = nextOccurrenceInDays(stopH,  stopM,  daysAhead)

                    val startIntent = PendingIntent.getBroadcast(
                        context, requestCode++,
                        Intent(context, MealScheduleReceiver::class.java)
                            .apply { action = ACTION_START },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val stopIntent = PendingIntent.getBroadcast(
                        context, requestCode++,
                        Intent(context, MealScheduleReceiver::class.java)
                            .apply { action = ACTION_STOP },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    // Ne programme que les alarmes futures
                    if (startTime > System.currentTimeMillis()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, startTime, startIntent
                        )
                    }
                    if (stopTime > System.currentTimeMillis()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, stopTime, stopIntent
                        )
                    }
                }
            }
            val mode = if (context.getSharedPreferences("meal_prefs", Context.MODE_PRIVATE)
                    .getBoolean("vacances_mode", false)) "Vacances" else "Normal"
            Log.d(TAG, "✅ Alarmes programmées — Mode $mode")
        }

        // ── Vérifie si l'heure actuelle est dans une plage active ─────────────
        fun isCurrentlyInSchedule(): Boolean {
            // Utilise un contexte statique — non disponible ici, géré via SharedPreferences
            // Cette version sans contexte utilise un cache statique du mode vacances
            val now     = Calendar.getInstance()
            val nowMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val today   = now.get(Calendar.DAY_OF_WEEK)
            val plages  = cachedActivePlages(today)
            return plages.any { plage ->
                val startMins = plage.first.first  * 60 + plage.first.second
                val stopMins  = plage.second.first * 60 + plage.second.second
                nowMins in startMins until stopMins
            }
        }

        // ── Cache du mode vacances (mis à jour depuis WearMainActivity) ───────
        var isVacancesMode: Boolean = false

        private fun cachedActivePlages(dayOfWeek: Int): List<Pair<Pair<Int,Int>, Pair<Int,Int>>> {
            return if (isVacancesMode) SCHEDULE_VACANCES
                   else SCHEDULE_BY_DAY[dayOfWeek] ?: emptyList()
        }

        // ── Timestamp de fin de la plage en cours ─────────────────────────────
        fun currentPlageEndMs(): Long {
            val now     = Calendar.getInstance()
            val nowMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val today   = now.get(Calendar.DAY_OF_WEEK)
            cachedActivePlages(today).forEach { plage ->
                val startMins = plage.first.first  * 60 + plage.first.second
                val stopMins  = plage.second.first * 60 + plage.second.second
                if (nowMins in startMins until stopMins) {
                    return Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, plage.second.first)
                        set(Calendar.MINUTE,      plage.second.second)
                        set(Calendar.SECOND,      0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                }
            }
            return 0L
        }

        // ── Label de la plage en cours ────────────────────────────────────────
        fun currentPlageLabel(): String {
            val now     = Calendar.getInstance()
            val nowMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val today   = now.get(Calendar.DAY_OF_WEEK)
            cachedActivePlages(today).forEach { plage ->
                val startMins = plage.first.first  * 60 + plage.first.second
                val stopMins  = plage.second.first * 60 + plage.second.second
                if (nowMins in startMins until stopMins) {
                    return "%02dh%02d → %02dh%02d".format(
                        plage.first.first, plage.first.second,
                        plage.second.first, plage.second.second
                    )
                }
            }
            return ""
        }

        // ── Label de la prochaine plage ───────────────────────────────────────
        fun nextPlageStartLabel(): String {
            val now     = Calendar.getInstance()
            val nowMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val today   = now.get(Calendar.DAY_OF_WEEK)
            val dayNames = mapOf(
                Calendar.MONDAY    to "Lun", Calendar.TUESDAY   to "Mar",
                Calendar.WEDNESDAY to "Mer", Calendar.THURSDAY  to "Jeu",
                Calendar.FRIDAY    to "Ven", Calendar.SATURDAY  to "Sam",
                Calendar.SUNDAY    to "Dim"
            )
            for (daysAhead in 0..6) {
                val checkDay = (today - 1 + daysAhead) % 7 + 1
                val plages   = cachedActivePlages(checkDay)
                plages.forEach { plage ->
                    val startMins = plage.first.first * 60 + plage.first.second
                    if (daysAhead == 0 && startMins <= nowMins) return@forEach
                    val label = when (daysAhead) {
                        0    -> "aujourd'hui"
                        1    -> "demain"
                        else -> dayNames[checkDay] ?: ""
                    }
                    return "%s à %02dh%02d".format(label, plage.first.first, plage.first.second)
                }
            }
            return "aucune plage"
        }

        // ── Helpers ───────────────────────────────────────────────────────────
        private fun nextOccurrence(hour: Int, minute: Int): Long {
            val now    = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (target.before(now)) target.add(Calendar.DAY_OF_YEAR, 1)
            return target.timeInMillis
        }

        private fun nextOccurrenceInDays(hour: Int, minute: Int, daysAhead: Int): Long {
            return Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, daysAhead)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
    }
}
