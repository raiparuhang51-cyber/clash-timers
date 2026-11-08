#!/usr/bin/env python3
"""Run from the clash-timers repo root:  python3 apply_multi_account.py"""
import os, sys

B = "app/src/main/java/com/kuswa/clashtimer/"
if not os.path.isdir(B):
    sys.exit("Run this from the clash-timers repo root (cd ~/clash-timers).")


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)
    print("wrote", path)


def patch(path, pairs):
    s = open(path).read()
    for a, b in pairs:
        if b in s:
            continue  # already applied
        n = s.count(a)
        if n != 1:
            sys.exit("FAILED %s: %r found %dx" % (path, a[:70], n))
        s = s.replace(a, b)
    open(path, "w").write(s)
    print("patched", path)


# ---------------------------------------------------------------- new / rewritten files

write(B + "data/Accounts.kt", r'''package com.kuswa.clashtimer.data

import com.kuswa.clashtimer.core.AppState
import kotlinx.serialization.Serializable

@Serializable
data class Account(val id: String, val name: String)

@Serializable
data class AccountList(val accounts: List<Account> = emptyList()) {
    fun encode(): String = AppState.JSON.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String?): AccountList =
            text?.let { runCatching { AppState.JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: AccountList()
    }
}
''')

write(B + "data/StateRepository.kt", r'''package com.kuswa.clashtimer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kuswa.clashtimer.core.AppSettings
import com.kuswa.clashtimer.core.AppState
import com.kuswa.clashtimer.core.NameMap
import com.kuswa.clashtimer.core.SyncLog
import com.kuswa.clashtimer.core.SyncLogEntry
import com.kuswa.clashtimer.core.UpgradeTimes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "clash_timers")

/**
 * All persisted state lives in one Preferences DataStore. The original village is the account
 * [DEFAULT_ID]; extra accounts each get their own `state_json_<id>` key. Settings are shared.
 */
class StateRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val state: Flow<AppState> = stateFlow(DEFAULT_ID)
    val settings: Flow<AppSettings> = store.data.map { AppSettings.decode(it[SETTINGS]) }.distinctUntilChanged()
    val syncLog: Flow<SyncLog> = store.data.map { SyncLog.decode(it[SYNC_LOG]) }.distinctUntilChanged()

    fun stateFlow(accountId: String): Flow<AppState> =
        store.data.map { AppState.decode(it[stateKey(accountId)]) }.distinctUntilChanged()

    suspend fun currentState(accountId: String = DEFAULT_ID): AppState = stateFlow(accountId).first()
    suspend fun currentSettings(): AppSettings = settings.first()

    /** Atomically read-modify-write one account's state; returns the value [transform] produced. */
    suspend fun <R> updateState(accountId: String = DEFAULT_ID, transform: (AppState) -> Pair<AppState, R>): R {
        var result: R? = null
        val key = stateKey(accountId)
        store.edit { prefs ->
            val (next, r) = transform(AppState.decode(prefs[key]))
            prefs[key] = next.encode()
            result = r
        }
        @Suppress("UNCHECKED_CAST")
        return result as R
    }

    // ---- accounts ----

    /** The default account (named from the settings) first, then the extra accounts. */
    suspend fun accounts(): List<Account> {
        val prefs = store.data.first()
        val name = AppSettings.decode(prefs[SETTINGS]).displayName
        return listOf(Account(DEFAULT_ID, name)) + AccountList.decode(prefs[ACCOUNTS]).accounts
    }

    suspend fun addAccount(name: String): Account {
        val clean = AppSettings.cleanName(name).ifEmpty { "Account" }
        val acc = Account("a" + System.currentTimeMillis().toString(36), clean)
        store.edit { prefs ->
            val list = AccountList.decode(prefs[ACCOUNTS])
            prefs[ACCOUNTS] = list.copy(accounts = list.accounts + acc).encode()
        }
        return acc
    }

    suspend fun widgetAccounts(ids: IntArray): Map<Int, String> {
        val prefs = store.data.first()
        return ids.mapNotNull { id -> prefs[widgetKey(id)]?.let { id to it } }.toMap()
    }

    suspend fun setWidgetAccount(widgetId: Int, accountId: String) {
        store.edit { it[widgetKey(widgetId)] = accountId }
    }

    suspend fun clearWidget(widgetId: Int) {
        store.edit { it.remove(widgetKey(widgetId)) }
    }

    /** Appends to the on-screen sync log; never throws (it is diagnostics only). */
    suspend fun log(source: String, ok: Boolean?, message: String) {
        runCatching {
            store.edit { prefs ->
                val entry = SyncLogEntry(System.currentTimeMillis() / 1000, source, ok, message)
                prefs[SYNC_LOG] = SyncLog.decode(prefs[SYNC_LOG]).add(entry).encode()
            }
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> prefs[SETTINGS] = transform(AppSettings.decode(prefs[SETTINGS])).encode() }
    }

    companion object {
        const val DEFAULT_ID = "default"
        private val STATE = stringPreferencesKey("state_json")
        private val SETTINGS = stringPreferencesKey("settings_json")
        private val SYNC_LOG = stringPreferencesKey("sync_log_json")
        private val ACCOUNTS = stringPreferencesKey("accounts_json")

        private fun stateKey(id: String) = if (id == DEFAULT_ID) STATE else stringPreferencesKey("state_json_$id")
        private fun widgetKey(id: Int) = stringPreferencesKey("widget_account_$id")
    }
}

/** Bundled `assets/names.json`, loaded once per process. */
object Names {
    @Volatile private var cached: NameMap? = null

    fun get(context: Context): NameMap = cached ?: synchronized(this) {
        cached ?: context.applicationContext.assets.open("names.json").bufferedReader().use {
            NameMap.fromJson(it.readText())
        }.also { cached = it }
    }
}

/** Bundled `assets/upgrade_times.json` (full upgrade durations for the progress bars), loaded once. */
object Times {
    @Volatile private var cached: UpgradeTimes? = null

    fun get(context: Context): UpgradeTimes = cached ?: synchronized(this) {
        cached ?: runCatching {
            context.applicationContext.assets.open("upgrade_times.json").bufferedReader().use {
                UpgradeTimes.fromJson(it.readText())
            }
        }.getOrDefault(UpgradeTimes.EMPTY).also { cached = it }
    }
}
''')

