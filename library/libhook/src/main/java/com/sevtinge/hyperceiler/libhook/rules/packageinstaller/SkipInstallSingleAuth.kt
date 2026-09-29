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

import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 跳过「单次安装授权」。
 *
 * 现象：安全守护处于「增强防护 / 中级模式」时，安装未经应用商店检测的应用会被拦下，
 * 提示「请前往页面右上角"︙" > 单次安装授权」，必须手动授权一次才能继续。
 * 目标：点了「确认安装」就直接开始安装。
 *
 * 链路（全部来自 com.miui.packageinstaller 的 smali 反汇编，不是推断）：
 *
 * 1. 设置项读写类 `B0.c`（混淆名），键名是明文字符串：
 *      B0.c.f:()Ljava/lang/String;   → 读 "security_mode_install_once_date"（默认 ""）
 *      B0.c.B:(Ljava/lang/String;)V  → 写同一个键
 *
 * 2. 判定类 `Ly2.L$a`：
 *      b(Context)Z:
 *          B0.c.f() 为空            → false
 *          否则与 utils.h.a() 比较，相等才 true        ← 即"今天授权过"
 *      a(Context)V: B0.c.B("")   → 清除授权
 *
 * 3. 「今天」的格式在 `com.android.packageinstaller.utils.h.a()` 里（明文类名）：
 *      new SimpleDateFormat("yyyy-MM-dd").format(new Date())
 *
 * 4. 拦截点 `Lo2.d.e(...)`（校验方式分发）：
 *      invoke-virtual {v6, v0}, Ly2/L$a;.b:(Landroid/content/Context;)Z
 *      if-eqz v6, +0x12
 *          false → 走"未授权"分支（就是提示去右上角"︙"的那条）
 *          true  → 走"今天已授权"分支，直接放行
 *
 * 5. 而 `B0.c.f()` 在整个 dex 里**只有第 2 步这一处调用**（已核对：1 处），
 *    所以把它改成"今天已授权"最精准：只影响这一个判定，
 *    不动云端配置、不动风险检测、也不写任何持久化数据。
 */
object SkipInstallSingleAuth : BaseHook() {

    override fun useDexKit() = true

    override fun initDexKit(): Boolean {
        onceDateGetter
        return true
    }

    /**
     * 读 `security_mode_install_once_date` 的 getter。
     *
     * 用这个键名做锚点的方法有两个（getter 与 setter），所以必须同时限定
     * 无参数 + 返回 String，否则 `.single()` 会因为命中两个而失败。
     */
    private val onceDateGetter by lazy {
        requiredMember("SkipInstallSingleAuth1") {
            it.findMethod {
                matcher {
                    addUsingString("security_mode_install_once_date", StringMatchType.Equals)
                    paramCount = 0
                    returnType = "java.lang.String"
                }
            }.single()
        } as Method
    }

    override fun init() {
        onceDateGetter.createHook {
            before { param ->
                // 与 utils.h.a() 完全同款格式（默认 Locale），保证 TextUtils.equals 判定为真
                param.result = SimpleDateFormat("yyyy-MM-dd").format(Date())
            }
        }
    }
}
