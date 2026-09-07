package com.phonebridge

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

object MoteWidget {
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, MoteWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val intent = Intent(context, MoteWidgetProvider::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        context.sendBroadcast(intent)
    }
}

class MoteWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> render(context, manager, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, MoteWidgetProvider::class.java))
            .forEach { id -> render(context, manager, id) }
    }

    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val prefs = context.getSharedPreferences("mote_pet", Context.MODE_PRIVATE)
        val level = prefs.getInt("level", 1)
        val energy = prefs.getInt("energy", 82)
        val affection = prefs.getInt("affection", 40)
        val streak = prefs.getInt("careStreak", 0)
        val appearance = PetAppearance.fromWire(prefs.getString("appearance", "MOTE"))
        val profile = MoteProfiles.profile(appearance)
        val mood = runCatching {
            PetMood.valueOf(prefs.getString("mood", "CURIOUS") ?: "CURIOUS")
        }.getOrDefault(PetMood.CURIOUS)
        val moodName = when (mood) {
            PetMood.HAPPY -> "开心"
            PetMood.CURIOUS -> "好奇"
            PetMood.ALERT -> "警觉"
            PetMood.SLEEPY -> "犯困"
            PetMood.LONELY -> "想你"
            else -> "平缓"
        }
        val widgetPrefs = context.getSharedPreferences("mote_widget", Context.MODE_PRIVATE)
        val connected = widgetPrefs.getBoolean("connected", false)
        val fps = widgetPrefs.getInt("fps", 0)
        val battery = widgetPrefs.getInt("battery", 0)
        val activeTasks = widgetPrefs.getInt("activeTasks", 0)
        val views = RemoteViews(context.packageName, R.layout.widget_mote).apply {
            setTextViewText(R.id.widgetTitle, "${profile.name} · Lv.$level")
            setTextViewText(
                R.id.widgetStatus,
                "$moodName · 能量 $energy% · 好感 $affection · 连续陪伴 $streak 天"
            )
            setTextViewText(
                R.id.widgetMeta,
                "${if (connected) "在线" else "离线"} · $fps fps · 电量 $battery% · 任务 $activeTasks"
            )
            setOnClickPendingIntent(R.id.widgetFeed, careIntent(context, "feed"))
            setOnClickPendingIntent(R.id.widgetListen, commandIntent(context, "listen"))
            setOnClickPendingIntent(R.id.widgetCamera, commandIntent(context, "/camera"))
        }
        manager.updateAppWidget(id, views)
    }

    private fun careIntent(context: Context, kind: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            kind.hashCode(),
            Intent(context, MainActivity::class.java).putExtra("auto_care", kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun commandIntent(context: Context, command: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            command.hashCode(),
            Intent(context, MainActivity::class.java).putExtra("auto_command", command),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
