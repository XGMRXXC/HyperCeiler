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
package com.sevtinge.hyperceiler.libhook.rules.home.os4

import android.content.Context
import android.provider.Settings
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook

/**
 * OS4：去除壁纸缩放。
 *
 * OS4 桌面（Flutter + Rust）在启动时读 `Settings.Secure.home_wallpaper_scale_base`
 * 当壁纸缩放基准，本机默认 1.05 —— 壁纸会被略微放大并跟随桌面移动，写成 1.0 就没有。
 *
 * 为什么写入放在这里而不是应用侧：写 secure 设置需要 WRITE_SECURE_SETTINGS，这是
 * signature|privileged 权限，HyperCeiler 自己是普通应用、拿不到（试过，值根本没变）；
 * 而**桌面自己有这个权限**（dumpsys 里 granted=true）。所以让桌面进程自己写。
 *
 * 时机：hook Application.attachBaseContext —— 这是进程里最早的入口之一，拿到 Context
 * 就能写，而且一定发生在桌面自己（含原生代码）读这个值之前，所以不需要重启桌面。
 */
class WallpaperScale : BaseHook() {

    override fun init() {
        val application = findClassIfExists(APPLICATION_CLASS)
        if (application == null) {
            debug("$APPLICATION_CLASS not found")
            return
        }
        val method = findMethodExactIfExists(
            application, "attachBaseContext",
            *arrayOf<Class<*>>(Context::class.java)
        )
        if (method == null) {
            debug("Application.attachBaseContext not found")
            return
        }

        // 只把这个进程（桌面）里的 Application 记下来，其它进程不碰
        xposed().hook(method).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                val context = chain.getArg(0) as? Context
                if (context != null && context.packageName == HOME_PACKAGE) {
                    write(context)
                }
            }.onFailure { debug("write failed: ${it.message}") }
            result
        }
        debug("hooked Application.attachBaseContext")
    }

    private fun write(context: Context) {
        val current = Settings.Secure.getString(context.contentResolver, WALLPAPER_SCALE_BASE)
        if (current == REAL_VALUE) {
            debug("wallpaper scale base already $REAL_VALUE")
            return
        }
        Settings.Secure.putString(context.contentResolver, WALLPAPER_SCALE_BASE, REAL_VALUE)
        debug("wallpaper scale base: $current -> $REAL_VALUE")
    }

    private fun debug(message: String) {
        runCatching { XposedLog.w(TAG, message) }
    }

    private companion object {
        const val APPLICATION_CLASS = "android.app.Application"
        const val HOME_PACKAGE = "com.miui.home"

        /** 桌面读取壁纸缩放基准的设置键。 */
        const val WALLPAPER_SCALE_BASE = "home_wallpaper_scale_base"

        /** 不缩放的值。 */
        const val REAL_VALUE = "1.0"
    }
}
