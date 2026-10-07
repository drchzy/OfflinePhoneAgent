package com.drchzy.offlinephoneagent

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var commandInput: EditText
    private lateinit var codexStatusView: TextView
    private lateinit var codexCodeView: TextView
    private lateinit var codexUsageView: TextView
    private lateinit var loginButton: Button

    private val executor = Executors.newCachedThreadPool()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val p = dp(18)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p, p, p)
        }

        root.addView(TextView(this).apply {
            text = "离线手机助手"
            textSize = 26f
            setTextColor(Color.BLACK)
        })

        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(10), 0, dp(12))
        }
        root.addView(statusView)

        root.addView(Button(this).apply {
            text = "打开无障碍设置"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, fullWidth())

        root.addView(Button(this).apply {
            text = "检查更新"
            setOnClickListener { AppUpdater.checkForUpdate(this@MainActivity, silent = false) }
        }, fullWidth())

        commandInput = EditText(this).apply {
            hint = "例如：打开微信 然后 点击 通讯录"
            minLines = 3
            gravity = Gravity.TOP
        }
        root.addView(commandInput, fullWidth())

        root.addView(Button(this).apply {
            text = "执行指令"
            setOnClickListener { executeCommand() }
        }, fullWidth())

        root.addView(TextView(this).apply {
            text = "支持：返回、主页、最近任务、打开应用、点击文字、输入文字、上下左右滑、点击坐标；可用“然后”串联。"
            textSize = 15f
            setPadding(0, dp(14), 0, dp(18))
        })

        root.addView(separator())

        root.addView(TextView(this).apply {
            text = "Codex 用量"
            textSize = 22f
            setTextColor(Color.BLACK)
            setPadding(0, dp(18), 0, dp(8))
        })

        codexStatusView = TextView(this).apply {
            textSize = 15f
            text = "状态：检查登录状态…"
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(codexStatusView)

        codexCodeView = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.rgb(30, 80, 160))
            visibility = View.GONE
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(codexCodeView)

        codexUsageView = TextView(this).apply {
            textSize = 17f
            text = "5小时：--\n每周：--"
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(codexUsageView)

        loginButton = Button(this).apply {
            text = "登录 Codex"
            setOnClickListener { startCodexLogin() }
        }
        root.addView(loginButton, fullWidth())

        root.addView(Button(this).apply {
            text = "刷新 Codex 用量"
            setOnClickListener { refreshCodexUsage() }
        }, fullWidth())

        root.addView(Button(this).apply {
            text = "添加 Codex 用量到桌面"
            setOnClickListener { pinCodexWidget() }
        }, fullWidth())

        root.addView(TextView(this).apply {
            text = "说明：Codex 登录使用设备码授权；Token 加密保存在本机 Android Keystore 中。桌面组件默认约每 30 分钟刷新，也可手动刷新。"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(18))
        })

        setContentView(ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        })

        refreshCodexLoginState()
        AppUpdater.checkForUpdate(this, silent = true)
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
        refreshCodexLoginState()
    }

    private fun refreshAccessibilityStatus() {
        val enabled = PhoneAccessibilityService.instance != null
        statusView.text =
            if (enabled) "状态：控制服务已连接" else "状态：请先开启无障碍控制服务"
        statusView.setTextColor(
            if (enabled) Color.rgb(0, 120, 70) else Color.rgb(180, 50, 40)
        )
    }

    private fun refreshCodexLoginState() {
        val loggedIn = CodexAuthManager(this).currentTokens() != null
        codexStatusView.text =
            if (loggedIn) "Codex：已登录" else "Codex：未登录"
        loginButton.text = if (loggedIn) "重新登录 Codex" else "登录 Codex"
    }

    private fun startCodexLogin() {
        loginButton.isEnabled = false
        codexStatusView.text = "Codex：正在申请设备码…"
        codexCodeView.visibility = View.GONE

        executor.execute {
            try {
                val auth = CodexAuthManager(applicationContext)
                val info = auth.requestDeviceCode()

                runOnUiThread {
                    codexStatusView.text = "Codex：请在浏览器完成授权"
                    codexCodeView.text = "授权码：" + info.userCode + "（已复制）"
                    codexCodeView.visibility = View.VISIBLE

                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Codex device code", info.userCode))

                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.verificationUrl)))
                    Toast.makeText(this, "授权码已复制，请粘贴到网页", Toast.LENGTH_LONG).show()
                }

                auth.completeDeviceLogin(info)

                runOnUiThread {
                    loginButton.isEnabled = true
                    codexStatusView.text = "Codex：登录成功"
                    codexCodeView.visibility = View.GONE
                    Toast.makeText(this, "Codex 登录成功", Toast.LENGTH_SHORT).show()
                    refreshCodexUsage()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loginButton.isEnabled = true
                    codexStatusView.text = "Codex：登录失败"
                    Toast.makeText(
                        this,
                        "登录失败：" + (e.message ?: "未知错误"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun refreshCodexUsage() {
        codexStatusView.text = "Codex：正在刷新用量…"
        executor.execute {
            try {
                val usage = CodexUsageClient(applicationContext).fetch()
                runOnUiThread {
                    codexStatusView.text = "Codex：用量已更新"
                    codexUsageView.text = buildUsageText(usage)
                    CodexUsageWidgetProvider.refreshAll(applicationContext)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    refreshCodexLoginState()
                    Toast.makeText(
                        this,
                        "刷新失败：" + (e.message ?: "未知错误"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun buildUsageText(usage: CodexUsage): String {
        val five = usage.fiveHour
        val week = usage.weekly
        val fiveText = five?.remainingPercent?.roundToInt()?.toString()?.plus("% 剩余") ?: "--"
        val weekText = week?.remainingPercent?.roundToInt()?.toString()?.plus("% 剩余") ?: "--"

        val resetText = five?.resetAtSeconds?.takeIf { it > 0 }?.let {
            DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT,
                Locale.getDefault()
            ).format(Date(it * 1000L))
        }

        return "5小时：" + fiveText +
            "\n每周：" + weekText +
            if (resetText != null) "\n5小时窗口重置：" + resetText else ""
    }

    private fun pinCodexWidget() {
        val manager = AppWidgetManager.getInstance(this)
        if (!manager.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, "当前桌面不支持应用内添加，请长按桌面手动添加", Toast.LENGTH_LONG).show()
            return
        }
        val provider = ComponentName(this, CodexUsageWidgetProvider::class.java)
        val ok = manager.requestPinAppWidget(provider, null, null)
        if (!ok) {
            Toast.makeText(this, "请长按桌面 → 小组件 → 离线手机助手", Toast.LENGTH_LONG).show()
        }
    }

    private fun executeCommand() {
        val service = PhoneAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "请先开启无障碍控制服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val command = commandInput.text.toString().trim()
        if (command.isEmpty()) {
            Toast.makeText(this, "请输入指令", Toast.LENGTH_SHORT).show()
            return
        }

        statusView.text = "状态：执行中…"
        CommandEngine(service).execute(command) { ok, message ->
            runOnUiThread {
                statusView.text = "状态：" + message
                if (!ok) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun separator() = View(this).apply {
        setBackgroundColor(Color.LTGRAY)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        )
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        bottomMargin = dp(10)
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
