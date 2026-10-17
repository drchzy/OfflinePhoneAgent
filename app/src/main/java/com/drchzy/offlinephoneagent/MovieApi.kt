package com.drchzy.offlinephoneagent

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 影视接口适配器。服务地址与签名参数均由使用者配置，不向公开仓库写入第三方密钥。
 * 只提供正常的目录、详情、播放接口；不实现绕过付费/解锁功能。
 */
data class MovieItem(
    val id: String,
    val name: String,
    val cover: String,
    val year: String = "",
    val type: String = "",
    val summary: String = "",
    val dynamic: String = ""
)

data class MovieEpisode(
    val id: String,
    val name: String,
    val url: String,
    val fromCode: String,
    val ready: Boolean,
    val userAgent: String,
    val headers: Map<String, String>,
    val needsParse: Boolean
)

data class MovieLine(val name: String, val code: String, val episodes: List<MovieEpisode>)
data class MovieDetail(val movie: MovieItem, val lines: List<MovieLine>)
data class PlayAddress(val url: String, val userAgent: String, val headers: Map<String, String>)

data class ProbeResult(
    val requestUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val elapsedMillis: Long,
    val contentType: String,
    val responseBody: String,
    val apiService: String?,
    val error: String? = null
)

data class MovieSettings(
    val baseUrl: String,
    val configUrl: String,
    val aesKey: String,
    val aesIv: String,
    val hmacKey: String
)

class MovieApi(private val context: Context) {
    companion object {
        const val DEFAULT_CONFIG_URL = "https://app-site.ecoliving168.com/domain_v5.json"
        private const val PREFS = "movie_connector"
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(): MovieSettings = MovieSettings(
        prefs.getString("api_url", "").orEmpty(),
        prefs.getString("config_url", DEFAULT_CONFIG_URL).orEmpty().ifBlank { DEFAULT_CONFIG_URL },
        prefs.getString("aes_key", "").orEmpty(),
        prefs.getString("aes_iv", "").orEmpty(),
        prefs.getString("hmac_key", "").orEmpty()
    )

    fun save(settings: MovieSettings) {
        prefs.edit()
            .putString("api_url", settings.baseUrl.trim())
            .putString("config_url", settings.configUrl.trim())
            .putString("aes_key", settings.aesKey.trim())
            .putString("aes_iv", settings.aesIv.trim())
            .putString("hmac_key", settings.hmacKey.trim())
            .apply()
    }

    /**
     * 手动诊断专用：仅 GET、无认证头、最多读取 64 KiB。
     * 不会自动访问候选配置源，也不会将结果默认为业务 API。
     */
    fun probeUrl(uri: String): ProbeResult {
        val requested = uri.trim()
        val parsed = URL(requested)
        require(parsed.protocol.equals("https", true) || parsed.protocol.equals("http", true)) {
            "只支持 HTTP / HTTPS"
        }
        require(parsed.host.isNotBlank()) { "请填写完整 URL" }
        val started = SystemClock.elapsedRealtime()
        var conn: HttpURLConnection? = null
        return try {
            conn = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("User-Agent", "OfflinePhoneAgent/1.0 Android")
            }
            val code = conn.responseCode
            val type = conn.contentType.orEmpty()
            val source = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = java.io.ByteArrayOutputStream()
            if (source != null) source.use { input ->
                val buffer = ByteArray(4096)
                while (bytes.size() < 65_536) {
                    val read = input.read(buffer, 0, minOf(buffer.size, 65_536 - bytes.size()))
                    if (read <= 0) break
                    bytes.write(buffer, 0, read)
                }
            }
            val raw = String(bytes.toByteArray(), StandardCharsets.UTF_8)
            val pretty = try { JSONObject(raw).toString(2) }
                catch (_: Exception) { try { JSONArray(raw).toString(2) } catch (_: Exception) { raw } }
            val api = try {
                JSONObject(raw).optString("api_service").trim()
                    .takeIf { it.startsWith("https://") || it.startsWith("http://") }
            } catch (_: Exception) { null }
            ProbeResult(
                requested,
                conn.url.toString(),
                code,
                SystemClock.elapsedRealtime() - started,
                type,
                pretty,
                api,
                if (bytes.size() >= 65_536) "响应超出 64 KiB，已截断" else null
            )
        } catch (e: Exception) {
            ProbeResult(
                requested,
                conn?.url?.toString().orEmpty(),
                -1,
                SystemClock.elapsedRealtime() - started,
                "",
                "",
                null,
                e.javaClass.simpleName + ": " + (e.message ?: "连接失败")
            )
        } finally {
            conn?.disconnect()
        }
    }

