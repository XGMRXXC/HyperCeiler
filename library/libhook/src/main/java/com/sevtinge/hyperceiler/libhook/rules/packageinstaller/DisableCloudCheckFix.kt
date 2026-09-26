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

import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.hookapi.dexkit.DexKit
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import java.lang.reflect.Method

/**
 * 跳过「未查询到此应用的 ICP 备案信息」弹窗（安装包管理组件 5.5.4.0.0）。
 *
 * 旧实现（DisableCloudCheck 早期版本）靠两个锚点：
 *   · `install_btn` + 常量 16        （无网络时用的那个安装按钮）
 *   · 编译产物字符串 `getString(if (shouldUseU…e R.string.start_install)`
 * 5.5.4.0.0 里第二个字符串**已经不存在**（dex 里 0 处），而第一个会去调用它，
 * 于是整个 hook 抛异常失效 —— 这就是弹窗照旧出现的原因。
 *
 * 新版不靠按钮，直接掐住**云端校验**本身：
 * `NewInstallerPrepareActivity` 里有个 suspend 方法（唯一一个参数是协程续体、
 * 返回 Object 的），它拿到的结果决定要不要弹备案提示。这里让它直接返回伪造的
 * `CloudResult$Success`，于是校验"通过"，备案弹窗根本不会出现。
 *
 * 类名 `com.miui.packageInstaller.NewInstallerPrepareActivity` 在 dex 里是**明文**
 * （已核对），所以按类名 + 签名定位，比锚字符串稳。
 */
object DisableCloudCheckFix : BaseHook() {

    override fun useDexKit() = true

    override fun initDexKit(): Boolean {
        cloudCheckMethod
        return true
    }

    /** 新版云端校验方法：NewInstallerPrepareActivity 里参数只有一个续体的 suspend 方法。 */
    private val cloudCheckMethod by lazy<Method?> {
        runCatching {
            val candidates = DexKit.findMemberList<Method>("CloudCheckMethod") {
                it.findMethod {
                    matcher {
                        paramCount = 1
                        returnType = Any::class.java.name
                    }
                }
            }
            // 只认这个类里的；续体参数在混淆后名字不定，所以不强求它的类型名
            candidates.firstOrNull { it.declaringClass.name == PREPARE_ACTIVITY }
        }.getOrNull()
    }

    override fun init() {
        val method = cloudCheckMethod
        if (method == null) {
            log("cloud check method not found, skip")
            return
        }
        method.createHook {
            replace { param ->
                runCatching {
                    val cloudParamsClass = findClass("com.miui.packageInstaller.model.CloudParams")
                    val cloudParams = cloudParamsClass.newInstance()
                    val successClass = findClass("com.miui.packageInstaller.model.CloudResult\$Success")
                    successClass.getDeclaredConstructor(cloudParamsClass).newInstance(cloudParams)
                }.getOrElse {
                    // 拿不到模型类时退回原始实现，别把安装流程弄坏
                    BaseHook.invokeOriginalMethod(param.executable as Method, param.thisObject, param.args)
                }
            }
        }
        log("hooked cloud check: ${method.declaringClass.simpleName}#${method.name}")
    }

    private fun log(message: String) {
        android.util.Log.w("InstallerCloudCheck", message)
    }

    private const val PREPARE_ACTIVITY = "com.miui.packageInstaller.NewInstallerPrepareActivity"
}
