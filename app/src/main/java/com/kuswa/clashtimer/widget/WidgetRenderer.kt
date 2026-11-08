package com.kuswa.clashtimer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import com.kuswa.clashtimer.R
import com.kuswa.clashtimer.core.BoostPill
import com.kuswa.clashtimer.core.WidgetActions
import com.kuswa.clashtimer.core.Countdown
import com.kuswa.clashtimer.core.Finish
import com.kuswa.clashtimer.core.FooterTone
import com.kuswa.clashtimer.core.RowButton
import com.kuswa.clashtimer.core.RowKind
import com.kuswa.clashtimer.core.WidgetModel
import com.kuswa.clashtimer.core.WidgetModelBuilder
import com.kuswa.clashtimer.core.WidgetRow
import com.kuswa.clashtimer.sync.SyncActivity
import com.kuswa.clashtimer.data.StateRepository
import kotlin.math.roundToInt

/** Maps a [WidgetModel] onto `widget_root.xml` + one `widget_row*.xml` per row. */
class WidgetRenderer(private val context: Context) {

    private val pkg = context.packageName
    private val icons = WidgetIcons.get(context)
    private val density = context.resources.displayMetrics.density
    private var accountId: String = StateRepository.DEFAULT_ID

    fun render(model: WidgetModel, liveCountdown: Boolean, options: Bundle?, accountId: String = StateRepository.DEFAULT_ID): RemoteViews {
        this.accountId = accountId
        val rv = RemoteViews(pkg, R.layout.widget_root)
        val sync = syncIntent()
        rv.setOnClickPendingIntent(R.id.header, sync)
        rv.setOnClickPendingIntent(R.id.sync_button, sync)
        rv.setTextViewText(R.id.who, title(model))

        if (!model.hasData) {
            rv.setViewVisibility(R.id.content, View.GONE)
            rv.setViewVisibility(R.id.empty, View.VISIBLE)
            rv.setOnClickPendingIntent(R.id.empty, sync)
            rv.setTextViewText(R.id.empty_text, if (model.syncFailed) WidgetModelBuilder.SYNC_FAILED else WidgetModelBuilder.NO_DATA)
            return rv
        }
        rv.setViewVisibility(R.id.empty, View.GONE)
        rv.setViewVisibility(R.id.content, View.VISIBLE)

        rv.setTextViewText(R.id.builders_label, buildersLabel(model))
        pill(rv, PillIds(R.id.pill_bite, R.id.pill_bite_add, R.id.pill_bite_undo, R.id.pill_bite_icon, R.id.pill_bite_text),
            model.bite, "builder_bite", R.drawable.ic_builder_bite)
        pill(rv, PillIds(R.id.pill_bpot, R.id.pill_bpot_add, R.id.pill_bpot_undo, R.id.pill_bpot_icon, R.id.pill_bpot_text),
            model.builderPotion, "builder_potion", R.drawable.ic_builder_potion)
        pill(rv, PillIds(R.id.pill_rpot, R.id.pill_rpot_add, R.id.pill_rpot_undo, R.id.pill_rpot_icon, R.id.pill_rpot_text),
            model.researchPotion, "research_potion", R.drawable.ic_research_potion)
        pill(rv, PillIds(R.id.pill_ppot, R.id.pill_ppot_add, R.id.pill_ppot_undo, R.id.pill_ppot_icon, R.id.pill_ppot_text),
            model.petPotion, "pet_potion", R.drawable.ic_pet_potion)

        val compact = useCompactRows(options, model.allRows.size)
        val nowMs = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        fun fill(@IdRes container: Int, rows: List<WidgetRow>) {
            rv.removeAllViews(container)
            rows.take(MAX_BUILDER_ROWS).forEach { rv.addView(container, row(it, compact, liveCountdown, nowMs, elapsed)) }
        }
        fill(R.id.rows_builders, model.builderRows)
        fill(R.id.rows_lab, listOf(model.labRow))
        fill(R.id.rows_pet, listOf(model.petRow))

        rv.setTextViewText(R.id.foot_left, footer(model))
        rv.setTextViewText(R.id.foot_right, model.footerRight)
        return rv
    }

