package com.kuswa.clashtimer.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.kuswa.clashtimer.core.AppState
import com.kuswa.clashtimer.core.FinishNotices
import com.kuswa.clashtimer.core.WidgetActions
import com.kuswa.clashtimer.core.WidgetModel
import com.kuswa.clashtimer.core.WidgetModelBuilder
import com.kuswa.clashtimer.core.WidgetSchedule
import com.kuswa.clashtimer.data.Names
import com.kuswa.clashtimer.data.StateRepository
import com.kuswa.clashtimer.data.Times
import com.kuswa.clashtimer.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The home-screen widget (plain RemoteViews). Also the app's single broadcast entry point.
 * Each placed widget belongs to one account (see [AccountPickerActivity]).
 */
class ClashWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context, "widget")

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        refresh(context, "widget")

    override fun onDeleted(context: Context, ids: IntArray) {
        runAsync {
            val repo = StateRepository(context.applicationContext)
            ids.forEach { repo.clearWidget(it) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TICK -> refresh(context, "widget")
            ACTION_FINISH -> refresh(context, "alarm")
            ACTION_TAP -> tap(context, intent)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, ACTION_QUICKBOOT,
            -> refresh(context, "system")
            else -> super.onReceive(context, intent)
        }
    }

    override fun onDisabled(context: Context) = WidgetAlarm.cancelRedraw(context)

    private fun refresh(context: Context, source: String) = runAsync { WidgetUpdater.updateAll(context, source) }

    /** A widget button: `clashtimer://tap/<action>/<arg>/<accountId>`. */
    private fun tap(context: Context, intent: Intent) {
        val segments = intent.data?.pathSegments.orEmpty()
        val action = segments.getOrNull(0) ?: return
        val arg = segments.getOrNull(1) ?: return
        val accountId = segments.getOrNull(2) ?: StateRepository.DEFAULT_ID
        runAsync {
            val app = context.applicationContext
            val repo = StateRepository(app)
            try {
                val settings = repo.currentSettings()
                val now = System.currentTimeMillis() / 1000
                val line = repo.updateState(accountId) { before ->
                    val after = WidgetActions.apply(before, action, arg, now, settings.boosts)
                    after to WidgetActions.describe(before, after, action, arg, Names.get(app))
                }
                repo.log("tap", null, line)
            } catch (t: Throwable) {
                repo.log("tap", false, "Tap failed: ${t.javaClass.simpleName}: ${t.message}")
            }
            WidgetUpdater.updateAll(app, "tap")
        }
    }

    private fun runAsync(block: suspend () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                block()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TICK = "com.kuswa.clashtimer.WIDGET_TICK"
        const val ACTION_FINISH = "com.kuswa.clashtimer.JOB_FINISH"
        const val ACTION_TAP = "com.kuswa.clashtimer.WIDGET_TAP"
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

object WidgetUpdater {

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ClashWidgetReceiver::class.java))

    /**
     * Notifies for every account, renders each widget from its own account, and schedules the
     * redraw alarm and the finish alarm (earliest across all accounts).
     * Returns the number of widgets, or -1 on failure (logged; never throws).
     */
    suspend fun updateAll(context: Context, source: String): Int {
        val app = context.applicationContext
        val repo = StateRepository(app)
        return try {
            val settings = repo.currentSettings()
            val names = Names.get(app)
            val nowMs = System.currentTimeMillis()
            val now = nowMs / 1000
            val accounts = repo.accounts()
            val multi = accounts.size > 1

            val states = HashMap<String, AppState>()
            val finishTimes = mutableListOf<Long>()
            for (acc in accounts) {
                val prefix = if (multi) acc.name else null
                val tag = if (multi) "[${acc.name}] " else ""
                try {
                    val notices = repo.updateState(acc.id) { state ->
                        val r = FinishNotices.check(state, settings, names, now)
                        val fresh = r.notices.filter { it.jobKey !in state.notified }
                        state.copy(notified = r.notified) to fresh
                    }
                    if (notices.isNotEmpty()) {
                        if (settings.notificationsEnabled) {
                            val posted = Notifier.post(app, notices, acc.id, prefix)
                            repo.log(source, null, tag + (if (posted) "Notified: " + notices.joinToString("; ") { it.title }
                                else "Not notified (notifications are blocked for the app): " + notices.joinToString("; ") { it.title }))
                        } else {
                            repo.log(source, null, tag + "Finished (notifications off): " + notices.joinToString("; ") { it.title })
                        }
                    }
                } catch (t: Throwable) {
                    repo.log(source, false, tag + "Notification failed: ${t.javaClass.simpleName}: ${t.message}")
                }
                val state = repo.currentState(acc.id)
                states[acc.id] = state
                FinishNotices.alarmAt(state, settings, now)?.let { finishTimes += it }
            }
            WidgetAlarm.scheduleFinish(app, finishTimes.minOrNull()?.times(1000))

            val manager = AppWidgetManager.getInstance(app)
            val ids = widgetIds(app)
            if (ids.isEmpty()) {
                WidgetAlarm.cancelRedraw(app)
                return 0
            }
            val widgetAccounts = repo.widgetAccounts(ids)
            val models = HashMap<String, WidgetModel>()
            val renderer = WidgetRenderer(app)
            for (id in ids) {
                val acc = accounts.firstOrNull { it.id == widgetAccounts[id] } ?: accounts.first()
                val model = models.getOrPut(acc.id) {
                    WidgetModelBuilder.build(states.getValue(acc.id), settings.copy(playerName = acc.name), names, Times.get(app), now)
                }
                manager.updateAppWidget(
                    id,
                    renderer.render(model, settings.liveCountdown, manager.getAppWidgetOptions(id), acc.id),
                )
            }
            val next = models.values.mapNotNull { WidgetSchedule.nextUpdateMs(it, nowMs, settings.liveCountdown) }.minOrNull()
            WidgetAlarm.scheduleRedraw(app, next)
            ids.size
        } catch (t: Throwable) {
            repo.log(source, false, "Widget refresh failed: ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
    }
}

object WidgetAlarm {
    private const val SLACK_MS = 250L
    private const val MIN_DELAY_MS = 1_000L

    private fun intent(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code,
        Intent(context, ClashWidgetReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    fun scheduleRedraw(context: Context, atMs: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(context, ClashWidgetReceiver.ACTION_TICK, 1)
        if (atMs == null) return am.cancel(pi)
        val at = maxOf(atMs + SLACK_MS, System.currentTimeMillis() + MIN_DELAY_MS)
        try {
            if (canScheduleExact(context)) am.setExact(AlarmManager.RTC, at, pi)
            else am.setWindow(AlarmManager.RTC, at, 60_000L, pi)
        } catch (e: SecurityException) {
            am.setWindow(AlarmManager.RTC, at, 60_000L, pi)
        }
    }

    fun scheduleFinish(context: Context, atMs: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(context, ClashWidgetReceiver.ACTION_FINISH, 2)
        if (atMs == null) return am.cancel(pi)
        val at = maxOf(atMs + SLACK_MS, System.currentTimeMillis() + MIN_DELAY_MS)
        try {
            if (canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancelRedraw(context: Context) = scheduleRedraw(context, null)
}
