package com.drchzy.offlinephoneagent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

object AppUpdater {
    private const val REPO_RELEASES_API =
        "https://api.github.com/repos/drchzy/OfflinePhoneAgent/releases?per_page=25"

    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val apkName: String,
        val preview: Boolean
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
                        options.add(VersionInfo(versionCode, tag, url, name, preview))
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
        Toast.makeText(activity, "正在下载最新版…", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val baseDir = activity.externalCacheDir ?: activity.cacheDir
                val updateDir = File(baseDir, "updates").apply { mkdirs() }
                val apkFile = File(updateDir, info.apkName.ifBlank { "OfflinePhoneAgent-update.apk" })
                updateDir.listFiles()?.forEach { if (it != apkFile) it.delete() }
                if (apkFile.exists()) apkFile.delete()

                val connection = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "OfflinePhoneAgent/" + BuildConfig.VERSION_NAME)
                }

                try {
                    val code = connection.responseCode
                    if (code !in 200..299) throw IOException("下载失败 HTTP " + code)
                    connection.inputStream.use { input ->
                        apkFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } finally {
                    connection.disconnect()
                }

                activity.runOnUiThread {
                    installApk(activity, apkFile)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        "更新下载失败：" + (e.message ?: "网络异常"),
                        Toast.LENGTH_LONG
                    ).show()
                }
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
