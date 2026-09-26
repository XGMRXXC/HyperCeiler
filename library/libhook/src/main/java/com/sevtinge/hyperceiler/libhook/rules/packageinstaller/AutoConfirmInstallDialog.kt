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

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.sevtinge.hyperceiler.libhook.base.BaseHook

/**
 * 安装器里的两个确认弹窗自动点掉（安装包管理组件 5.5.4.0.0）。
 *
 * 为什么不是"伪造云端校验结果"：实测过 —— 伪造 CloudResult$Success 之后流程**过了弹窗**，
 * 但带过去的 CloudParams 是空的（日志里 appInfo=null、appType=null…），准备页于是停在
 * 「安装包扫描中，请稍候」一直等数据，根本不会开始。手动点掉弹窗则一切正常。
 * 所以正确做法是**替用户点确认**，让数据链路原样走完。
 *
 * 实现方式：挂 Dialog.show()，等它真正显示出来后遍历视图树，按**按钮文案**匹配再
 * performClick()。两套文案分别由两个开关控制：
 *   · cloudCheckConfirm —— 「未查询到 ICP 备案信息」那个弹窗（继续/继续安装）
 *   · unknownSourceAllow —— 「未知来源安装」那个弹窗（允许）
 * 用文案匹配而不是资源 id：这些弹窗在 5.5.4.0.0 里换了实现，资源 id 不稳定，
 * 而按钮文字是用户看得见的东西，反而稳。
 */
class AutoConfirmInstallDialog(
    /** 要自动点掉的按钮文案集合 */
    private val buttonTexts: Set<String>
) : BaseHook() {

    override fun init() {
        val dialogClass = findClassIfExists("android.app.Dialog")
        if (dialogClass == null) {
            log("Dialog not found")
            return
        }
        val show = findMethodExactIfExists(show = "show", clazz = dialogClass) ?: run {
            log("Dialog.show not found")
            return
        }
        xposed().hook(show).intercept { chain ->
            val result = chain.proceed()
            val dialog = chain.thisObject as? Dialog
            if (dialog != null) {
                // 布局完成后再找按钮；找不到就算了，绝不影响弹窗本身
                dialog.window?.decorView?.post { clickConfirm(dialog) }
            }
            result
        }
        log("hooked Dialog.show, texts=$buttonTexts")
    }

    private fun findMethodExactIfExists(show: String, clazz: Class<*>): java.lang.reflect.Method? =
        runCatching { clazz.getDeclaredMethod(show) }.getOrNull()

    private fun clickConfirm(dialog: Dialog) {
        runCatching {
            val decor = dialog.window?.decorView ?: return
            if (!dialog.isShowing) return
            val target = findButton(decor) ?: return
            log("click '${target.text}'")
            target.performClick()
            dialog.dismiss()
        }.onFailure { log("click failed: ${it.message}") }
    }

    private fun findButton(view: View): Button? {
        if (view is Button && view.visibility == View.VISIBLE) {
            if (view.text?.toString()?.trim() in buttonTexts) return view
        }
        // 有些弹窗的按钮是 TextView，包在可点的容器里
        if (view is TextView && view.visibility == View.VISIBLE) {
            if (view.text?.toString()?.trim() in buttonTexts && view.isClickable) return null
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findButton(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun log(message: String) {
        android.util.Log.w("InstallerAutoConfirm", message)
    }
}