write(B + "sync/SyncRunner.kt", r'''package com.kuswa.clashtimer.sync

import android.content.ClipboardManager
import android.content.Context
import com.kuswa.clashtimer.core.SyncLogic
import com.kuswa.clashtimer.core.describe
import com.kuswa.clashtimer.data.Names
import com.kuswa.clashtimer.data.StateRepository
import com.kuswa.clashtimer.widget.WidgetUpdater
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One sync pipeline for both the widget's Sync button and the launcher screen's button. */
object SyncRunner {

    data class Result(val ok: Boolean, val message: String)

    fun readClipboard(context: Context): String? = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    } catch (e: Exception) {
        null
    }

    /** Parses [clipboard] into [accountId]'s state, logs the result and refreshes the widgets. Never throws. */
    suspend fun sync(
        context: Context,
        clipboard: String?,
        source: String,
        accountId: String = StateRepository.DEFAULT_ID,
    ): Result = withContext(NonCancellable) {
        val app = context.applicationContext
        val repo = StateRepository(app)
        val label = if (accountId == StateRepository.DEFAULT_ID) "" else
            runCatching { repo.accounts().firstOrNull { it.id == accountId }?.name?.let { "$it: " } }.getOrNull() ?: ""
        val result = try {
            val outcome = repo.updateState(accountId) { old ->
                val o = SyncLogic.apply(old, clipboard)
                o.state to o
            }
            Result(outcome is SyncLogic.Outcome.Synced, label + plainText(outcome.describe(Names.get(app))))
        } catch (t: Throwable) {
            Result(false, "Error: ${t.javaClass.simpleName}: ${t.message}")
        }
        repo.log(source, result.ok, result.message)
        refreshWidgets(app, source)
        result
    }

    suspend fun refreshWidgets(context: Context, source: String): Int {
        val count = WidgetUpdater.updateAll(context, source)
        if (count == 0) StateRepository(context).log(source, null, "No Clash Timers widget is placed on the home screen")
        return count
    }

    fun plainText(s: String): String = s.replace(" → ", " -> ").replace("→", "->")
}
''')

