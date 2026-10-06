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
        if (!PrefsBridge.getBoolean("miui_package_installer_floating_window", false)) {
            log("floating window switch is off")
            return
        }
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
            log("installer activities will open in a floating window")
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
}
