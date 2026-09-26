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

import android.view.View
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import java.lang.reflect.Method

/**
 * 去掉安装准备页里的「安全守护」提示条目（安装包管理组件 5.5.4.0.0）。
 *
 * 旧的 DisableSafeModelTip 在这个版本上**根本没跑**：LSPosed 日志里是
 * "Skip hook because initDexKit failed" —— 它按 `SafeModeTipViewObject` 里一个
 * **boolean** 成员匹配，而这个类现在没有任何 boolean
 * （字段只有 `m: CloudParams`，方法只有 l/m(Context, CloudParams, I2/d, J2/c)V、
 * E/D(ViewHolder)V、m()I、r(RecyclerView.D)V），锚点找不到就直接把整个 hook 放弃了。
 *
 * 这里改为挂它**确实存在**的那个绑定方法 `r(RecyclerView$ViewHolder)`：
 * 走完原始逻辑之后把这一条的 itemView 隐藏掉，于是提示不再出现在列表里。
 */
object HideSafeModeTip : BaseHook() {

    override fun init() {
        val method = findBindMethod()
        if (method == null) {
            log("SafeModeTipViewObject bind method not found, skip")
            return
        }
        xposed().hook(method).intercept { chain ->
            val result = chain.proceed()
            runCatching {
                // 参数是 ViewHolder，藏它的 itemView
                val holder = chain.args.firstOrNull()
                val itemView = holder?.javaClass?.getMethod("itemView")?.invoke(holder) as? View
                itemView?.visibility = View.GONE
            }.onFailure { log("hide failed: ${it.message}") }
            result
        }
        log("hooked ${method.declaringClass.simpleName}#${method.name}")
    }

    private fun findBindMethod(): Method? {
        val clazz = findClassIfExists(TIP_CLASS) ?: return null
        // r(RecyclerView$ViewHolder)：参数是 RecyclerView 的 ViewHolder
        return clazz.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 1 && m.parameterTypes[0].name.startsWith("androidx.recyclerview.widget.RecyclerView")
        }
    }

    private fun log(message: String) {
        android.util.Log.w("InstallerSafeTip", message)
    }

    private const val TIP_CLASS = "com.miui.packageInstaller.ui.listcomponets.SafeModeTipViewObject"
}
