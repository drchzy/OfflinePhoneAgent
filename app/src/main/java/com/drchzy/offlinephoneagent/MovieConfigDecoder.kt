package com.drchzy.offlinephoneagent

import android.graphics.BitmapFactory
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 原版配置文件格式：
 * 纯文本路由直接解析 JSON；图片路由在位图 R 通道嵌入 AES-256-CBC 密文。
 *
 * 图片只处理 Android BitmapFactory 解码后的像素（不读取文件尾部/PNG 元数据）。
 * HTTP 输入限制大小，防止误选下载链接造成内存占用失控。
 */
internal object MovieConfigDecoder {
    private const val BITMAP_PASSPHRASE = "SPQMkHPWlQ8vBlr3bbqdL9lfICkRQ5Fk"
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    private const val MAX_BITMAP_PIXELS = 8_000_000L
    private const val MAX_TEXT_BYTES = 128 * 1024
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    data class Response(
        val url: String,
        val finalUrl: String,
        val code: Int,
        val elapsedMs: Long,
        val contentType: String,
        val responseText: String,
        val decodeMode: String,
        val bytesReceived: Int,
        val error: String?
    ) {
        val apiService: String?
            get() = try {
                JSONObject(responseText).optString("api_service").trim()
                    .takeIf { it.startsWith("https://") || it.startsWith("http://") }
            } catch (_: Exception) { null }
    }

    /** 请求仅用于配置调试/读取，不带用户 Token，不自动扫描配置源。 */
    fun fetch(url: String): Response {
        val requested = url.trim()
        val start = SystemClock.elapsedRealtime()
        var connection: HttpURLConnection? = null
        var status = -1
        var mime = ""
        var finalUrl = requested
        var readBytes = 0
        val outcome = try {
            val target = URL(requested)
            require(target.protocol == "https" || target.protocol == "http") { "只支持 HTTP/HTTPS 地址" }
            require(target.host.isNotBlank()) { "请填写完整的 URL" }
            connection = (target.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "image/png, image/*, application/json, text/plain, */*")
                setRequestProperty("User-Agent", "OfflinePhoneAgent/Android")
            }
            status = connection.responseCode
            finalUrl = connection.url.toString()
            mime = connection.contentType.orEmpty()
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val payload = ByteArrayOutputStream()
            if (input != null) input.use { stream ->
                val chunk = ByteArray(8192)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    require(payload.size() + count <= MAX_IMAGE_BYTES) {
                        "响应超过 12 MiB 限制，已停止读取"
                    }
                    payload.write(chunk, 0, count)
                }
            }
            val bytes = payload.toByteArray()
            readBytes = bytes.size
            val looksLikeImage = mime.startsWith("image/", ignoreCase = true) ||
                bytes.size >= PNG_MAGIC.size && PNG_MAGIC.indices.all { bytes[it] == PNG_MAGIC[it] } ||
                Regex("\\.(png|jpg|jpeg|bmp|webp)(\\?.*)?$", RegexOption.IGNORE_CASE)
                    .containsMatchIn(requested)
            if (status !in 200..299) {
                Response(requested, finalUrl, status, SystemClock.elapsedRealtime() - start,
                    mime, String(bytes.take(MAX_TEXT_BYTES).toByteArray(), StandardCharsets.UTF_8),
                    "HTTP 错误", readBytes, "HTTP $status")
            } else if (looksLikeImage) {
                val decoded = decodeBitmap(bytes)
                val json = JSONObject(decoded)
                Response(requested, finalUrl, status, SystemClock.elapsedRealtime() - start,
                    mime, json.toString(2), "位图 R 通道 · AES-256-CBC", readBytes, null)
            } else {
                require(bytes.size <= MAX_TEXT_BYTES) { "文本配置超过 128 KiB 限制" }
                val raw = String(bytes, StandardCharsets.UTF_8).trimStart('\uFEFF')
                val pretty = try { JSONObject(raw).toString(2) }
                    catch (_: Exception) { try { JSONArray(raw).toString(2) }
                        catch (_: Exception) { raw } }
                Response(requested, finalUrl, status, SystemClock.elapsedRealtime() - start,
                    mime, pretty, "UTF-8 文本", readBytes, null)
            }
        } catch (e: Exception) {
            Response(requested, finalUrl, status, SystemClock.elapsedRealtime() - start,
                mime, "", "解码或请求失败", readBytes,
                e.javaClass.simpleName + "：" + (e.message ?: "未知错误"))
        } finally {
            connection?.disconnect()
        }
        MovieHttpLog.add(
            "Movie 配置",
            "GET " + requested,
            "HTTP " + outcome.code + "\n" + outcome.contentType +
                "\n解码方式：" + outcome.decodeMode + "\n\n" +
                (outcome.error ?: outcome.responseText),
            outcome.code.takeIf { it >= 0 }
        )
        return outcome
    }

    /** 从真实 Bitmap 的 ARGB 整像素取 R 值，长度存放在前两个像素。 */
    fun decodeBitmap(imageBytes: ByteArray): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        val total = width.toLong() * height.toLong()
        require(width > 0 && height > 0 && total in 42..MAX_BITMAP_PIXELS) {
            "不是有效图片，或图片尺寸超出限制"
        }

        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: error("BitmapFactory 未能解析图片")
        return try {
            val pixels = IntArray(total.toInt())
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            decodePixels(pixels)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 与反编译算法严格一致：len=(R[0]<<8)|R[1]，密文来自 (i+2)*20 的 R 通道。
     * 密钥 SHA256(口令 UTF8)，IV 全 0，AES/CBC/PKCS5Padding。
     */
    fun decodePixels(pixels: IntArray): String {
        require(pixels.size >= 42) { "像素数据太短" }
        fun red(index: Int) = (pixels[index] ushr 16) and 0xFF
        val length = (red(0) shl 8) or red(1)
        require(length in 16..65_535 && length % 16 == 0) {
            "隐写长度无效：$length（AES 密文须为 16 的倍数）"
        }
        require((length.toLong() + 1L) * 20L < pixels.size.toLong()) {
            "图像尺寸不足以承载声明的 $length 字节密文"
        }
        val encrypted = ByteArray(length)
        for (i in 0 until length) encrypted[i] = red((i + 2) * 20).toByte()

        val key = MessageDigest.getInstance("SHA-256")
            .digest(BITMAP_PASSPHRASE.toByteArray(StandardCharsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(ByteArray(16))
        )
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }
}
