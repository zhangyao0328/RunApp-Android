package com.runpulse.app.ui.base

import android.app.Activity
import java.util.Stack

/**
 * 全局 Activity 栈：由 BaseActivity 在 onCreate/onDestroy 中自动维护。
 * 用于「双击返回退出 App」时一次性关闭所有页面。
 */
object ActivityStack {

    private val stack = Stack<Activity>()

    fun push(activity: Activity) {
        stack.add(activity)
    }

    fun pop(activity: Activity) {
        stack.remove(activity)
    }

    /** 关闭栈内所有 Activity（退出 App；前台 Service 不受影响，运动中记录继续） */
    fun finishAll() {
        val snapshot = stack.toList()
        stack.clear()
        snapshot.forEach { it.finish() }
    }
}
