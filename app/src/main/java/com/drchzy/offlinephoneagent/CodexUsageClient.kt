package com.drchzy.offlinephoneagent

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UsageWindow(
    val usedPercent: Double,
    val resetAtSeconds: Long,
    val windowSeconds: Long
) {
    val remainingPercent: Double
        get() = (100.0 - usedPercent).coerceIn(0.0, 100.0)
}

data class CodexUsage(
    val fiveHour: UsageWindow?,
    val weekly: UsageWindow?
)

class CodexUsageClient(context: Context) {
    companion object {
        private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
        private const val SESSION_MAX_SECONDS = 24L * 60L * 60L
    }

    private val auth = CodexAuthManager(context)

    fun fetch(): CodexUsage {
        var tokens = auth.currentTokens() ?: error("请先登录 Codex")
        return try {
            fetchWith(tokens)
        } catch (e: HttpStatusException) {
            if (e.code != 401 && e.code != 403) throw e
            tokens = auth.refresh(tokens)
            fetchWith(tokens)
        }
    }

    private fun fetchWith(tokens: CodexTokens): CodexUsage {
        val conn = (URL(USAGE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer " + tokens.accessToken)
            if (tokens.accountId.isNotBlank()) {
                setRequestProperty("ChatGPT-Account-Id", tokens.accountId)
            }
            setRequestProperty("User-Agent", "codex-cli")
            setRequestProperty("Accept", "application/json")
        }

        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) throw HttpStatusException(status, text)

        val root = JSONObject(text)
        val rate = root.optJSONObject("rate_limit") ?: return CodexUsage(null, null)
        val windows = listOf(
            rate.optJSONObject("primary_window"),
            rate.optJSONObject("secondary_window")
        ).filterNotNull()

        var fiveHour: UsageWindow? = null
        var weekly: UsageWindow? = null

        windows.forEach { json ->
            val seconds = json.optLong("limit_window_seconds", 0L)
            val item = UsageWindow(
                usedPercent = json.optDouble("used_percent", 0.0),
                resetAtSeconds = json.optLong("reset_at", 0L),
                windowSeconds = seconds
            )
            when {
                seconds in 1..SESSION_MAX_SECONDS && fiveHour == null -> fiveHour = item
                seconds > SESSION_MAX_SECONDS && weekly == null -> weekly = item
                fiveHour == null -> fiveHour = item
                weekly == null -> weekly = item
            }
        }
        return CodexUsage(fiveHour, weekly)
    }
}
