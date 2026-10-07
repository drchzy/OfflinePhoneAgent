package com.drchzy.offlinephoneagent

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class DeviceCodeInfo(
    val verificationUrl: String,
    val userCode: String,
    val deviceAuthId: String,
    val intervalSeconds: Long
)

class CodexAuthManager(context: Context) {
    companion object {
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val VERIFY_URL = "https://auth.openai.com/codex/device"
        private const val USER_CODE_URL = "https://auth.openai.com/api/accounts/deviceauth/usercode"
        private const val DEVICE_TOKEN_URL = "https://auth.openai.com/api/accounts/deviceauth/token"
        private const val OAUTH_TOKEN_URL = "https://auth.openai.com/oauth/token"
        private const val REDIRECT_URI = "https://auth.openai.com/deviceauth/callback"
    }

    private val store = CodexTokenStore(context)

    fun requestDeviceCode(): DeviceCodeInfo {
        val body = JSONObject().put("client_id", CLIENT_ID).toString()
        val json = requestJson("POST", USER_CODE_URL, body, "application/json")
        return DeviceCodeInfo(
            VERIFY_URL,
            json.getString("user_code"),
            json.getString("device_auth_id"),
            json.optString("interval", "5").toLongOrNull() ?: 5L
        )
    }

    fun completeDeviceLogin(code: DeviceCodeInfo, maxMinutes: Int = 15): CodexTokens {
        val deadline = System.currentTimeMillis() + maxMinutes * 60_000L
        while (System.currentTimeMillis() < deadline) {
            val body = JSONObject()
                .put("device_auth_id", code.deviceAuthId)
                .put("user_code", code.userCode)
                .toString()
            try {
                val json = requestJson("POST", DEVICE_TOKEN_URL, body, "application/json")
                val authorizationCode = json.getString("authorization_code")
                val verifier = json.getString("code_verifier")
                val tokens = exchangeAuthorizationCode(authorizationCode, verifier)
                store.save(tokens)
                return tokens
            } catch (e: HttpStatusException) {
                if (e.code != 403 && e.code != 404) throw e
                Thread.sleep(code.intervalSeconds.coerceAtLeast(1) * 1000L)
            }
        }
        error("设备码登录超时")
    }

    fun currentTokens(): CodexTokens? = store.load()

    fun refresh(tokens: CodexTokens): CodexTokens {
        if (tokens.refreshToken.isBlank()) error("没有 refresh token，请重新登录")
        val form = form(
            "grant_type" to "refresh_token",
            "client_id" to CLIENT_ID,
            "refresh_token" to tokens.refreshToken
        )
        val json = requestJson(
            "POST", OAUTH_TOKEN_URL, form,
            "application/x-www-form-urlencoded"
        )
        val idToken = json.optString("id_token", tokens.idToken)
        val updated = CodexTokens(
            json.getString("access_token"),
            json.optString("refresh_token", tokens.refreshToken),
            idToken,
            extractAccountId(idToken).ifBlank { tokens.accountId }
        )
        store.save(updated)
        return updated
    }

    private fun exchangeAuthorizationCode(code: String, verifier: String): CodexTokens {
        val form = form(
            "grant_type" to "authorization_code",
            "client_id" to CLIENT_ID,
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "code_verifier" to verifier
        )
        val json = requestJson(
            "POST", OAUTH_TOKEN_URL, form,
            "application/x-www-form-urlencoded"
        )
        val idToken = json.getString("id_token")
        return CodexTokens(
            json.getString("access_token"),
            json.optString("refresh_token"),
            idToken,
            extractAccountId(idToken)
        )
    }

    private fun extractAccountId(jwt: String): String {
        val payload = jwt.split('.').getOrNull(1) ?: return ""
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val root = JSONObject(String(decoded, StandardCharsets.UTF_8))
        root.optString("chatgpt_account_id").takeIf { it.isNotBlank() }?.let { return it }
        return root.optJSONObject("https://api.openai.com/auth")
            ?.optString("chatgpt_account_id").orEmpty()
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") {
            URLEncoder.encode(it.first, "UTF-8") + "=" +
                URLEncoder.encode(it.second, "UTF-8")
        }

    private fun requestJson(
        method: String,
        url: String,
        body: String? = null,
        contentType: String? = null
    ): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw HttpStatusException(code, text)
        return JSONObject(text)
    }
}

class HttpStatusException(val code: Int, message: String) : RuntimeException(message)
