package com.drchzy.offlinephoneagent

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var commandInput: EditText

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
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
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
            setPadding(0, dp(14), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })

        AppUpdater.checkForUpdate(this, silent = true)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = PhoneAccessibilityService.instance != null
        statusView.text = if (enabled) "状态：控制服务已连接" else "状态：请先开启无障碍控制服务"
        statusView.setTextColor(if (enabled) Color.rgb(0, 120, 70) else Color.rgb(180, 50, 40))
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

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(10) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
