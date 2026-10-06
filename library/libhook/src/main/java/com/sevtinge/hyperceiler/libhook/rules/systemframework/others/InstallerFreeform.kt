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
package com.sevtinge.hyperceiler.libhook.rules.systemframework.others

import android.content.pm.ActivityInfo
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook

/**
 * 让安装器的页面可以真正以悬浮窗（freeform）打开。
 *
 * 只在安装器进程里请求 freeform 是不够的：安装包的 AndroidManifest 里
 * `resizeableActivity=false`，system_server 会因此在 ActivityStarter 阶段直接拒绝自由窗口，
 * 结果窗口仍是 mWindowingMode=fullscreen（已实测）。
 *
 * 所以这里在 system_server 里把安装器 ActivityInfo 的 `isResizeable()` 变成 true ——
 * 系统随后就允许它以自由窗口（悬浮窗）启动，配合 InstallerFloatingWindow 里的
 * freeform options 与平板形态伪装，整个安装器都会以悬浮窗呈现。
 *
 * 与安装器那边共用同一个开关：miui_package_installer_floating_window。
 */
object InstallerFreeform : BaseHook() {

    override fun init() {
        // 与 InstallerFloatingWindow 一致：功能未完成，先停用。
        if (!FEATURE_READY) {
            log("feature disabled (not ready)")
            return
        }
        if (!PrefsBridge.getBoolean("miui_package_installer_floating_window", false)) return

        runCatching {
            ActivityInfo::class.java.getDeclaredMethod("isResizeable").createHook {
                after { param ->
                    runCatching {
                        val info = param.thisObject as? ActivityInfo ?: return@after
                        if (info.packageName == INSTALLER_PKG) {
                            param.result = true
                        }
                    }
                }
            }
            log("installer activities are now considered resizeable (freeform allowed)")
        }.onFailure { log("hook isResizeable failed: $it") }

        // 有些路径直接读字段而不是调方法。
        runCatching {
            ActivityInfo::class.java.getDeclaredField("resizeable").apply { isAccessible = true }
            log("resizeable field exists (kept as is)")
        }
    }

    private fun log(message: String) {
        android.util.Log.w("InstallerFreeform", message)
    }

    private const val INSTALLER_PKG = "com.miui.packageinstaller"

    /** 与 InstallerFloatingWindow 共用的功能总开关。 */
    private const val FEATURE_READY = false
}
