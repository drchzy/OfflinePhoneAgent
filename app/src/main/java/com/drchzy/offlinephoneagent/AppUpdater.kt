package com.drchzy.offlinephoneagent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/drchzy/OfflinePhoneAgent/releases/latest"

    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val apkName: String
    )

    fun checkForUpdate(activity: Activity, silent: Boolean = false) {
        Thread {
            try {
                val latest = fetchLatestRelease()
                activity.runOnUiThread {
                    if (latest.versionCode > BuildConfig.VERSION_CODE) {
                        AlertDialog.Builder(activity)
                            .setTitle("发现新版本")
                            .setMessage(
                                "当前版本：" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n" +
                                    "最新版本：" + latest.versionName + " (" + latest.versionCode + ")"
                            )
                            .setNegativeButton("稍后", null)
                            .setPositiveButton("立即更新") { _, _ ->
                                prepareDownload(activity, latest)
                            }
                            .show()
                    } else if (!silent) {
                        Toast.makeText(
                            activity,
                            "已是最新版本：" + BuildConfig.VERSION_NAME,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                if (!silent) {
                    activity.runOnUiThread {
                        Toast.makeText(
                            activity,
                            "检查更新失败：" + (e.message ?: "网络异常"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.start()
    }

    private fun fetchLatestRelease(): VersionInfo {
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OfflinePhoneAgent/" + BuildConfig.VERSION_NAME)
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("GitHub 返回 HTTP " + code)

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tagName = json.getString("tag_name")
            val cleanVersion = tagName.removePrefix("v")
            val latestCode = cleanVersion.substringAfterLast('.').toIntOrNull()
                ?: throw IOException("无法识别版本号：" + tagName)

            val assets = json.getJSONArray("assets")
            var downloadUrl = ""
            var apkName = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkName = name
                    downloadUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (downloadUrl.isBlank()) throw IOException("最新版 Release 没有找到 APK")

            return VersionInfo(
                versionCode = latestCode,
                versionName = cleanVersion,
                downloadUrl = downloadUrl,
                apkName = apkName
            )
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
