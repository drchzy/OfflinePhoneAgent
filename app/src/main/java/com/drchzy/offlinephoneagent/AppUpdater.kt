package com.drchzy.offlinephoneagent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

object AppUpdater {
    private val downloading = AtomicBoolean(false)
    private const val REPO_RELEASES_API =
        "https://api.github.com/repos/drchzy/OfflinePhoneAgent/releases?per_page=25"

    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val apkName: String,
        val preview: Boolean,
        val assetSizeBytes: Long = 0L
    )

    private fun isPreviewBuild(): Boolean =
        BuildConfig.VERSION_NAME.contains("preview", ignoreCase = true)

    /**
     * Release 频道：只检查正式 GitHub Release。
     * Preview 频道：同时检查 prerelease 与正式 Release。
     * GitHub Actions Artifacts 不是 Release，不能被应用内升级器直接发现。
     */
    fun checkForUpdate(activity: Activity, silent: Boolean = false) {
        Thread {
            try {
                val newest = fetchLatestRelease()
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (newest != null && newest.versionCode > BuildConfig.VERSION_CODE) {
                        AlertDialog.Builder(activity)
                            .setIcon(R.drawable.yoyo_launcher)
                            .setTitle("发现新版本")
                            .setMessage(
                                "当前版本：" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n" +
                                    "可更新至：" + newest.versionName + " (" + newest.versionCode + ")" +
                                    if (newest.preview) "\n更新渠道：预览版" else "\n更新渠道：正式版"
                            )
                            .setNegativeButton("稍后", null)
                            .setPositiveButton("下载并安装") { _, _ ->
                                prepareDownload(activity, newest)
                            }
                            .show()
                    } else if (!silent) {
                        val channelMessage = if (isPreviewBuild()) {
                            "当前是预览版，GitHub 尚无版本号更高的 Release。\n" +
                                "Actions 构建产物不属于 Release，无法自动更新。"
                        } else {
                            "当前是正式版，GitHub 没有比它更新的正式 Release。"
                        }
                        AlertDialog.Builder(activity)
                            .setIcon(R.drawable.yoyo_launcher)
                            .setTitle("检查更新")
                            .setMessage(
                                "当前版本：" + BuildConfig.VERSION_NAME +
                                    " (" + BuildConfig.VERSION_CODE + ")\n\n" + channelMessage
                            )
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                if (!silent) {
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        AlertDialog.Builder(activity)
                            .setIcon(R.drawable.yoyo_launcher)
                            .setTitle("检查更新失败")
                            .setMessage(e.message ?: "网络异常，请检查 GitHub 连接")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun fetchLatestRelease(): VersionInfo? {
        val connection = (URL(REPO_RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("User-Agent", "OfflinePhoneAgent/" + BuildConfig.VERSION_NAME)
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("GitHub Release API 返回 HTTP " + code)
            }
            val content = connection.inputStream.bufferedReader().use { it.readText() }
            MovieHttpLog.add("检查更新", "GET " + REPO_RELEASES_API,
                "HTTP " + code + "\n\n" + content.take(48_000), code)
            val releases = JSONArray(content)
            val options = mutableListOf<VersionInfo>()
            for (i in 0 until releases.length()) {
                val release = releases.optJSONObject(i) ?: continue
                if (release.optBoolean("draft", false)) continue
                val preview = release.optBoolean("prerelease", false)
                if (preview && !isPreviewBuild()) continue
                val tag = release.optString("tag_name").removePrefix("v")
                val match = Regex("""^1\.0\.(\d+)(?:-preview)?$""").matchEntire(tag) ?: continue
                val versionCode = match.groupValues[1].toIntOrNull() ?: continue
                val assets = release.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                    if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://github.com/")) {
                        options.add(VersionInfo(versionCode, tag, url, name, preview, asset.optLong("size", 0L)))
                        break
                    }
                }
            }
            return options.maxWithOrNull(compareBy<VersionInfo> { it.versionCode }.thenBy { if (it.preview) 0 else 1 })
        } finally {
            connection.disconnect()
        }
    }

    private fun prepareDownload(activity: Activity, info: VersionInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                activity,
                "请先允许“安装未知应用”，返回后再次点“检查更新”即可。",
                Toast.LENGTH_LONG
            ).show()
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.packageName)
                )
            )
            return
        }
        downloadAndInstall(activity, info)
    }

    private fun downloadAndInstall(activity: Activity, info: VersionInfo) {
        if (!downloading.compareAndSet(false, true)) {
            Toast.makeText(activity, "已有更新正在下载", Toast.LENGTH_SHORT).show()
            return
        }
        val cancelled = AtomicBoolean(false)
        val activeConnection = AtomicReference<HttpURLConnection?>(null)
        val dp = activity.resources.displayMetrics.density
        fun dip(value: Int): Int = (value * dp + .5f).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(22), dip(12), dip(22), dip(10))
        }
        val detail = TextView(activity).apply {
            text = "正在连接 GitHub…"
            textSize = 13f
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val downloadedText = TextView(activity).apply {
            text = "0 MB"
            textSize = 12f
        }
        container.addView(detail)
        container.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dip(30)
            ).apply { topMargin = dip(8) }
        )
        container.addView(downloadedText)
        val dialog = AlertDialog.Builder(activity)
                            .setIcon(R.drawable.yoyo_launcher)
            .setTitle("正在下载 · " + info.versionName)
            .setView(container)
            .setNegativeButton("取消下载") { _, _ ->
                cancelled.set(true)
                activeConnection.get()?.disconnect()
            }
            .setCancelable(false)
            .create()
        dialog.show()

        Thread {
            var partial: File? = null
            var connection: HttpURLConnection? = null
            try {
                val baseDir = activity.externalCacheDir ?: activity.cacheDir
                val updateDir = File(baseDir, "updates").apply { mkdirs() }
                val name = info.apkName.ifBlank { "OfflinePhoneAgent-update.apk" }
                val apkFile = File(updateDir, name)
                val tempFile = File(updateDir, "$name.part")
                partial = tempFile
                updateDir.listFiles()?.forEach { if (it != tempFile) it.delete() }
                if (tempFile.exists()) tempFile.delete()

                connection = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "OfflinePhoneAgent/" + BuildConfig.VERSION_NAME)
                }
                activeConnection.set(connection)
                val response = connection.responseCode
                if (response !in 200..299) throw IOException("下载失败 HTTP $response")
                val expectedBytes = connection.contentLengthLong.takeIf { it > 0 }
                    ?: info.assetSizeBytes.takeIf { it > 0 } ?: -1L
                val buffer = ByteArray(32 * 1024)
                var received = 0L
                var lastUpdateAt = 0L
                connection.inputStream.use { input ->
                    tempFile.outputStream().buffered().use { output ->
                        while (true) {
                            if (cancelled.get()) throw IOException("下载已取消")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUpdateAt >= 120L) {
                                lastUpdateAt = now
                                val currentBytes = received
                                activity.runOnUiThread {
                                    if (!cancelled.get() && !activity.isFinishing && !activity.isDestroyed) {
                                        val mb = currentBytes / 1_048_576.0
                                        if (expectedBytes > 0) {
                                            val pct = (currentBytes * 100L / expectedBytes)
                                                .toInt().coerceIn(0, 100)
                                            bar.isIndeterminate = false
                                            bar.progress = pct
                                            detail.text = "下载中 $pct%"
                                            downloadedText.text = String.format(
                                                java.util.Locale.getDefault(),
                                                "%.1f / %.1f MB", mb, expectedBytes / 1_048_576.0
                                            )
                                        } else {
                                            detail.text = "正在下载更新…"
                                            downloadedText.text = String.format(
                                                java.util.Locale.getDefault(), "已下载 %.1f MB", mb
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (cancelled.get()) throw IOException("下载已取消")
                if (received < 1_048_576L ||
                    (expectedBytes > 0 && received != expectedBytes)) {
                    throw IOException("文件不完整（已下载 $received 字节）")
                }
                // 校验 ZIP/APK 最基本结构，避免下载到 HTML 错误页也发起安装。
                ZipFile(tempFile).use { zip ->
                    if (zip.getEntry("AndroidManifest.xml") == null) {
                        throw IOException("下载内容不是有效的 Android APK")
                    }
                }
                if (apkFile.exists() && !apkFile.delete()) {
                    throw IOException("无法替换旧的临时安装包")
                }
                if (!tempFile.renameTo(apkFile)) throw IOException("保存安装包失败")
                partial = null
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed && !cancelled.get()) {
                        bar.isIndeterminate = false
                        bar.progress = 100
                        detail.text = "下载完成，正在启动安装…"
                        dialog.dismiss()
                        runCatching { installApk(activity, apkFile) }.onFailure {
                            Toast.makeText(activity,
                                "打开安装器失败：" + (it.message ?: "未知错误"),
                                Toast.LENGTH_LONG).show()
                        }
                    } else {
                        apkFile.delete()
                        dialog.dismiss()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    dialog.dismiss()
                    if (!cancelled.get() && !activity.isFinishing && !activity.isDestroyed) {
                        AlertDialog.Builder(activity)
                            .setIcon(R.drawable.yoyo_launcher)
                            .setTitle("更新下载失败")
                            .setMessage(e.message ?: "网络异常，请重试")
                            .setPositiveButton("重试") { _, _ -> prepareDownload(activity, info) }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            } finally {
                connection?.disconnect()
                activeConnection.set(null)
                partial?.delete()
                downloading.set(false)
            }
        }.start()
    }

    private fun installApk(activity: Activity, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            activity,
            activity.packageName + ".fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }
}
