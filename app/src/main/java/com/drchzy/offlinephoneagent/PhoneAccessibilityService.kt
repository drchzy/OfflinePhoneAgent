package com.drchzy.offlinephoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PhoneAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: PhoneAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun showRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun launchAppByLabel(label: String): Boolean {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val target = packageManager.queryIntentActivities(q, 0).firstOrNull {
            it.loadLabel(packageManager).toString().equals(label, true)
        } ?: packageManager.queryIntentActivities(q, 0).firstOrNull {
            it.loadLabel(packageManager).toString().contains(label, true)
        } ?: return false
        val launch = packageManager.getLaunchIntentForPackage(target.activityInfo.packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return true
    }

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) if (clickNodeOrParent(node)) return true
        return false
    }

    private fun clickNodeOrParent(start: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = start
        repeat(8) {
            val cur = node ?: return false
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = cur.parent
        }
        return false
    }

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            if (found != null) return found
        }
        return null
    }

    fun tap(x: Float, y: Float, cb: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) = cb(true)
            override fun onCancelled(d: GestureDescription?) = cb(false)
        }, null)
    }

    fun swipe(dir: String, cb: (Boolean) -> Unit) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val p = when (dir) {
            "up" -> floatArrayOf(cx, h * .78f, cx, h * .25f)
            "down" -> floatArrayOf(cx, h * .25f, cx, h * .78f)
            "left" -> floatArrayOf(w * .82f, cy, w * .18f, cy)
            "right" -> floatArrayOf(w * .18f, cy, w * .82f, cy)
            else -> return cb(false)
        }
        val path = Path().apply {
            moveTo(p[0], p[1])
            lineTo(p[2], p[3])
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 420))
            .build()
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) = cb(true)
            override fun onCancelled(d: GestureDescription?) = cb(false)
        }, null)
    }
}
