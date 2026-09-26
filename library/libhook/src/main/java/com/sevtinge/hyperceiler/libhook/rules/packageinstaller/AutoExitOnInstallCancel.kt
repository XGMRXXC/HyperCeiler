/*
  * This file is part of HyperCeiler.

  * HyperCeiler is free software: you can redistribute it and/or modify
  * it under the terms of the GNU Affero General Public License as
  * published by the Free Software Foundation, either version 3 of the
  * License, or (at your option) any later version.

  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU Affero General Public License for more details.

  * You should have received a copy of the GNU Affero General Public License
  * along with this program.  If not, see <https://www.gnu.org/licenses/>.

  * Copyright (C) 2023-2026 HyperCeiler Contributions
*/
package com.sevtinge.hyperceiler.libhook.rules.packageinstaller

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import java.util.Collections
import java.util.WeakHashMap

/**
 * 取消安装后自动退出安装器，不再停留在「已取消安装 / 完成」那页（安装包管理组件 5.5.4.0.0）。
 *
 * 用户在准备页点「取消安装」后，安装器不会自己关掉，而是切到一张结果页：
 * 标题「已取消安装」+ 一个「完成」按钮，得再点一次「完成」才回得去（#12447 / #12206）。
 *
 * 结果页在哪一层没去猜：安装器的 Activity 全都继承 com.android.packageinstaller.miui.BaseActivity，
 * 所以统一挂 BaseActivity#onResume（外加准备页自己的 onResume，防它不调 super），
 * 拿到 decorView 后挂一个 OnGlobalLayoutListener —— 结果页是同页换 fragment 还是新 Activity 都能覆盖。
 * 视图树里一出现「已取消安装」就 finish()，等价于用户自己点了「完成」。
 *
 * Activity 直接从 hook 的参数里取。别想着从 decorView.getContext() 往上找（ContextWrapper 链）：
 * 真机上那样拿到的是 null（安装器里实测日志 "watching null"），整条逻辑会静默失效。
 *
 * 只认「已取消安装」这一个标题：安装成功页、失败页、风险页都不碰。
 * 扫描按 400ms 节流，只读视图 + 只调一次 finish，找不到就一直什么都不做。
 */
object AutoExitOnInstallCancel : BaseHook() {

    private const val BASE_ACTIVITY = "com.android.packageinstaller.miui.BaseActivity"
    private const val PREPARE_ACTIVITY = "com.miui.packageInstaller.NewInstallerPrepareActivity"

    /** 取消后结果页的标题文案，命中就退出（其他结果页不含这句） */
    private const val CANCEL_TEXT = "已取消安装"

    /** 每次布局回调都可能触发，节流一下，避免反复遍历视图树 */
    private const val SCAN_INTERVAL_MS = 400L

    /** 同一个 decorView 只挂一次监听 */
    private val watched: MutableSet<View> = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    override fun init() {
        var hooked = 0
        for (name in arrayOf(BASE_ACTIVITY, PREPARE_ACTIVITY)) {
            val clazz = findClassIfExists(name)
            if (clazz == null) {
                log("$name not found")
                continue
            }
            val onResume = runCatching { clazz.getDeclaredMethod("onResume") }.getOrNull()
            if (onResume == null) {
                log("$name#onResume not found")
                continue
            }
            xposed().hook(onResume).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Activity)?.let { activity ->
                    activity.window?.decorView?.let { decor -> watch(activity, decor) }
                }
                result
            }
            hooked++
        }
        log("hooked $hooked activity onResume")
    }

    private fun watch(activity: Activity, decor: View) {
        val first = synchronized(watched) { watched.add(decor) }
        if (!first) return

        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            private var lastScan = 0L

            override fun onGlobalLayout() {
                val now = System.currentTimeMillis()
                if (now - lastScan < SCAN_INTERVAL_MS) return
                lastScan = now
                runCatching { checkCancelPage(activity, decor, this) }
            }
        }
        runCatching { decor.viewTreeObserver.addOnGlobalLayoutListener(listener) }
        log("watching ${activity.javaClass.simpleName}")
    }

    private fun checkCancelPage(
        activity: Activity,
        decor: View,
        listener: ViewTreeObserver.OnGlobalLayoutListener
    ) {
        if (activity.isFinishing) return
        if (findText(decor, CANCEL_TEXT) == null) return

        log("install cancelled -> finish ${activity.javaClass.simpleName}")
        runCatching { decor.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
        runCatching { activity.finish() }
    }

    private fun findText(view: View, keyword: String): TextView? {
        if (view is TextView && view.visibility == View.VISIBLE) {
            val text = view.text?.toString()?.trim().orEmpty()
            if (text.contains(keyword)) return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findText(view.getChildAt(i), keyword)?.let { return it }
            }
        }
        return null
    }

    private fun log(message: String) {
        android.util.Log.w("InstallerAutoExit", message)
    }
}
