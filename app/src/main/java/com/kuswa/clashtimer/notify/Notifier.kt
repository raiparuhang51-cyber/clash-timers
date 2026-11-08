package com.kuswa.clashtimer.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kuswa.clashtimer.MainActivity
import com.kuswa.clashtimer.R
import com.kuswa.clashtimer.core.FinishNotice
import com.kuswa.clashtimer.data.StateRepository

/** "Builder free: Inferno Tower" / "Lab free" / "Pet house free". */
object Notifier {
    const val CHANNEL_ID = "finished"
    private const val CLASH_PACKAGE = "com.supercell.clashofclans"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Upgrade finished", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A builder, the lab or the pet house became free"
            },
        )
    }

    /** True if the app may post notifications (runtime permission on Android 13+, and not blocked in settings). */
    fun allowed(context: Context): Boolean {
        val permission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permission && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Posts one notification per finished job; returns false if notifications are blocked. */
    fun post(context: Context, notices: List<FinishNotice>, accountId: String = StateRepository.DEFAULT_ID, prefix: String? = null): Boolean {
        if (!allowed(context)) return false
        ensureChannel(context)
        val nm = NotificationManagerCompat.from(context)
        for (n in notices) {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_timer)
                .setContentTitle(if (prefix != null) "$prefix: ${n.title}" else n.title)
                .setContentText(n.text)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openIntent(context))
                .build()
            try {
                nm.notify("$accountId/${n.jobKey}".hashCode(), notification)
            } catch (e: SecurityException) {
                return false
            }
        }
        return true
    }

    /** Tapping a notification opens Clash of Clans if it's installed, otherwise this app. */
    private fun openIntent(context: Context): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(CLASH_PACKAGE)
            ?: Intent(context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
