package com.gitgo.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

object WidgetStore {
    fun sp(ctx: Context) = ctx.getSharedPreferences("gitgo_widgets", Context.MODE_PRIVATE)
}

/** Загружает данные для виджетов (в кэш) и перерисовывает их. Токен передаётся явно — виджеты не читают хранилище. */
object WidgetRefresh {
    private fun providers(): List<BaseWidget> = listOf(ContribWidget(), CiWidget(), ReviewWidget())

    fun launch(ctx: Context, token: String, force: Boolean = false) {
        if (token.isBlank()) return
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch { refresh(app, token, force) }
    }

    fun refresh(ctx: Context, token: String, force: Boolean = false) {
        val sp = WidgetStore.sp(ctx)
        // Три провайдера и выход из приложения вызывают refresh почти одновременно — пропускаем дубликаты.
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (!force && now - sp.getLong("last", 0L) < 20_000L) return
            sp.edit().putLong("last", now).apply()
        }
        val prefs = (ctx.applicationContext as GitGoApp).prefs
        val api = GitHubApi(token)

        runCatching {
            val (total, weeks) = api.contributions()
            sp.edit().putInt("total", total)
                .putString("weeks", JSONArray(weeks.map { JSONArray(it) }).toString()).apply()
        }
        val pin = prefs.pinnedRepo
        if (pin.contains("/")) runCatching {
            val (o, r) = pin.split("/", limit = 2)
            val run = api.listRuns(o, r).firstOrNull()
            val json = if (run == null) "" else JSONObject()
                .put("repo", pin).put("title", run.title).put("status", run.status)
                .put("conclusion", run.conclusion).put("branch", run.branch).put("number", run.number).toString()
            sp.edit().putString("ci", json).apply()
        }
        runCatching {
            val prs = api.reviewRequests()
            val arr = JSONArray()
            prs.forEach { arr.put(JSONObject().put("title", it.title).put("repo", it.repo).put("number", it.number)) }
            sp.edit().putString("reviews", arr.toString()).putBoolean("reviews_loaded", true).apply()
        }
        updateAll(ctx)
    }

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (p in providers()) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, p.javaClass))
            for (id in ids) mgr.updateAppWidget(id, p.render(ctx))
        }
    }
}

abstract class BaseWidget : AppWidgetProvider() {
    abstract fun render(ctx: Context): RemoteViews

    protected fun refreshPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, javaClass.name.hashCode(), Intent(ctx, javaClass).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    protected fun openAppPi(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    protected fun lockedNote(ctx: Context): String {
        val p = (ctx.applicationContext as GitGoApp).prefs
        return if (p.vaultEnabled && p.sessionToken == null) tr(R.string.widget_locked) else ""
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, render(ctx))
        val app = ctx.applicationContext
        val t = (app as GitGoApp).prefs.token
        if (t.isNotBlank()) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try { WidgetRefresh.refresh(app, t) } finally { pending?.finish() }
            }
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(ctx)
            onUpdate(ctx, mgr, mgr.getAppWidgetIds(ComponentName(ctx, javaClass)))
        } else super.onReceive(ctx, intent)
    }

    companion object { const val ACTION_REFRESH = "com.gitgo.app.WIDGET_REFRESH" }
}

class ContribWidget : BaseWidget() {
    override fun render(ctx: Context): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_contrib)
        val sp = WidgetStore.sp(ctx)
        val weeksJson = sp.getString("weeks", null)
        val total = sp.getInt("total", 0)
        rv.setOnClickPendingIntent(R.id.w_root, refreshPi(ctx))
        rv.setOnClickPendingIntent(R.id.w_title, openAppPi(ctx))
        rv.setTextViewText(R.id.w_title, tr(R.string.widget_contrib_title) + if (total > 0) " • " + tr(R.string.widget_total, total) else "")
        if (weeksJson == null) {
            rv.setViewVisibility(R.id.w_image, View.GONE)
            rv.setTextViewText(R.id.w_text, tr(R.string.widget_no_data))
        } else {
            val arr = JSONArray(weeksJson)
            val weeks = (0 until arr.length()).map { i -> val w = arr.getJSONArray(i); (0 until w.length()).map { w.getInt(it) } }
            rv.setViewVisibility(R.id.w_image, View.VISIBLE)
            rv.setImageViewBitmap(R.id.w_image, draw(weeks))
            rv.setTextViewText(R.id.w_text, lockedNote(ctx))
        }
        return rv
    }

    private fun colorFor(n: Int): Int = when {
        n <= 0 -> Color.parseColor("#2D333B")
        n < 4 -> Color.parseColor("#0E4429")
        n < 7 -> Color.parseColor("#006D32")
        n < 10 -> Color.parseColor("#26A641")
        else -> Color.parseColor("#39D353")
    }

    private fun draw(all: List<List<Int>>): Bitmap {
        val weeks = all.takeLast(26)
        val cell = 14
        val gap = 3
        val bmp = Bitmap.createBitmap(maxOf(1, weeks.size) * (cell + gap), 7 * (cell + gap), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((x, week) in weeks.withIndex()) for ((y, n) in week.withIndex()) {
            paint.color = colorFor(n)
            val l = x * (cell + gap).toFloat()
            val t = y * (cell + gap).toFloat()
            canvas.drawRoundRect(RectF(l, t, l + cell, t + cell), 3f, 3f, paint)
        }
        return bmp
    }
}