    /** api_service 是动态配置中的业务接口根地址。离线或配置异常时使用缓存。 */
    fun resolveBaseUrl(): String {
        val current = settings()
        if (current.baseUrl.isNotBlank()) return normalise(current.baseUrl)
        val cached = prefs.getString("resolved_url", "").orEmpty()
        try {
            val text = rawGet(current.configUrl)
            val root = JSONObject(text)
            val remote = root.optString("api_service").trim()
            require(remote.startsWith("https://", true)) { "动态配置未返回有效 HTTPS api_service" }
            prefs.edit().putString("resolved_url", remote).apply()
            return normalise(remote)
        } catch (e: Exception) {
            if (cached.isNotBlank()) return normalise(cached)
            throw IllegalStateException("未能读取 Movie 服务地址，请在设置中填写 API BaseURL：" + (e.message ?: ""), e)
        }
    }

    private fun normalise(url: String): String {
        require(url.startsWith("https://", true) || url.startsWith("http://", true)) { "仅支持 HTTP/HTTPS 服务地址" }
        return url.trimEnd('/') + "/"
    }

    /**
     * 与 1.0.0.8 反编译结果保持一致：两处 timestamp 分别计算。
     * 注意原实现 fallback 使用 currentTimeMillis().toInt()，不能擅自改成 Unix 秒。
     */
    private fun timestamp(): Int {
        val server = prefs.getString("key_servertime", "").orEmpty()
        if (server.isBlank() || server == "0") return System.currentTimeMillis().toInt()
        val base = prefs.getInt("key_localtimebase", 0)
        return (server.toIntOrNull() ?: return System.currentTimeMillis().toInt()) +
            (SystemClock.elapsedRealtime() - base).toInt()
    }

    private fun pack(params: Map<String, String>, s: MovieSettings): Pair<String, String> {
        require(s.aesKey.toByteArray(StandardCharsets.UTF_8).size == 16) { "请先设置 16 字节 AES Key" }
        require(s.aesIv.toByteArray(StandardCharsets.UTF_8).size == 16) { "请先设置 16 字节 AES IV" }
        require(s.hmacKey.isNotBlank()) { "请先设置 HMAC Key" }
        val values = LinkedHashMap(params)
        values.remove("timestamp")
        values.remove("sign")
        values["timestamp"] = timestamp().toString()
        val json = JSONObject(values as Map<*, *>).toString()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(s.aesKey.toByteArray(StandardCharsets.UTF_8), "AES"),
            IvParameterSpec(s.aesIv.toByteArray(StandardCharsets.UTF_8))
        )
        val encoded = Base64.encodeToString(
            cipher.doFinal(json.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP
        )
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(s.hmacKey.toByteArray(StandardCharsets.UTF_8), "HmacMD5"))
        val signature = mac.doFinal(encoded.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return encoded to signature
    }

