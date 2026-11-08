package com.kuswa.clashtimer.data

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
        return ids.toList().mapNotNull { id -> prefs[widgetKey(id)]?.let { id to it } }.toMap()
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