    private fun row(model: WidgetRow, compact: Boolean, live: Boolean, nowMs: Long, elapsed: Long): RemoteViews {
        // The model already marks a row done Finish.GRACE_SEC early. This also covers a model built a
        // moment before this render, so a chronometer is never started at or past its finish.
        val row = if (model.kind == RowKind.RUNNING && model.finishAt != null && Finish.isDone(model.finishAt, nowMs / 1000)) {
            model.copy(kind = RowKind.COMPLETED, button = RowButton.NONE, remaining = 0, progress = 1f)
        } else {
            model
        }
        val v =RemoteViews(pkg, if (compact) R.layout.widget_row_compact else R.layout.widget_row)
        val iconPx = ((if (compact) 20 else 24) * density).roundToInt()

        val icon = row.iconIds.firstNotNullOfOrNull { icons.bitmap(it.toString(), iconPx) }
        if (icon != null) {
            v.setImageViewBitmap(R.id.row_icon, icon)
            v.setViewVisibility(R.id.row_icon, View.VISIBLE)
            v.setViewVisibility(R.id.row_initials, View.GONE)
        } else {
            v.setTextViewText(R.id.row_initials, row.initials)
        }

        if (row.goblin) {
            v.setViewVisibility(R.id.row_goblin, View.VISIBLE)
            icons.bitmap("goblin", (11 * density).roundToInt())?.let {
                v.setImageViewBitmap(R.id.row_goblin_icon, it)
                v.setViewVisibility(R.id.row_goblin_icon, View.VISIBLE)
            }
        }
        v.setTextViewText(R.id.row_name, row.name)
        // Finished rows show just the name (e.g. "Inferno Tower   Completed ✓").
        val level = row.levelText?.takeIf { row.kind == RowKind.RUNNING }
        v.setViewVisibility(R.id.row_arrow, if (level != null && row.showArrow) View.VISIBLE else View.GONE)
        if (level != null) v.setTextViewText(R.id.row_level, level)
        else v.setViewVisibility(R.id.row_level, View.GONE)

        when (row.kind) {
            RowKind.RUNNING -> {
                v.setProgressBar(R.id.row_fill, 1000, (row.progress * 1000).roundToInt(), false)
                icons.bitmap("clock", (12 * density).roundToInt())?.let { v.setImageViewBitmap(R.id.row_clock, it) }
                val segment = if (live) row.finishAt?.let { Countdown.segment(it, nowMs) } else null
                if (segment != null) {
                    v.setChronometer(R.id.row_chrono, elapsed + segment.chronometerMs, segment.format, true)
                    v.setChronometerCountDown(R.id.row_chrono, true)
                } else {
                    v.setViewVisibility(R.id.row_chrono, View.GONE)
                    v.setViewVisibility(R.id.row_text, View.VISIBLE)
                    v.setTextViewText(R.id.row_text, row.staticStatus)
                }
            }
            RowKind.COMPLETED, RowKind.IDLE -> {
                v.setViewVisibility(R.id.row_fill, View.GONE)
                v.setInt(R.id.row, "setBackgroundResource", R.drawable.row_done)
                v.setViewVisibility(R.id.row_clock, View.GONE)
                v.setViewVisibility(R.id.row_chrono, View.GONE)
                v.setViewVisibility(R.id.row_text, View.VISIBLE)
                v.setTextViewText(R.id.row_text, row.staticStatus)
                if (row.kind == RowKind.COMPLETED) v.setViewVisibility(R.id.row_check, View.VISIBLE)
            }
        }

        if (row.button != RowButton.NONE && row.jobKey != null) {
            v.setOnClickPendingIntent(R.id.row_btn, tapIntent(WidgetActions.HELPER, row.jobKey))
        }
        when (row.button) {
            RowButton.NONE -> v.setViewVisibility(R.id.row_btn, View.INVISIBLE)
            RowButton.LINK -> Unit // green + is the layout default
            RowButton.APPRENTICE, RowButton.LAB_ASSISTANT -> {
                v.setInt(R.id.row_btn, "setBackgroundResource", R.drawable.btn_helper)
                val name = if (row.button == RowButton.APPRENTICE) "apprentice" else "lab_assistant"
                val badge = icons.bitmap(name, ((if (compact) 19 else 22) * density).roundToInt())
                if (badge != null) {
                    v.setImageViewBitmap(R.id.row_btn_icon, badge)
                    v.setViewVisibility(R.id.row_btn_icon, View.VISIBLE)
                    v.setViewVisibility(R.id.row_btn_text, View.GONE)
                } else {
                    v.setTextViewText(R.id.row_btn_text, "H")
                    v.setTextColor(R.id.row_btn_text, COLOR_SYNC_TEXT)
                    v.setTextViewTextSize(R.id.row_btn_text, android.util.TypedValue.COMPLEX_UNIT_DIP, 10f)
                }
            }
        }
        return v
    }

    private class PillIds(
        @param:IdRes val box: Int, @param:IdRes val add: Int, @param:IdRes val undo: Int,
        @param:IdRes val icon: Int, @param:IdRes val text: Int,
    )

    private fun pill(rv: RemoteViews, ids: PillIds, pill: BoostPill, asset: String, @DrawableRes fallback: Int) {
        val bmp = icons.bitmap(asset, (14 * density).roundToInt())
        if (bmp != null) rv.setImageViewBitmap(ids.icon, bmp) else rv.setImageViewResource(ids.icon, fallback)
        rv.setTextViewText(ids.text, pill.text)
        rv.setTextColor(ids.text, if (pill.active) COLOR_GOLD else COLOR_WHITE)
        rv.setInt(ids.box, "setBackgroundResource", if (pill.active || pill.canUndo) R.drawable.pill_on else R.drawable.pill_off)
        rv.setOnClickPendingIntent(ids.add, tapIntent(WidgetActions.ADD_BOOST, pill.type.name))
        rv.setViewVisibility(ids.undo, if (pill.canUndo) View.VISIBLE else View.GONE)
        if (pill.canUndo) rv.setOnClickPendingIntent(ids.undo, tapIntent(WidgetActions.UNDO_BOOST, pill.type.name))
    }

