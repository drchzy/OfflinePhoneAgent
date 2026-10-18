package com.drchzy.offlinephoneagent

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class CodexUsageWidgetProvider : AppWidgetProvider() {
    companion object {
        const val ACTION_REFRESH = "com.drchzy.offlinephoneagent.REFRESH_CODEX_USAGE"
        const val PREFS_NAME = "codex_widget_prefs"
        const val KEY_BACKGROUND_OPACITY = "background_opacity"
        const val DEFAULT_BACKGROUND_OPACITY = 86

        private val executor = Executors.newSingleThreadExecutor()

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, CodexUsageWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            // App 页面已获取最新用量时，只重绘小组件，不重复发起第二次网络请求。
            if (ids.isNotEmpty()) {
                val tokens = CodexAuthManager(context).currentTokens()
                val snapshot = CodexUsageCache.load(context, tokens)
                ids.forEach { id ->
                    manager.updateAppWidget(
                        id,
                        if (snapshot == null) baseViews(
                            context, if (tokens == null) "尚未登录" else "尚无缓存", manager, id
                        ) else usageViews(
                            context, snapshot.usage, manager, id,
                            "缓存 " + formatTime(snapshot.savedAtMs / 1000L)
                        )
                    )
                }
            }
        }

        private fun updateAsync(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            pending: PendingResult?
        ) {
            val before = CodexUsageCache.load(
                context, CodexAuthManager(context).currentTokens()
            )
            ids.forEach { id ->
                manager.updateAppWidget(id, if (before == null) {
                    baseViews(context, "正在刷新…", manager, id)
                } else {
                    usageViews(context, before.usage, manager, id,
                        "正在刷新 · 仍显示缓存")
                })
            }

            executor.execute {
                try {
                    val fresh = CodexUsageClient(context).fetch()
                    val tokens = CodexAuthManager(context).currentTokens()
                    val cached = CodexUsageCache.load(context, tokens)
                    val usage = CodexUsageCache.mergeFresh(fresh, cached)
                    val stored = CodexUsageCache.save(context, tokens, usage)
                    ids.forEach { id ->
                        manager.updateAppWidget(
                            id,
                            usageViews(context, usage, manager, id,
                                if (stored) "同步 " + formatTime(System.currentTimeMillis() / 1000L)
                                else "同步成功，缓存写入失败")
                        )
                    }
                } catch (e: Exception) {
                    val msg = if (CodexAuthManager(context).currentTokens() == null) {
                        "未登录 · 点卡片打开应用"
                    } else {
                        "刷新失败 · " + (e.message?.take(32) ?: "未知错误")
                    }
                    val cached = CodexUsageCache.load(
                        context, CodexAuthManager(context).currentTokens()
                    )
                    ids.forEach { id ->
                        manager.updateAppWidget(
                            id,
                            if (cached == null) baseViews(context, msg, manager, id)
                            else usageViews(context, cached.usage, manager, id,
                                "刷新失败，仍显示缓存")
                        )
                    }
                } finally {
                    pending?.finish()
                }
            }
        }

        private fun usageViews(
            context: Context,
            usage: CodexUsage,
            manager: AppWidgetManager,
            widgetId: Int,
            status: String = "刷新 " + formatTime(System.currentTimeMillis() / 1000L)
        ): RemoteViews {
            val views = baseViews(context, status, manager, widgetId)

            views.setTextViewText(
                R.id.widget_five_value,
                formatPercent(usage.fiveHour)
            )
            views.setTextViewText(
                R.id.widget_five_reset,
                "重置 " + formatReset(usage.fiveHour)
            )
            views.setTextViewText(
                R.id.widget_week_value,
                formatPercent(usage.weekly)
            )
            views.setTextViewText(
                R.id.widget_week_reset,
                "重置 " + formatReset(usage.weekly)
            )
            views.setTextViewText(
                R.id.widget_compact_usage,
                "5小时 " + formatPercent(usage.fiveHour) +
                    "  ·  每周 " + formatPercent(usage.weekly)
            )
            views.setProgressBar(R.id.widget_five_bar, 100,
                usage.fiveHour?.remainingPercent?.roundToInt()?.coerceIn(0, 100) ?: 0, false)
            views.setProgressBar(R.id.widget_week_bar, 100,
                usage.weekly?.remainingPercent?.roundToInt()?.coerceIn(0, 100) ?: 0, false)
            return views
        }

        private fun formatPercent(window: UsageWindow?): String {
            if (window == null) return "--"
            return window.remainingPercent.roundToInt().toString() + "%"
        }

        private fun formatReset(window: UsageWindow?): String {
            val seconds = window?.resetAtSeconds?.takeIf { it > 0 } ?: return "--"
            return java.text.SimpleDateFormat("M/d HH:mm", Locale.getDefault())
                .format(Date(seconds * 1000L))
        }

        private fun formatTime(epochSeconds: Long): String {
            return DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT,
                Locale.getDefault()
            ).format(Date(epochSeconds * 1000L))
        }

        private fun baseViews(
            context: Context,
            status: String,
            manager: AppWidgetManager,
            widgetId: Int
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_codex_usage)

            views.setTextViewText(R.id.widget_five_value, "--")
            views.setTextViewText(R.id.widget_five_reset, "重置 --")
            views.setTextViewText(R.id.widget_week_value, "--")
            views.setTextViewText(R.id.widget_week_reset, "重置 --")
            views.setTextViewText(R.id.widget_compact_usage, "5小时 --  ·  每周 --")
            views.setProgressBar(R.id.widget_five_bar, 100, 0, false)
            views.setProgressBar(R.id.widget_week_bar, 100, 0, false)
            views.setTextViewText(R.id.widget_status, status)

            val opacity = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_BACKGROUND_OPACITY, DEFAULT_BACKGROUND_OPACITY)
                .coerceIn(30, 100)
            views.setInt(
                R.id.widget_background,
                "setImageAlpha",
                (255 * opacity / 100f).roundToInt()
            )

            applySizeProfile(views, manager.getAppWidgetOptions(widgetId))

            val refreshIntent = Intent(context, CodexUsageWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPending = PendingIntent.getBroadcast(
                context,
                1001,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPending)

            val openIntent = Intent(context, MainActivity::class.java)
            val openPending = PendingIntent.getActivity(
                context,
                1002,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPending)
            return views
        }

        private fun applySizeProfile(views: RemoteViews, options: Bundle) {
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120)
            val compact = minHeight in 1..94

            views.setViewVisibility(
                R.id.widget_usage_row,
                if (compact) View.GONE else View.VISIBLE
            )
            views.setViewVisibility(
                R.id.widget_compact_usage,
                if (compact) View.VISIBLE else View.GONE
            )
            views.setViewVisibility(
                R.id.widget_status,
                if (compact) View.GONE else View.VISIBLE
            )
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 首次添加、Launcher 周期刷新都先使用缓存，避免卡片每次出现时请求网络。
        refreshAll(context.applicationContext)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        // 拉伸和缩小仅调整布局，不触发网络同步。右上角按钮仍可主动刷新。
        refreshAll(context.applicationContext)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val pending = goAsync()
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, CodexUsageWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            updateAsync(context.applicationContext, manager, ids, pending)
            return
        }
        super.onReceive(context, intent)
    }
}
