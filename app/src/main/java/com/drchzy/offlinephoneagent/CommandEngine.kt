package com.drchzy.offlinephoneagent

import android.os.Handler
import android.os.Looper

class CommandEngine(private val service: PhoneAccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())

    fun execute(raw: String, cb: (Boolean, String) -> Unit) {
        val steps = raw.split(Regex("\\s*(?:然后|；|;)\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
        runSteps(steps, 0, cb)
    }

    private fun runSteps(steps: List<String>, i: Int, cb: (Boolean, String) -> Unit) {
        if (i >= steps.size) return cb(true, "已执行 " + steps.size + " 步")
        one(steps[i]) { ok, msg ->
            if (!ok) cb(false, "第 " + (i + 1) + " 步失败：" + msg)
            else handler.postDelayed({ runSteps(steps, i + 1, cb) }, 650)
        }
    }

    private fun one(c0: String, cb: (Boolean, String) -> Unit) {
        val c = c0.trim()
        when {
            c == "返回" || c.equals("back", true) -> cb(service.goBack(), "返回")
            c in listOf("主页", "桌面", "回到桌面", "home") -> cb(service.goHome(), "主页")
            c in listOf("最近任务", "最近应用", "多任务", "recents") -> cb(service.showRecents(), "最近任务")
            c.startsWith("打开") || c.startsWith("启动") -> {
                val app = c.removePrefix("打开").removePrefix("启动").trim()
                cb(app.isNotEmpty() && service.launchAppByLabel(app), "打开 " + app)
            }
            c.startsWith("点击坐标") -> {
                val a = c.removePrefix("点击坐标").trim().split(Regex("[,，\\s]+"))
                val x = a.getOrNull(0)?.toFloatOrNull()
                val y = a.getOrNull(1)?.toFloatOrNull()
                if (x == null || y == null) cb(false, "格式：点击坐标 500,800")
                else service.tap(x, y) { cb(it, "点击坐标") }
            }
            c.startsWith("点击") -> {
                val t = c.removePrefix("点击").trim()
                cb(t.isNotEmpty() && service.clickText(t), "点击 " + t)
            }
            c.startsWith("输入") -> {
                val t = c.removePrefix("输入").trim()
                cb(t.isNotEmpty() && service.inputText(t), "输入文字")
            }
            c in listOf("上滑", "向上滑") -> service.swipe("up") { cb(it, "上滑") }
            c in listOf("下滑", "向下滑") -> service.swipe("down") { cb(it, "下滑") }
            c in listOf("左滑", "向左滑") -> service.swipe("left") { cb(it, "左滑") }
            c in listOf("右滑", "向右滑") -> service.swipe("right") { cb(it, "右滑") }
            else -> cb(false, "无法识别：" + c)
        }
    }
}
