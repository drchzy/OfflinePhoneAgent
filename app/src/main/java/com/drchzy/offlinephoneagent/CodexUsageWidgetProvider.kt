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
            if (ids.isNotEmpty()) {
                updateAsync(context.applicationContext, manager, ids, null)
            }
        }

        private fun updateAsync(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            pending: PendingResult?
        ) {
            ids.forEach { id ->
                manager.updateAppWidget(
                    id,
                    baseViews(context, "正在刷新…", manager, id)
                )
            }

            executor.execute {
                try {
                    val usage = CodexUsageClient(context).fetch()
                    ids.forEach { id ->
                        manager.updateAppWidget(
                            id,
                            usageViews(context, usage, manager, id)
                        )
                    }
                } catch (e: Exception) {
                    val msg = if (CodexAuthManager(context).currentTokens() == null) {
                        "未登录 · 点卡片打开应用"
                    } else {
                        "刷新失败 · " + (e.message?.take(32) ?: "未知错误")
                    }
                    ids.forEach { id ->
                        manager.updateAppWidget(
                            id,
                            baseViews(context, msg, manager, id)
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
            widgetId: Int
        ): RemoteViews {
            val views = baseViews(
                context,
                "刷新 " + formatTime(System.currentTimeMillis() / 1000L),
                manager,
                widgetId
            )

            views.setTextViewText(
                R.id.widget_five_value,
                "5 小时  " + formatPercent(usage.fiveHour)
            )
            views.setTextViewText(
                R.id.widget_five_reset,
                "重置 " + formatReset(usage.fiveHour)
            )
            views.setTextViewText(
                R.id.widget_week_value,
                "每周  " + formatPercent(usage.weekly)
            )
            views.setTextViewText(
                R.id.widget_week_reset,
                "重置 " + formatReset(usage.weekly)
            )
            views.setTextViewText(
                R.id.widget_compact_usage,
                "5h " + formatPercent(usage.fiveHour) +
                    "   ·   周 " + formatPercent(usage.weekly)
            )
            return views
        }

        private fun formatPercent(window: UsageWindow?): String {
            if (window == null) return "--"
            return window.remainingPercent.roundToInt().toString() + "%"
        }

        private fun formatReset(window: UsageWindow?): String {
            val seconds = window?.resetAtSeconds?.takeIf { it > 0 } ?: return "--"
            return formatTime(seconds)
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

            views.setTextViewText(R.id.widget_five_value, "5 小时  --")
            views.setTextViewText(R.id.widget_five_reset, "重置 --")
            views.setTextViewText(R.id.widget_week_value, "每周  --")
            views.setTextViewText(R.id.widget_week_reset, "重置 --")
            views.setTextViewText(R.id.widget_compact_usage, "5h --   ·   周 --")
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
        updateAsync(context.applicationContext, appWidgetManager, appWidgetIds, null)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateAsync(
            context.applicationContext,
            appWidgetManager,
            intArrayOf(appWidgetId),
            null
        )
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
