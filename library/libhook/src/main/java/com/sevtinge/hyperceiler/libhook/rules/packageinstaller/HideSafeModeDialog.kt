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
import android.widget.Button
import android.widget.TextView
import com.sevtinge.hyperceiler.libhook.base.BaseHook

/**
 * 去掉安装准备页的「建议开启"安全守护"」提示（安装包管理组件 5.5.4.0.0）。
 *
 * 这个提示不是 android.app.Dialog，而是准备页自己弹出来的面板
 * （页面上能看到：标题「建议开启"安全守护"」+ 正文 + 「了解安全守护」「继续安装」「取消安装」），
 * 所以挂 Dialog.show 抓不到它。它由 NewInstallerPrepareActivity$d 那几个协程挂起方法推出来，
 * 直接改状态机不划算，也没必要。
 *
 * 这里用最直白的办法：准备页每次 onResume 后，在它的视图树里找一小段时间 ——
 * 看见标题里带「建议开启」的提示，就点它的「继续安装」，提示消失、安装照常继续。
 * 只读视图、只点这一个按钮；找不到就什么都不做。
 */
object HideSafeModeDialog : BaseHook() {

    private const val ACTIVITY = "com.miui.packageInstaller.NewInstallerPrepareActivity"

    /** 轮询时长与间隔：面板是异步推出来的，等一会儿；超时就放弃。 */
    private const val POLL_TOTAL_MS = 15000L
    private const val POLL_INTERVAL_MS = 500L

    override fun init() {
        val clazz = findClassIfExists(ACTIVITY)
        if (clazz == null) {
            log("$ACTIVITY not found")
            return
        }
        val onResume = runCatching {
            clazz.getDeclaredMethod("onResume")
        }.getOrNull()
        if (onResume == null) {
            log("onResume not found")
            return
        }
        xposed().hook(onResume).intercept { chain ->
            val result = chain.proceed()
            (chain.thisObject as? Activity)?.let { activity ->
                activity.window?.decorView?.let { decor -> startPolling(decor) }
            }
            result
        }
        log("hooked $ACTIVITY#onResume")
    }

    private fun startPolling(decor: View) {
        val deadline = System.currentTimeMillis() + POLL_TOTAL_MS
        val tick = object : Runnable {
            override fun run() {
                val done = runCatching { tryDismiss(decor) }.getOrDefault(false)
                if (!done && System.currentTimeMillis() < deadline) {
                    decor.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
        decor.postDelayed(tick, POLL_INTERVAL_MS)
    }

    /**
     * 找到「建议开启…」标题后，把**提示本身**隐藏掉。
     *
     * 注意：只藏提示，**不点任何按钮** —— 「继续安装 / 取消安装」要留给用户自己选。
     * 做法是从标题往上走，取"最小的、还不包含继续安装按钮的那层容器"，
     * 于是提示（标题+正文+了解安全守护）被隐藏，而按钮所在的更外层保持不动。
     */
    private fun tryDismiss(root: View): Boolean {
        val title = findText(root) { it.contains("建议开启") } ?: return false
        var node: View? = title
        var target: View? = null
        var hops = 0
        while (node != null && hops < 8) {
            if (findButton(node) { it.contains("继续安装") } != null) break
            target = node
            node = node.parent as? View
            hops++
        }
        val hide = target ?: title
        if (hide.visibility == View.GONE) return true
        hide.visibility = View.GONE
        log("hidden tip: ${hide.javaClass.simpleName}")
        return true
    }

    private fun findText(view: View, match: (String) -> Boolean): TextView? {
        if (view is TextView && view.visibility == View.VISIBLE) {
            val text = view.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && match(text)) return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findText(view.getChildAt(i), match)?.let { return it }
            }
        }
        return null
    }

    private fun findButton(view: View, match: (String) -> Boolean): Button? {
        if (view is Button && view.visibility == View.VISIBLE) {
            val text = view.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && match(text)) return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findButton(view.getChildAt(i), match)?.let { return it }
            }
        }
        return null
    }

    private fun log(message: String) {
        android.util.Log.w("InstallerSafeMode", message)
    }
}