    private fun rawGet(uri: String): String {
        val conn = URL(uri).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        return try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("配置请求 HTTP " + status)
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val s = settings()
        val (value, signature) = pack(params, s)
        val query = "pack=" + URLEncoder.encode(value, "UTF-8") +
            "&signature=" + URLEncoder.encode(signature, "UTF-8")
        val conn = URL(resolveBaseUrl() + path + "?" + query).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.setRequestProperty("Authorization", "")
        conn.setRequestProperty("User-Agent", "Android")
        conn.setRequestProperty("Accept", "application/prs.55App.v2+json")
        conn.setRequestProperty("timestamp", timestamp().toString())
        conn.setRequestProperty("X-Client-Version", when {
            path.contains("index_recommend") || path.contains("app/config") -> "3098"
            path.contains("movie/detail") -> "3096"
            else -> "3101"
        })
        conn.setRequestProperty("X-App-Lang", "zh_CN")
        conn.setRequestProperty("X-Client-Setting", "{\"pure-mode\":0}")
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val device = JSONObject().put("device_id", deviceId)
            .put("type", 1).put("brand", Build.BRAND).put("model", Build.MODEL)
            .put("system_version", Build.VERSION.SDK_INT).put("sdk_version", "1.0.0.8")
        conn.setRequestProperty("X-Client-UUID", device.toString())
        try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            var body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("接口 HTTP " + status + "：" + body.take(180))
            if (conn.getHeaderField("X-App-Data-Encrypt") == "1") {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(s.aesKey.toByteArray(StandardCharsets.UTF_8), "AES"),
                    IvParameterSpec(s.aesIv.toByteArray(StandardCharsets.UTF_8))
                )
                body = String(cipher.doFinal(Base64.decode(body, Base64.DEFAULT)), StandardCharsets.UTF_8)
            }
            val root = JSONObject(body)
            if (!root.optBoolean("success", true)) {
                error(root.optString("errorMsg").ifBlank { root.optString("msg", "Movie 请求失败") })
            }
            return root
        } finally {
            conn.disconnect()
        }
    }

    private fun data(root: JSONObject): Any? = root.opt("data").takeUnless { it == JSONObject.NULL }

    private fun item(json: JSONObject): MovieItem = MovieItem(
        id = json.optString("id", json.optString("movie_id")),
        name = json.optString("name"),
        cover = json.optString("cover"),
        year = json.optString("year"),
        type = json.optString("type_name"),
        summary = json.optString("blurb", json.optString("content")),
        dynamic = json.optString("dynamic")
    )

    private fun extractMovieItems(node: Any?, dest: MutableList<MovieItem>, depth: Int = 0) {
        if (node == null || depth > 6 || dest.size >= 100) return
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) extractMovieItems(node.opt(i), dest, depth + 1)
            is JSONObject -> {
                if (node.optString("id").isNotBlank() && node.optString("name").isNotBlank()) {
                    dest.add(item(node))
                } else {
                    for (key in node.keys()) {
                        if (node.opt(key) is JSONArray || node.opt(key) is JSONObject) {
                            extractMovieItems(node.opt(key), dest, depth + 1)
                        }
                    }
                }
            }
        }
    }

    fun recommendations(): List<MovieItem> {
        val result = mutableListOf<MovieItem>()
        extractMovieItems(data(get("v1/movie/index_recommend")), result)
        return result.distinctBy { it.id }.take(60)
    }

    fun search(keyword: String, page: Int = 1): List<MovieItem> {
        val request = linkedMapOf(
            "keyword" to keyword, "sort" to "", "type_id" to "0",
            "page" to page.toString(), "pageSize" to "20", "res_type" to ""
        )
        val result = mutableListOf<MovieItem>()
        extractMovieItems(data(get("v1/movie/search", request)), result)
        return result.distinctBy { it.id }
    }

    private fun headers(json: JSONArray?): Map<String, String> {
        val result = linkedMapOf<String, String>()
        if (json != null) for (i in 0 until json.length()) {
            val h = json.optJSONObject(i) ?: continue
            val k = h.optString("key")
            val v = h.optString("val", h.optString("value"))
            if (k.isNotBlank()) result[k] = v
        }
        return result
    }

    fun detail(id: String): MovieDetail {
        val root = data(get("v1/movie/detail", mapOf("id" to id))) as? JSONObject
            ?: error("影片详情格式不正确")
        val lines = mutableListOf<MovieLine>()
        val sources = root.optJSONArray("play_from")
        if (sources != null) for (i in 0 until sources.length()) {
            val line = sources.optJSONObject(i) ?: continue
            val code = line.optString("code")
            val inheritedHeaders = headers(line.optJSONArray("player_headers"))
            val inheritedAgent = line.optString("player_user_agent")
            val needParse = line.optBoolean("player_need_to_parse")
            val episodes = mutableListOf<MovieEpisode>()
            val arr = line.optJSONArray("list")
            if (arr != null) for (j in 0 until arr.length()) {
                val ep = arr.optJSONObject(j) ?: continue
                episodes.add(MovieEpisode(
                    id = ep.optString("episode_id"),
                    name = ep.optString("episode_name", "第 " + (j + 1) + " 集"),
                    url = ep.optString("play_url"),
                    fromCode = ep.optString("from_code", code),
                    ready = ep.optBoolean("ready_to_play"),
                    userAgent = ep.optString("user_agent", inheritedAgent),
                    headers = headers(ep.optJSONArray("headers")).ifEmpty { inheritedHeaders },
                    needsParse = needParse
                ))
            }
            lines.add(MovieLine(line.optString("name", "线路 " + (i + 1)), code, episodes))
        }
        return MovieDetail(item(root), lines)
    }

    fun playback(episode: MovieEpisode): PlayAddress {
        if (episode.ready && !episode.needsParse && episode.url.startsWith("http")) {
            return PlayAddress(episode.url, episode.userAgent, episode.headers)
        }
        val reply = data(get("v1/movie_addr/parse_url", mapOf(
            "from_code" to episode.fromCode,
            "play_url" to episode.url,
            "episode_id" to episode.id,
            "type" to "play"
        ))) as? JSONObject ?: error("无法解析播放地址")
        if (!reply.optBoolean("is_playable", true) || !reply.optBoolean("is_free", true)) {
            error("该剧集目前不可直接播放，请使用有权限的账号或选择其他线路")
        }
        val url = reply.optString("play_url", reply.optString("url"))
        require(url.startsWith("https://") || url.startsWith("http://")) { "无效的播放地址" }
        return PlayAddress(
            url,
            reply.optString("user_agent", episode.userAgent),
            headers(reply.optJSONArray("headers")).ifEmpty { episode.headers }
        )
    }
}
