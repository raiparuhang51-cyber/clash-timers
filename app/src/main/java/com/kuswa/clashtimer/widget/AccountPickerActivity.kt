package com.kuswa.clashtimer.widget

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
