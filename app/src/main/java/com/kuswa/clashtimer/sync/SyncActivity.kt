package com.kuswa.clashtimer.sync

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.kuswa.clashtimer.data.StateRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/**
 * Invisible activity launched by the widget's Sync button.
 *
 * Android 10+ only lets the focused app read the clipboard, so the read happens in
 * [onWindowFocusChanged]. Some ROMs are slow (or never) to deliver focus to a translucent
 * window, so there is a fallback that tries anyway after [FOCUS_FALLBACK_MS] and a hard
 * timeout so this never lingers invisibly over the home screen. Every step is written to the
 * sync log shown on the launcher screen, since there is no logcat on most phones.
 */
class SyncActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private val accountId: String get() = intent?.getStringExtra(EXTRA_ACCOUNT) ?: StateRepository.DEFAULT_ID

    private val focusFallback = Runnable { startSync(viaFallback = true) }
    private val hardTimeout = Runnable {
        log(false, "Sync screen timed out without finishing")
        finishQuietly()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log(null, "Sync screen opened from widget")
        handler.postDelayed(hardTimeout, HARD_TIMEOUT_MS)
    }

    override fun onResume() {
        super.onResume()
        if (!started) {
            if (hasWindowFocus()) startSync(viaFallback = false)
            else handler.postDelayed(focusFallback, FOCUS_FALLBACK_MS)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) startSync(viaFallback = false)
    }

    private fun startSync(viaFallback: Boolean) {
        if (started) return
        started = true
        handler.removeCallbacks(focusFallback)

        val text = SyncRunner.readClipboard(this)
        if (viaFallback) log(null, "No window focus after ${FOCUS_FALLBACK_MS} ms; read clipboard anyway")

        lifecycleScope.launch {
            val result = SyncRunner.sync(this@SyncActivity, text, SOURCE, accountId)
            // Shown while this activity is still in front, so ROMs that block background toasts allow it.
            Toast.makeText(this@SyncActivity, result.message, Toast.LENGTH_LONG).show()
            finishQuietly()
        }
    }

    private fun log(ok: Boolean?, message: String) {
        val app = applicationContext
        // NonCancellable: breadcrumbs must survive the activity finishing right after.
        lifecycleScope.launch(NonCancellable) { StateRepository(app).log(SOURCE, ok, message) }
    }

    private fun finishQuietly() {
        handler.removeCallbacksAndMessages(null)
        if (!isFinishing) {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ACCOUNT = "account_id"
        const val SOURCE = "widget"
        const val FOCUS_FALLBACK_MS = 1500L
        const val HARD_TIMEOUT_MS = 8000L
    }
}