write(B + "widget/AccountPickerActivity.kt", r'''package com.kuswa.clashtimer.widget

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.kuswa.clashtimer.core.AppSettings
import com.kuswa.clashtimer.data.Account
import com.kuswa.clashtimer.data.StateRepository
import kotlinx.coroutines.launch

/** Widget configure screen: choose which account this widget shows, or create a new one. */
class AccountPickerActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val repo = StateRepository(applicationContext)
        lifecycleScope.launch { showPicker(repo, repo.accounts()) }
    }

    private fun showPicker(repo: StateRepository, accounts: List<Account>) {
        val labels = accounts.map { it.name }.toTypedArray() + "+ New account"
        AlertDialog.Builder(this)
            .setTitle("Account for this widget")
            .setItems(labels) { _, which ->
                if (which < accounts.size) done(repo, accounts[which].id) else askName(repo)
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun askName(repo: StateRepository) {
        val input = EditText(this).apply {
            hint = "Account name"
            filters = arrayOf(InputFilter.LengthFilter(AppSettings.MAX_PLAYER_NAME))
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("New account")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                lifecycleScope.launch { done(repo, repo.addAccount(input.text.toString()).id) }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun done(repo: StateRepository, accountId: String) {
        lifecycleScope.launch {
            repo.setWidgetAccount(widgetId, accountId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            WidgetUpdater.updateAll(applicationContext, "picker")
            finish()
        }
    }
}
''')

write(B + "widget/ClashWidget.kt", r'''package com.kuswa.clashtimer.widget

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
''')

write(".github/workflows/build-apk.yml", '''name: Build debug APK
on:
  workflow_dispatch:
  push:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
      - uses: android-actions/setup-android@v3
      - run: chmod +x gradlew && ./gradlew assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: clash-timers-debug
          path: app/build/outputs/apk/debug/*.apk
''')

# ---------------------------------------------------------------- patches to existing files

patch(B + "sync/SyncActivity.kt", [
    ("private var started = false",
     "private var started = false\n    private val accountId: String get() = intent?.getStringExtra(EXTRA_ACCOUNT) ?: StateRepository.DEFAULT_ID"),
    ("SyncRunner.sync(this@SyncActivity, text, SOURCE)",
     "SyncRunner.sync(this@SyncActivity, text, SOURCE, accountId)"),
    ("private companion object {",
     'companion object {\n        const val EXTRA_ACCOUNT = "account_id"'),
])

patch(B + "notify/Notifier.kt", [
    ("import com.kuswa.clashtimer.core.FinishNotice\n",
     "import com.kuswa.clashtimer.core.FinishNotice\nimport com.kuswa.clashtimer.data.StateRepository\n"),
    ("fun post(context: Context, notices: List<FinishNotice>): Boolean {",
     "fun post(context: Context, notices: List<FinishNotice>, accountId: String = StateRepository.DEFAULT_ID, prefix: String? = null): Boolean {"),
    (".setContentTitle(n.title)",
     '.setContentTitle(if (prefix != null) "$prefix: ${n.title}" else n.title)'),
    ("nm.notify(n.jobKey.hashCode(), notification)",
     'nm.notify("$accountId/${n.jobKey}".hashCode(), notification)'),
])

patch(B + "widget/WidgetRenderer.kt", [
    ("import com.kuswa.clashtimer.sync.SyncActivity\n",
     "import com.kuswa.clashtimer.sync.SyncActivity\nimport com.kuswa.clashtimer.data.StateRepository\n"),
    ("private val density = context.resources.displayMetrics.density",
     "private val density = context.resources.displayMetrics.density\n    private var accountId: String = StateRepository.DEFAULT_ID"),
    ("fun render(model: WidgetModel, liveCountdown: Boolean, options: Bundle?): RemoteViews {",
     "fun render(model: WidgetModel, liveCountdown: Boolean, options: Bundle?, accountId: String = StateRepository.DEFAULT_ID): RemoteViews {\n        this.accountId = accountId"),
    (".appendPath(arg).build()", ".appendPath(arg).appendPath(accountId).build()"),
    ("val intent = Intent(context, SyncActivity::class.java).addFlags(",
     'val intent = Intent(context, SyncActivity::class.java)\n            .putExtra(SyncActivity.EXTRA_ACCOUNT, accountId)\n            .setData(Uri.parse("clashtimer://sync/$accountId"))\n            .addFlags('),
])

patch("app/src/main/res/xml/clash_widget_info.xml", [
    ('android:widgetCategory="home_screen" />',
     'android:widgetCategory="home_screen"\n    android:configure="com.kuswa.clashtimer.widget.AccountPickerActivity"\n    android:widgetFeatures="reconfigurable" />'),
])

patch("app/src/main/AndroidManifest.xml", [
    ("<receiver",
     '''<activity
            android:name=".widget.AccountPickerActivity"
            android:exported="true"
            android:excludeFromRecents="true"
            android:taskAffinity=""
            android:theme="@style/Theme.ClashTimer.Invisible">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
            </intent-filter>
        </activity>

        <receiver'''),
])

print("\nAll done. Next: git add -A && git commit, push to your fork, build with GitHub Actions.")
