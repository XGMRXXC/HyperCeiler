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
import android.content.Context
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import java.util.Collections
import java.util.WeakHashMap

/**
 * 去掉安装器右上角的「举报」图标（安装包管理组件 5.5.4.0.0）。
 *
 * 安装器所有页面的标题栏都是 com.android.packageinstaller.miui.BaseActivity 里那一条
 * （action_bar_container：[返回] …… [举报][设置]），所以挂它的 onResume，
 * 拿到 decorView 后挂 OnGlobalLayoutListener，找到那个图标就把它自己设成 GONE。
 *
 * 认的是图标的 contentDescription。它来自安装器自己的 string/report_text
 * （默认 "Report"，zh-rCN「举报」，zh-rTW「檢舉」……），
 * 所以运行时按当前 locale 解析这个资源名再比对，另外留一份常见语言的兜底字面量。
 * 只隐藏命中的那一个节点，不动父容器 —— 上一版在安装页上藏过一个带按钮的容器，
 * 结果是整页白屏，这个教训不能再来一次。
 */
object HideReportEntry : BaseHook() {

    private const val BASE_ACTIVITY = "com.android.packageinstaller.miui.BaseActivity"
    private const val PREPARE_ACTIVITY = "com.miui.packageInstaller.NewInstallerPrepareActivity"

    private const val PKG = "com.miui.packageinstaller"
    private const val REPORT_STRING = "report_text"

    /** 解析不到资源时的兜底：安装器支持的语言里 report_text 的常见取值 */
    private val FALLBACK = listOf("举报", "檢舉", "舉報", "Report", "Reportar", "Signaler")

    /** 布局回调可能很密，节流一下；图标是静态的，找到就一直是 GONE */
    private const val SCAN_INTERVAL_MS = 400L

    private val watched: MutableSet<View> = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** 每个 Activity 只做一次标题栏诊断，别刷日志 */
    private val dumped: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    /** report_text 的解析结果：setContentDescription 是热路径，别每次都查资源 */
    @Volatile
    private var cachedTexts: List<String>? = null

    override fun init() {
        hookActivities()
        hookMenus()
        hookContentDescription()
    }