    /**
     * Broadcast to [ClashWidgetReceiver] for a button tap, handled in the background (no activity, no popup).
     * The URI makes every action + argument a distinct PendingIntent.
     */
    private fun tapIntent(action: String, arg: String): PendingIntent {
        val uri = Uri.Builder().scheme("clashtimer").authority("tap").appendPath(action).appendPath(arg).appendPath(accountId).build()
        val intent = Intent(context, ClashWidgetReceiver::class.java)
            .setAction(ClashWidgetReceiver.ACTION_TAP)
            .setData(uri)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Player name + small gold `TH18`. */
    private fun title(model: WidgetModel): CharSequence {
        val sb = SpannableStringBuilder(model.playerName)
        model.townHall?.let { th ->
            val start = sb.length
            sb.append(" TH$th")
            sb.setSpan(AbsoluteSizeSpan(12, true), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(COLOR_GOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /** `BUILDERS 6/6` + green ` + Goblin`. */
    private fun buildersLabel(model: WidgetModel): CharSequence {
        val sb = SpannableStringBuilder(model.buildersLabel)
        if (model.goblinWorking) {
            val start = sb.length
            sb.append(" + Goblin")
            sb.setSpan(ForegroundColorSpan(COLOR_GOBLIN), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun footer(model: WidgetModel): CharSequence {
        val sb = SpannableStringBuilder()
        for (part in model.footerLeft) {
            val start = sb.length
            sb.append(part.text)
            val color = when (part.tone) {
                FooterTone.NORMAL -> null
                FooterTone.IDLE -> COLOR_IDLE
                FooterTone.ERROR -> COLOR_ERROR
            }
            color?.let { sb.setSpan(ForegroundColorSpan(it), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        return sb
    }

    private fun syncIntent(): PendingIntent {
        val intent = Intent(context, SyncActivity::class.java)
            .putExtra(SyncActivity.EXTRA_ACCOUNT, accountId)
            .setData(Uri.parse("clashtimer://sync/$accountId"))
            .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TASK,
        )
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val MAX_BUILDER_ROWS = 8

        private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
        private const val COLOR_GOLD = 0xFFFFD24A.toInt()
        private const val COLOR_GOBLIN = 0xFF6FD36A.toInt()
        private const val COLOR_IDLE = 0xFFFFB35C.toInt()
        private const val COLOR_ERROR = 0xFFFF7A7A.toInt()
        private const val COLOR_SYNC_TEXT = 0xFF5A3500.toInt()

        /** Estimated panel height in dp with 30 dp rows (see widget_root.xml / widget_row.xml). */
        fun normalHeightDp(rows: Int): Int = 165 + 33 * rows

        /** Widget height in dp as reported by the launcher (portrait), or null if unknown. */
        fun reportedHeightDp(options: Bundle?): Int? =
            options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 }

        fun reportedWidthDp(options: Bundle?): Int? =
            options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)?.takeIf { it > 0 }

        /** 25 dp rows when the launcher says 30 dp rows won't fit. */
        fun useCompactRows(options: Bundle?, rows: Int): Boolean =
            reportedHeightDp(options)?.let { it < normalHeightDp(rows) } ?: false
    }
}

/**
 * Icons from `assets/icons/` (64×64 WebP made by tools/gen_icons.cjs; see tools/icon_checklist.txt),
 * scaled to their on-screen size and cached. A hand-added `<name>.png` works too.
 */
class WidgetIcons private constructor(private val context: Context) {

    /** File names present in `assets/icons/`. */
    val available: Set<String> by lazy {
        runCatching { context.assets.list("icons")?.toSet() }.getOrNull() ?: emptySet()
    }

    private val cache = LruCache<String, Bitmap>(64)

    /** `assets/icons/<name>.webp` (or `.png`) scaled to [sizePx], or null if there is no such file. */
    fun bitmap(name: String, sizePx: Int): Bitmap? {
        val file = EXTENSIONS.map { "$name.$it" }.firstOrNull { it in available } ?: return null
        val key = "$file@$sizePx"
        cache.get(key)?.let { return it }
        val decoded = runCatching {
            context.assets.open("icons/$file").use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        val scaled = if (decoded.width == sizePx && decoded.height == sizePx) decoded else {
            val scale = sizePx.toFloat() / maxOf(decoded.width, decoded.height)
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        }
        cache.put(key, scaled)
        return scaled
    }

    companion object {
        private val EXTENSIONS = listOf("webp", "png")

        @Volatile private var instance: WidgetIcons? = null

        fun get(context: Context): WidgetIcons = instance ?: synchronized(this) {
            instance ?: WidgetIcons(context.applicationContext).also { instance = it }
        }
    }
}
