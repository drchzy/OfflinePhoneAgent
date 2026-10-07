package com.drchzy.offlinephoneagent

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class CodexUsageWidgetProvider : AppWidgetProvider() {
    companion object {
        const val ACTION_REFRESH = "com.drchzy.offlinephoneagent.REFRESH_CODEX_USAGE"
        private val executor = Executors.newSingleThreadExecutor()

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, CodexUsageWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) updateAsync(context.applicationContext, manager, ids, null)
        }

        private fun updateAsync(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            pending: PendingResult?
        ) {
            ids.forEach { id -> manager.updateAppWidget(id, baseViews(context, "正在刷新…")) }
            executor.execute {
                try {
                    val usage = CodexUsageClient(context).fetch()
                    ids.forEach { id -> manager.updateAppWidget(id, usageViews(context, usage)) }
                } catch (e: Exception) {
                    val msg = if (CodexAuthManager(context).currentTokens() == null) {
                        "未登录，点组件打开应用"
                    } else {
                        "刷新失败：" + (e.message?.take(40) ?: "未知错误")
                    }
                    ids.forEach { id -> manager.updateAppWidget(id, baseViews(context, msg)) }
                } finally {
                    pending?.finish()
                }
            }
        }

        private fun usageViews(context: Context, usage: CodexUsage): RemoteViews {
            val views = baseViews(context, "已更新")
            views.setTextViewText(R.id.widget_five_hour, "5小时：" + formatRemaining(usage.fiveHour))
            views.setTextViewText(R.id.widget_weekly, "每周：" + formatRemaining(usage.weekly))
            val reset = usage.fiveHour?.resetAtSeconds?.takeIf { it > 0 }?.let {
                DateFormat.getDateTimeInstance(
                    DateFormat.SHORT,
                    DateFormat.SHORT,
                    Locale.getDefault()
                ).format(Date(it * 1000L))
            }
            views.setTextViewText(
                R.id.widget_status,
                if (reset != null) "5小时窗口重置：" + reset else "用量已刷新"
            )
            return views
        }

        private fun formatRemaining(window: UsageWindow?): String {
            if (window == null) return "--"
            return window.remainingPercent.roundToInt().toString() + "% 剩余"
        }

        private fun baseViews(context: Context, status: String): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_codex_usage)
            views.setTextViewText(R.id.widget_five_hour, "5小时：--")
            views.setTextViewText(R.id.widget_weekly, "每周：--")
            views.setTextViewText(R.id.widget_status, status)

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
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAsync(context.applicationContext, appWidgetManager, appWidgetIds, null)
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