    /**
     * 图标是 MIUIX 的 EndActionMenuItemView（不在安装器自己的 dex 里），它显示「举报」靠的是
     * contentDescription。所以直接在 setContentDescription 里拦：命中就把这一项设成 GONE ——
     * 这时 View 还没参与布局，进页面就不会出现，也不用等布局回调或补时检查。
     */
    private fun hookContentDescription() {
        val method = runCatching {
            View::class.java.getDeclaredMethod("setContentDescription", CharSequence::class.java)
        }.getOrNull()
        if (method == null) {
            log("View#setContentDescription not found")
            return
        }
        xposed().hook(method).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                val view = chain.thisObject as? View
                val text = chain.args.firstOrNull() as? CharSequence
                if (view != null && text != null) {
                    val desc = text.toString().trim()
                    if (desc.isNotEmpty() && reportTexts(view.context).any { it == desc }) {
                        if (view.visibility != View.GONE) {
                            view.visibility = View.GONE
                            log("hid on description: ${view.javaClass.simpleName} desc=$desc")
                        }
                    }
                }
            }
            result
        }
        log("hooked View#setContentDescription")
    }

    private fun hookActivities() {
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

    /**
     * 备选的一条路：菜单项如果在 onCreateOptionsMenu/onPrepareOptionsMenu 里建出来，
     * 菜单阶段就设成不可见，连 View 都不会创建。
     * 注意要挂 android.app.Activity 上 —— 安装器的 BaseActivity 并没有声明这两个方法（实测
     * getDeclaredMethod 直接找不到），声明它们的是框架的 Activity。
     */
    private fun hookMenus() {
        val clazz = findClassIfExists("android.app.Activity")
        if (clazz == null) {
            log("android.app.Activity not found")
            return
        }
        var hooked = 0
        for (method in arrayOf("onCreateOptionsMenu", "onPrepareOptionsMenu")) {
            val m = runCatching { clazz.getDeclaredMethod(method, Menu::class.java) }.getOrNull()
            if (m == null) {
                log("Activity#$method not found")
                continue
            }
            xposed().hook(m).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                val menu = chain.args.firstOrNull() as? Menu
                if (activity != null && menu != null) {
                    runCatching { hideMenuItems(activity, menu) }
                }
                result
            }
            hooked++
        }
        log("hooked $hooked menu method(s)")
    }

    private fun hideMenuItems(activity: Activity, menu: Menu) {
        val wanted = reportTexts(activity)
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i) ?: continue
            val title = item.title?.toString()?.trim().orEmpty()
            if (title.isEmpty() || wanted.none { it == title }) continue
            if (item.isVisible) {
                item.isVisible = false
                log("hid menu item '$title' (groupId=${item.groupId} itemId=${item.itemId})")
            }
        }
    }

    private fun watch(activity: Activity, decor: View) {
        val first = synchronized(watched) { watched.add(decor) }
        if (!first) return
        val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            private var lastScan = 0L

            override fun onGlobalLayout() {
                val now = System.currentTimeMillis()
                if (now - lastScan < SCAN_INTERVAL_MS) return
                lastScan = now
                runCatching { hideIfFound(activity, decor) }
            }
        }
        runCatching { decor.viewTreeObserver.addOnGlobalLayoutListener(listener) }
        // 布局回调要等页面自己再布局一次；补几次主动检查，进页面就藏掉，不用用户碰屏幕
        for (delay in longArrayOf(0L, 150L, 400L, 900L)) {
            runCatching { decor.postDelayed({ runCatching { hideIfFound(activity, decor) } }, delay) }
        }
        log("watching ${activity.javaClass.simpleName}")
    }

    private fun hideIfFound(activity: Activity, decor: View) {
        val wanted = reportTexts(activity)
        val target = findReport(decor, wanted)
        if (target != null) {
            if (target.visibility == View.GONE) return
            target.visibility = View.GONE
            log("hidden report entry: ${target.javaClass.simpleName} desc=${target.contentDescription}")
            return
        }
        dumpBarOnce(activity, decor)
    }

    /**
     * 没命中时把整棵视图树的上层结构记一次（每个 Activity 一次，最多 12 行）。
     * 安装器版本之间标题栏的 id / 描述会变，来回猜不如让它自己报一次。
     */
    private fun dumpBarOnce(activity: Activity, decor: View) {
        if (!dumped.add(activity)) return
        log("view dump (${activity.javaClass.simpleName}):")
        var lines = 0
        fun walk(view: View, depth: Int) {
            if (lines >= 12 || depth > 3) return
            lines++
            val id = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
            val desc = view.contentDescription?.toString().orEmpty()
            val text = (view as? TextView)?.text?.toString().orEmpty()
            log("  ${"  ".repeat(depth)}${view.javaClass.simpleName} id=$id desc=$desc text=$text vis=${view.visibility}")
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
            }
        }
        walk(decor, 0)
    }

    /** 当前 locale 下的 report_text，外加兜底字面量；setContentDescription 是热路径，结果缓存一次 */
    private fun reportTexts(context: Context): List<String> {
        cachedTexts?.let { return it }
        val texts = ArrayList<String>(FALLBACK.size + 1)
        runCatching {
            val res = context.resources
            val id = res.getIdentifier(REPORT_STRING, "string", PKG)
            if (id != 0) texts.add(res.getString(id))
        }
        texts.addAll(FALLBACK)
        val result = texts.distinct()
        cachedTexts = result
        return result
    }

    private fun findReport(view: View, wanted: List<String>): View? {
        if (view.visibility == View.VISIBLE) {
            val desc = view.contentDescription?.toString()?.trim().orEmpty()
            if (desc.isNotEmpty() && wanted.any { it == desc }) return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findReport(view.getChildAt(i), wanted)?.let { return it }
            }
        }
        return null
    }

    private fun log(message: String) {
        android.util.Log.w("InstallerHideReport", message)
    }
}
