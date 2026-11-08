package com.kuswa.clashtimer.sync

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
