package com.drchzy.offlinephoneagent

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class CachedCodexUsage(val usage: CodexUsage, val savedAtMs: Long)

/**
 * Codex 用量的本地快照。只保存统计数据，不保存 OAuth Token、请求头和认证响应。
 * 以账户稳定标识的 SHA-256 摘要隔离缓存；切换账户绝不读取上一账户数据。
 */
object CodexUsageCache {
    private const val PREFS = "codex_usage_cache_v1"
    private const val KEY = "snapshot"
    private const val FORMAT = 1

    fun accountKey(tokens: CodexTokens?): String? {
        if (tokens == null) return null
        val account = tokens.accountId.ifBlank {
            runCatching {
                val part = tokens.idToken.split('.').getOrNull(1) ?: return@runCatching ""
                val json = JSONObject(String(
                    Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                    StandardCharsets.UTF_8
                ))
                json.optString("sub")
            }.getOrDefault("")
        }
        if (account.isBlank()) return null
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(account.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun load(context: Context, tokens: CodexTokens?): CachedCodexUsage? {
        val account = accountKey(tokens) ?: return null
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return null
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("schema") != FORMAT ||
                root.optString("account_key") != account) return@runCatching null
            val savedAt = root.optLong("saved_at_ms")
            if (savedAt <= 0L) return@runCatching null
            val data = root.optJSONObject("usage") ?: return@runCatching null
            val cards = data.optJSONArray("reset_cards") ?: JSONArray()
            val parsedCards = ArrayList<CodexResetCard>()
            for (i in 0 until minOf(cards.length(), 150)) {
                val card = cards.optJSONObject(i) ?: continue
                parsedCards.add(CodexResetCard(
                    id = card.optString("id"),
                    title = card.optString("title"),
                    expiresAtSeconds = card.optLong("expires_at", -1L).takeIf { it > 0L }
                ))
            }
            CachedCodexUsage(
                CodexUsage(
                    fiveHour = decodeWindow(data.optJSONObject("five_hour")),
                    weekly = decodeWindow(data.optJSONObject("weekly")),
                    availableResetCount = data.optInt("reset_count", -1).takeIf { it >= 0 },
                    resetCards = parsedCards,
                    resetCardsMessage = data.optString("reset_message"),
                    resetDetailsReliable = data.optBoolean("reset_reliable", true)
                ),
                savedAt
            )
        }.getOrNull()
    }

    /**
     * 同步完整成功时覆盖缓存。部分成功（例如重置卡接口 429）时，
     * 保留原重置卡及到期日，同时使用本次新获取的额度窗口。
     */
    fun mergeFresh(fresh: CodexUsage, old: CachedCodexUsage?): CodexUsage {
        if (fresh.resetDetailsReliable || old == null) return fresh
        val oldUsage = old.usage
        val hasOlderCards = oldUsage.availableResetCount != null ||
            oldUsage.resetCards.isNotEmpty()
        if (!hasOlderCards) return fresh
        return fresh.copy(
            availableResetCount = oldUsage.availableResetCount,
            resetCards = oldUsage.resetCards,
            resetCardsMessage = "重置卡明细同步失败，仍展示上次缓存；" +
                fresh.resetCardsMessage,
            resetDetailsReliable = false
        )
    }

    /** commit 保证成功后缓存落盘，再更新页面；不写入凭据。 */
    fun save(context: Context, tokens: CodexTokens?, usage: CodexUsage): Boolean {
        val account = accountKey(tokens) ?: return false
        return runCatching {
            val cards = JSONArray()
            usage.resetCards.take(150).forEach { card ->
                cards.put(JSONObject().apply {
                    put("id", card.id)
                    put("title", card.title)
                    put("expires_at", card.expiresAtSeconds ?: JSONObject.NULL)
                })
            }
            val data = JSONObject().apply {
                put("five_hour", encodeWindow(usage.fiveHour))
                put("weekly", encodeWindow(usage.weekly))
                put("reset_count", usage.availableResetCount ?: JSONObject.NULL)
                put("reset_cards", cards)
                put("reset_message", usage.resetCardsMessage)
                put("reset_reliable", usage.resetDetailsReliable)
            }
            val now = System.currentTimeMillis()
            val raw = JSONObject().apply {
                put("schema", FORMAT)
                put("account_key", account)
                put("saved_at_ms", now)
                put("usage", data)
            }.toString()
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, raw).commit()
        }.getOrDefault(false)
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    private fun encodeWindow(value: UsageWindow?): Any =
        value?.let { window ->
            JSONObject().apply {
                put("used", window.usedPercent)
                put("reset", window.resetAtSeconds)
                put("seconds", window.windowSeconds)
            }
        } ?: JSONObject.NULL

    private fun decodeWindow(json: JSONObject?): UsageWindow? {
        if (json == null) return null
        val used = json.optDouble("used", Double.NaN)
        if (!used.isFinite() || used !in 0.0..100.0) return null
        return UsageWindow(
            usedPercent = used,
            resetAtSeconds = json.optLong("reset"),
            windowSeconds = json.optLong("seconds")
        )
    }
}