private fun ciLabel(status: String, conclusion: String): Pair<Int, String> = when {
    status == "completed" && conclusion == "success" -> R.string.ci_success to "#2EA043"
    status == "completed" && (conclusion == "failure" || conclusion == "timed_out" || conclusion == "startup_failure") -> R.string.ci_failure to "#F85149"
    status == "completed" && conclusion == "cancelled" -> R.string.ci_cancelled to "#8A8F98"
    status == "in_progress" -> R.string.ci_running to "#D9A400"
    status == "queued" || status == "waiting" || status == "pending" -> R.string.ci_queued to "#8A8F98"
    else -> R.string.ci_other to "#8A8F98"
}

class CiWidget : BaseWidget() {
    override fun render(ctx: Context): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_ci)
        val prefs = (ctx.applicationContext as GitGoApp).prefs
        val json = WidgetStore.sp(ctx).getString("ci", "") ?: ""
        rv.setOnClickPendingIntent(R.id.w_root, refreshPi(ctx))
        rv.setOnClickPendingIntent(R.id.w_title, openAppPi(ctx))
        val pin = prefs.pinnedRepo
        rv.setTextViewText(R.id.w_title, tr(R.string.widget_ci_title) + if (pin.isNotBlank()) " • $pin" else "")
        when {
            pin.isBlank() -> {
                rv.setTextViewText(R.id.w_status, "—")
                rv.setTextViewText(R.id.w_detail, tr(R.string.widget_no_pin))
            }
            json.isBlank() -> {
                rv.setTextViewText(R.id.w_status, "—")
                rv.setTextViewText(R.id.w_detail, tr(R.string.widget_no_data))
            }
            else -> {
                val o = JSONObject(json)
                val (label, color) = ciLabel(o.optString("status"), o.optString("conclusion"))
                rv.setTextViewText(R.id.w_status, tr(label))
                rv.setTextColor(R.id.w_status, Color.parseColor(color))
                val note = lockedNote(ctx)
                rv.setTextViewText(
                    R.id.w_detail,
                    "#${o.optInt("number")} • ${o.optString("branch")}\n${o.optString("title")}" + if (note.isNotEmpty()) "\n$note" else ""
                )
            }
        }
        return rv
    }
}

class ReviewWidget : BaseWidget() {
    override fun render(ctx: Context): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_review)
        val sp = WidgetStore.sp(ctx)
        rv.setOnClickPendingIntent(R.id.w_root, refreshPi(ctx))
        rv.setOnClickPendingIntent(R.id.w_title, openAppPi(ctx))
        rv.setTextViewText(R.id.w_title, tr(R.string.widget_review_title))
        val rows = intArrayOf(R.id.r1, R.id.r2, R.id.r3, R.id.r4, R.id.r5)
        rows.forEach { rv.setViewVisibility(it, View.GONE) }
        rv.setViewVisibility(R.id.w_empty, View.GONE)
        if (!sp.getBoolean("reviews_loaded", false)) {
            rv.setViewVisibility(R.id.w_empty, View.VISIBLE)
            rv.setTextViewText(R.id.w_empty, tr(R.string.widget_no_data))
            return rv
        }
        val arr = JSONArray(sp.getString("reviews", "[]"))
        if (arr.length() == 0) {
            rv.setViewVisibility(R.id.w_empty, View.VISIBLE)
            rv.setTextViewText(R.id.w_empty, tr(R.string.widget_no_reviews))
        }
        for (i in 0 until minOf(arr.length(), rows.size)) {
            val o = arr.getJSONObject(i)
            rv.setViewVisibility(rows[i], View.VISIBLE)
            rv.setTextViewText(rows[i], "• ${o.optString("title")}  (${o.optString("repo")}#${o.optInt("number")})")
        }
        val note = lockedNote(ctx)
        if (note.isNotEmpty()) {
            rv.setViewVisibility(R.id.w_empty, View.VISIBLE)
            rv.setTextViewText(R.id.w_empty, note)
        }
        return rv
    }
}
