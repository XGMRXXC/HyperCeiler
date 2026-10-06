/*
  * This file is part of HyperCeiler.

  * HyperCeiler is free software: you can redistribute it and/or modify
  * it under the terms of the GNU Affero General Public License as
  * published by the Free Software Foundation, either version 3 of the
  * License.

  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU Affero General Public License for more details.

  * You should have received a copy of the GNU Affero General Public License
  * along with this program.  If not, see <https://www.gnu.org/licenses/>.

  * Copyright (C) 2023-2026 HyperCeiler Contributions
*/
package com.sevtinge.hyperceiler.libhook.rules.packageinstaller

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook

/**
 * 让安装器的页面跳转以悬浮窗（PAD 风格）弹出。
 *
 * 背景：手机版与 PAD 版的安装器其实是同一套代码（两个包的 dex 里都有
 * `miuix_floating_activity_info_key`、`miui-magic-windows`、`mWindowingMode=freeform`
 * 这些 MIUIX 悬浮 Activity 框架的痕迹），差别只在运行时按设备形态走的分支。
 * 所以这里不去改形态判断（那会连带改掉布局），而是直接在安装器**自己发起 Activity 跳转**时
 * 要求系统用自由窗口（WINDOWING_MODE_FREEFORM = 5）打开。
 *
 * 拦截点选 `Instrumentation.execStartActivity(...)`：安装器里所有 startActivity /
 * startActivityForResult 最终都会走到它，而且它本身就带 options Bundle，改起来最干净。
 * 注意：用户从文件管理器点开 APK 的那一次启动不经过安装器进程，所以第一个页面仍是全屏，
 * 之后的页面（安装中、完成页等）都会以悬浮窗出现。
 */
object InstallerFloatingWindow : BaseHook() {

    override fun init() {
        // 功能尚未完成（目前只做到"悬浮窗样式"，还不是真正的自由窗口 freeform），
        // 所以这里用 FEATURE_READY 总开关把它停用；设置页里那个开关也已 disabled。
        if (!FEATURE_READY) {
            log("feature disabled (not ready)")
            return
        }
        if (!PrefsBridge.getBoolean("miui_package_installer_floating_window", false)) {
            log("floating window switch is off")
            return
        }

        // 主手段：让安装器进程以为自己在平板/折叠机上，MIUIX 的悬浮 Activity 框架
        // （miuix.appcompat.app.floatingactivity）就会接管全部 Activity —— 包括从文件
        // 管理器打开的第一个页面，这正是"整个安装器都是悬浮窗"的来源。
        pretendTablet()

        // 决定性手段：对比 PAD 版（5.5.4.0.2P）与手机版的 manifest 发现，
        // PAD 上安装器 Activity 用的是 `Theme.DayNight.FloatingWindow.NoTitle`，
        // 手机版用的是 `Theme.DayNight.NoTitle` —— 而 MIUIX 正是靠主题里的
        // `isMiuixFloatingTheme` 属性决定要不要把 Activity 做成悬浮窗。
        // 手机版资源里同样带 `Theme.DayNight.FloatingWindow`，所以这里直接给安装器的
        // 每个 Activity 在 onCreate 之前套上悬浮主题。
        applyFloatingTheme()

        // 辅手段：跳转时再要求一次自由窗口（有些路径不看主题）。
        hookStartActivity()
    }

    /** 在每个 Activity 的 onCreate 之前套用悬浮窗主题。 */
    private fun applyFloatingTheme() {
        runCatching {
            android.app.Activity::class.java
                .getDeclaredMethod("onCreate", Bundle::class.java)
                .createHook {
                    before { param ->
                        runCatching {
                            val activity = param.thisObject as? android.app.Activity ?: return@before
                            val res = activity.resources
                            val id = FLOATING_THEMES.firstNotNullOfOrNull { name ->
                                res.getIdentifier(name, "style", activity.packageName).takeIf { it != 0 }
                            } ?: return@before
                            activity.setTheme(id)
                            log("applied floating theme to ${activity.javaClass.simpleName}")
                        }
                    }
                }
            log("floating theme hook installed")
        }.onFailure { log("applyFloatingTheme failed: $it") }
    }

    /** 把设备形态相关的静态标志设为"平板/折叠"。 */
    private fun pretendTablet() {
        val cls = runCatching { Class.forName("miui.os.Build") }.getOrNull()
        if (cls == null) {
            log("miui.os.Build not found")
            return
        }
        listOf("IS_MIPAD", "IS_TABLET", "IS_PAD", "IS_FOLDABLE").forEach { name ->
            runCatching {
                val field = cls.getDeclaredField(name)
                field.isAccessible = true
                field.setBoolean(null, true)
                log("$name -> true")
            }
        }
    }

    private fun hookStartActivity() {
        runCatching {
            val instrumentation = Class.forName("android.app.Instrumentation")
            val method = instrumentation.getDeclaredMethod(
                "execStartActivity",
                android.content.Context::class.java,
                android.os.IBinder::class.java,
                android.os.IBinder::class.java,
                android.app.Activity::class.java,
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java
            )
            method.createHook {
                before { param ->
                    runCatching {
                        // execStartActivity(Context, IBinder, IBinder, Activity, Intent, int, Bundle)
                        // —— options Bundle 固定在最后一个参数位。
                        val index = 6
                        val original = param.args.getOrNull(index) as? Bundle
                        param.args[index] = freeformOptions(original)
                    }
                }
            }
            log("activity transitions also request freeform")
        }.onFailure { log("hook execStartActivity failed: $it") }
    }

    /** 在原有 options 上追加"自由窗口"模式；任何一步失败都退回原样，不影响启动。 */
    private fun freeformOptions(base: Bundle?): Bundle? = runCatching {
        val options = ActivityOptions.makeBasic()
        // WINDOWING_MODE_FREEFORM = 5（隐藏 API，反射调用）
        options.javaClass
            .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
            .invoke(options, 5)
        val out = options.toBundle()
        // 把调用方原本的 options 合并回来（动画等），同键时保留我们设的窗口模式。
        if (base != null) {
            val merged = Bundle(base)
            merged.putAll(out)
            merged
        } else {
            out
        }
    }.getOrElse { base }

    private fun log(message: String) {
        android.util.Log.w("InstallerFloatWin", message)
    }

    /** PAD 版用的就是第一个；后两个作为不同 MIUI 版本的兜底。 */
    private val FLOATING_THEMES = listOf(
        "Theme.DayNight.FloatingWindow.NoTitle",
        "Theme.DayNight.FloatingWindow",
        "Theme.Light.FloatingWindow",
    )

    /**
     * 功能总开关。等"真正的自由窗口（freeform）"接通后再改成 true ——
     * 目前只实现了悬浮窗样式，窗口本身仍是全屏，所以先停用。
     */
    private const val FEATURE_READY = false
}
