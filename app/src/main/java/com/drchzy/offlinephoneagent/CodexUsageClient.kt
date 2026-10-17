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

data class CodexResetCard(
    val id: String,
    val title: String,
    val expiresAtSeconds: Long?
)

data class CodexUsage(
    val fiveHour: UsageWindow?,
    val weekly: UsageWindow?,
    // null: 接口未提供可用数量；0: 服务端明确表示没有可用重置卡。
    val availableResetCount: Int? = null,
    val resetCards: List<CodexResetCard> = emptyList(),
    val resetCardsMessage: String = ""
)

class CodexUsageClient(context: Context) {
    companion object {
        private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
        private const val RESET_CARDS_URL = "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits"
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
        val safe = if (status in 200..299) {
            runCatching {
                JSONObject(text).optJSONObject("rate_limit")?.toString(2) ?: "无用量字段"
            }.getOrDefault("非 JSON 响应")
        } else "异常响应正文已隐藏，避免泄露账户凭据"
        MovieHttpLog.add(
            "Codex 用量",
            "GET " + USAGE_URL + "\nAuthorization: [已隐藏]\nChatGPT-Account-Id: [已隐藏]",
            "HTTP " + status + "\n\n" + safe,
            status
        )
        if (status !in 200..299) throw HttpStatusException(status, text)

        val root = JSONObject(text)
        val summary = root.optJSONObject("rate_limit_reset_credits")
        val summaryCount = summary?.optInt("available_count", -1)?.takeIf { it >= 0 }
        val details = if (summaryCount == 0) {
            ResetCardsResult(0, emptyList(), "")
        } else {
            runCatching { readResetCards(tokens) }.getOrElse {
                ResetCardsResult(null, emptyList(), "重置卡明细暂时不可用，可在 Codex 用量页核对")
            }
        }
        val actualCount = details.availableCount ?: summaryCount
        val cardMessage = if (actualCount == null && details.message.isBlank()) {
            "账户没有返回重置卡数量"
        } else details.message
        val rate = root.optJSONObject("rate_limit") ?: return CodexUsage(
            null, null, actualCount, details.cards, cardMessage
        )
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
        return CodexUsage(fiveHour, weekly, actualCount, details.cards, cardMessage)
    }

    private data class ResetCardsResult(
        val availableCount: Int?,
        val cards: List<CodexResetCard>,
        val message: String
    )

    /** 只读 GET：仅查询重置卡，不执行 consume，不使用任何兑换接口。 */
    private fun readResetCards(tokens: CodexTokens): ResetCardsResult {
        val conn = (URL(RESET_CARDS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer " + tokens.accessToken)
            if (tokens.accountId.isNotBlank()) {
                setRequestProperty("ChatGPT-Account-Id", tokens.accountId)
            }
            setRequestProperty("User-Agent", "codex-cli")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            // 日志仅记录非敏感统计，不写入完整 OAuth 响应。
            MovieHttpLog.add(
                "Codex 重置卡",
                "GET " + RESET_CARDS_URL + "\nAuthorization: [已隐藏]\nChatGPT-Account-Id: [已隐藏]",
                "HTTP " + status + if (status in 200..299) "\n返回重置卡明细（正文已脱敏）" else "\n接口暂不可用",
                status
            )
            if (status !in 200..299) error("HTTP $status")
            val root = JSONObject(body)
            val array = root.optJSONArray("credits")
            val cards = mutableListOf<CodexResetCard>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    if (!obj.optString("status").equals("available", ignoreCase = true)) continue
                    val expires = obj.optLong("expires_at", 0L)
                        .takeIf { it > 0L }
                    cards += CodexResetCard(
                        id = obj.optString("id").ifBlank { "credit-$i" },
                        title = obj.optString("title").ifBlank { "额度重置卡" }.take(80),
                        expiresAtSeconds = expires
                    )
                }
            }
            val count = root.optInt("available_count", -1).takeIf { it >= 0 }
                ?: if (array != null) cards.size else null
            val message = if (array == null && (count ?: 0) > 0) {
                "仅返回数量，未提供逐张到期日期"
            } else if (count != null && count > cards.size) {
                "明细可能不完整，共返回 ${cards.size} 张可用卡"
            } else ""
            return ResetCardsResult(count, cards, message)
        } finally {
            conn.disconnect()
        }
    }
}
