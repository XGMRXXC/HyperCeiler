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
package com.sevtinge.hyperceiler.libhook.rules.phrase

import android.view.inputmethod.EditorInfo
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook

/**
 * 超级小爱输入法：强制所有页面使用"高级材质"（旧版实现方式）。
 *
 * 两级开关：「恢复旧版高级材质实现方式」+「所有页面使用高级材质」，
 * 二级依赖一级（见 xiaoai_ime.xml 的 android:dependency），入口再判一次。
 *
 * 背景（对着 0.2.343 / 0.2.790 两版 dex 核对过）：
 *  - 新版（0.2.790）把材质启用交给了
 *    `android.inputmethodservice.InputMethodServiceInjector#setHyperMaterialEnabled`，
 *    而 HyperOS 4.0 的框架里**没有这个方法**（miui-framework.jar 有类无方法），
 *    调用失败后回退成深色不透明键盘 —— 也就是"大黑块"。
 *  - 旧版（0.2.343）没有这个调用：helper `bb.s` 自带 RuntimeShader **自己画**材质
 *    （着色器原文已抽出为 glass-shader-full.agsl），配合"包名伪装成全局搜索"的判定。
 *
 * 这里用**旧版那套判定**：把 editorInfo.packageName 临时伪装成
 * com.android.quicksearchbox，让输入法把任何输入框都当成搜索页；同时把搜索包补进
 * helper 的白名单/版本表。
 *
 * ⚠️ 有一条线绝不能碰：**材质支持位**（helper 里的那个 boolean）。
 *    强制它会让输入法去调用那个不存在的系统接口，失败后落回深色不透明键盘
 *    —— 之前复现过两次。这里只动白名单，不动支持位。
 *
 * ⚠️ 另外，往键盘窗口里画东西（无论是插一层 View 还是把面板的前景换成着色器）
 *    已经被验证是错的：键盘本体是 Compose 画的，插进去只会被它自己的底色盖住，
 *    唯一透得出来的场景恰好是"本来就有材质"的页面；改成前景又会连带盖住候选区
 *    （实测白底冒到输入法之上）。所以这一版**不做任何绘制**。
 */
class XiaoAiSearchMaterial : BaseHook() {

    override fun init() {
        val serviceClass = findClassIfExists(IME_SERVICE_CLASS)
        if (serviceClass == null) {
            log("$IME_SERVICE_CLASS not found, skip")
            return
        }
        var hooked = 0
        for (name in arrayOf("onStartInput", "onStartInputView")) {
            val method = findMethodExactIfExists(
                serviceClass, name,
                *arrayOf<Class<*>>(EditorInfo::class.java, java.lang.Boolean.TYPE)
            ) ?: continue
            xposed().hook(method).intercept { chain ->
                val editorInfo = chain.getArg(0) as? EditorInfo
                    ?: return@intercept chain.proceed()
                val originalPackage = editorInfo.packageName
                editorInfo.packageName = QUICK_SEARCH_PACKAGE
                try {
                    runCatching { prepareWhitelist(chain.thisObject) }
                        .onFailure { log("prepare failed: ${it.message}") }
                    chain.proceed()
                } finally {
                    editorInfo.packageName = originalPackage
                }
            }
            hooked++
        }
        log("hooked $hooked input lifecycle method(s) with the legacy approach")
    }

    /** 把搜索包补进 helper 的白名单/版本表；**不动材质支持位**。 */
    private fun prepareWhitelist(service: Any?) {
        val helper = helperOf(service) ?: return
        val helperClass = helper.javaClass

        // 当前包名：唯一的 String 字段
        helperClass.declaredFields.firstOrNull { it.type == String::class.java }?.let { field ->
            runCatching {
                field.isAccessible = true
                field.set(helper, QUICK_SEARCH_PACKAGE)
            }
        }

        // 包版本表：声明类型是 Object、运行时才是 Map，所以先认名字、再按值类型兜底
        val versionsField = helperClass.declaredFields
            .firstOrNull { it.name in PACKAGE_VERSIONS_FIELD_NAMES }
            ?: helperClass.declaredFields.firstOrNull { field ->
                field.type == Any::class.java &&
                    (field.get(helper) == null || field.get(helper) is Map<*, *>)
            }
        versionsField?.let { field ->
            runCatching {
                field.isAccessible = true
                val versions = LinkedHashMap<Any?, Any?>()
                (field.get(helper) as? Map<*, *>)?.forEach { (k, v) -> versions[k] = v }
                versions[QUICK_SEARCH_PACKAGE] = SUPPORTED_MATERIAL_VERSION
                field.set(helper, versions)
            }
        }
    }

    /** 通过稳定 getter 拿 HyperMaterial helper（它的返回类型就是 helper 类）。 */
    private fun helperOf(service: Any?): Any? {
        if (service == null) return null
        val getter = service.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name.startsWith(HYPER_MATERIAL_HELPER_GETTER_PREFIX)
        } ?: return null
        return runCatching { getter.invoke(service) }.getOrNull()
    }

    private fun log(message: String) {
        android.util.Log.w("XiaoAiMaterial", message)
        runCatching { XposedLog.w(TAG, message) }
    }

    private companion object {
        const val IME_SERVICE_CLASS = "com.mi.ime.MiInputMethodService"
        const val QUICK_SEARCH_PACKAGE = "com.android.quicksearchbox"
        const val HYPER_MATERIAL_HELPER_GETTER_PREFIX = "getHyperMaterialHelper"

        /** 0.2.790 实测的包版本表字段名（声明类型是 Object）。 */
        val PACKAGE_VERSIONS_FIELD_NAMES = setOf("u")

        const val SUPPORTED_MATERIAL_VERSION = 2
    }
}
