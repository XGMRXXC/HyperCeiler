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

import android.content.Context
import android.content.res.Configuration
import android.view.inputmethod.EditorInfo
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 让超级小爱输入法在**所有页面**都用"全局搜索页"那套 HyperMaterial 高级材质。
 *
 * 思路来自 HyperChanger 的 SearchPageAppearance（Apache-2.0，Copyright 2026 btm_m）：
 * 输入法按 `editorInfo.packageName` 决定材质档位，所以把包名临时伪造成全局搜索
 * （com.android.quicksearchbox），它就会给任何输入框套上搜索页那套材质。
 *
 * 与原版的关键区别（也是这次要修的"新版本失效"问题）：原版把混淆后的类名/字段名
 * 硬编码（bb.t / xe.b / f3472t…f3476x / f3458a），输入法一更新这些名字就全部变化，
 * 于是静默失效。这里改成**按结构取**：
 *   - 唯一的 boolean 字段            -> 材质支持开关，置 true
 *   - 唯一的 String 字段             -> 当前包名，写搜索包名
 *   - Map 字段                       -> 包版本表，写入 搜索包名 -> 2
 *   - 两个 Set/Collection 字段        -> 强制深色/浅色包集合，按当前主题增删
 *   - Context                        -> 直接用输入法 Service 自己（它本身就是 Context）
 *   - 刷新方法                        -> 先认 k，取不到就取唯一的无参 void 方法
 * 另外每个钩子都 runCatching 兜底：以后输入法再改名只会"降级失效"，不会整块崩。
 */
class XiaoAiSearchMaterial : BaseHook() {

    override fun init() {
        val serviceClass = findClassIfExists(IME_SERVICE_CLASS)
        if (serviceClass == null) {
            XposedLog.w(TAG, "$IME_SERVICE_CLASS not found, skip")
            return
        }

        var hooked = 0
        // 输入法在多个生命周期回调里都会重置材质档位，这几个都要覆盖
        for (name in arrayOf("onStartInput", "onStartInputView")) {
            // 显式传 Class 数组，避开 Java vararg 的 Class/Any 重载歧义
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
                    // onStartInputView() 返回前会自己刷新一次材质，所以要先把资格准备好
                    runCatching { prepare(helperOf(chain.thisObject), chain.thisObject) }
                    chain.proceed()
                } finally {
                    editorInfo.packageName = originalPackage
                    runCatching { applyMaterial(chain.thisObject) }
                }
            }
            hooked++
        }

        findMethodExactIfExists(serviceClass, "onWindowShown", *arrayOf<Class<*>>())?.let { method ->
            xposed().hook(method).intercept { chain ->
                val result = chain.proceed()
                runCatching { applyMaterial(chain.thisObject) }
                result
            }
            hooked++
        }

        hooked += hookHelperRefresh()

        if (hooked == 0) {
            XposedLog.w(TAG, "no compatible input lifecycle method was found")
        } else {
            XposedLog.i(TAG, "hooked $hooked input lifecycle methods")
        }
    }

    /**
     * 拦截 helper 自己的刷新方法：把资格准备放在它内部评估之前，
     * 这样材质视图会在原本的生命周期里就被创建，而不是事后补。
     */
    private fun hookHelperRefresh(): Int {
        val helperClass = findClassIfExists(HYPER_MATERIAL_HELPER_CLASS) ?: return 0
        val refresh = refreshMethodOf(helperClass) ?: return 0
        xposed().hook(refresh).intercept { chain ->
            runCatching { prepare(chain.thisObject, null) }
            chain.proceed()
        }
        return 1
    }

    /** 刷新方法：先认原来的 k，取不到就用唯一的无参 void 方法。 */
    private fun refreshMethodOf(helperClass: Class<*>): Method? {
        findMethodExactIfExists(helperClass, REFRESH_METHOD, *arrayOf<Class<*>>())?.let { return it }
        return helperClass.declaredMethods.firstOrNull {
            it.parameterCount == 0 && it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers)
        }
    }

    /** 通过稳定的 getter 拿 Hypermaterial helper。 */
    private fun helperOf(service: Any?): Any? {
        if (service == null) return null
        val getter = service.javaClass.methods.firstOrNull {
            it.name == HYPER_MATERIAL_HELPER_GETTER && it.parameterCount == 0
        } ?: return null
        return runCatching { getter.invoke(service) }.getOrNull()
    }

    /** 拿 Context：直接用输入法 Service 自己，不再去 helper 里找那个会被改名的字段。 */
    private fun contextOf(service: Any?): Context? = service as? Context

    /** 刷新 helper 的材质状态（对应原版的 applySearchMaterial）。 */
    private fun applyMaterial(service: Any?) {
        val helper = helperOf(service) ?: return
        prepare(helper, service)
        val refresh = refreshMethodOf(helper.javaClass) ?: return
        runCatching {
            refresh.isAccessible = true
            refresh.invoke(helper)
        }
    }

    /**
     * 把"全局搜索"加入 helper 的材质白名单。字段全部按类型/结构定位，不依赖混淆名。
     */
    private fun prepare(helper: Any?, service: Any?) {
        if (helper == null) return
        val helperClass = helper.javaClass

        // 1) 材质支持开关：唯一的 boolean 字段
        findFirstFieldByExactType(helperClass, java.lang.Boolean.TYPE)?.let { field ->
            runCatching {
                field.isAccessible = true
                field.setBoolean(helper, true)
            }
        }

        // 2) 当前包名：唯一的 String 字段
        findFirstFieldByExactType(helperClass, String::class.java)?.let { field ->
            runCatching {
                field.isAccessible = true
                field.set(helper, QUICK_SEARCH_PACKAGE)
            }
        }

        // 3) 包版本表：Map 字段，写入 搜索包名 -> 2
        helperClass.declaredFields.firstOrNull { Map::class.java.isAssignableFrom(it.type) }?.let { field ->
            runCatching {
                field.isAccessible = true
                val versions = LinkedHashMap<Any?, Any?>()
                (field.get(helper) as? Map<*, *>)?.forEach { (k, v) -> versions[k] = v }
                versions[QUICK_SEARCH_PACKAGE] = SUPPORTED_MATERIAL_VERSION
                field.set(helper, versions)
            }
        }

        // 4) 强制深色/浅色包集合：两个 Collection 字段，按当前主题增删搜索包名
        val collections = helperClass.declaredFields.filter {
            Collection::class.java.isAssignableFrom(it.type)
        }
        if (collections.isNotEmpty()) {
            val dark = isDarkTheme(contextOf(service))
            for ((index, field) in collections.withIndex()) {
                runCatching {
                    field.isAccessible = true
                    val set = LinkedHashSet<Any?>()
                    (field.get(helper) as? Collection<*>)?.forEach { set.add(it) }
                    set.remove(QUICK_SEARCH_PACKAGE)
                    // 约定：先出现的那个是深色集合，随后是浅色集合
                    val isDarkSet = index == 0
                    if (isDarkSet == dark) set.add(QUICK_SEARCH_PACKAGE)
                    field.set(helper, set)
                }
            }
        }
    }

    private fun isDarkTheme(context: Context?): Boolean {
        val uiMode = context?.resources?.configuration?.uiMode ?: return false
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        private const val IME_SERVICE_CLASS = "com.mi.ime.MiInputMethodService"
        private const val HYPER_MATERIAL_HELPER_CLASS = "bb.t"
        private const val QUICK_SEARCH_PACKAGE = "com.android.quicksearchbox"
        private const val HYPER_MATERIAL_HELPER_GETTER =
            "getHyperMaterialHelper\$app_iflytekFullRelease"
        private const val REFRESH_METHOD = "k"
        private const val SUPPORTED_MATERIAL_VERSION = 2
    }
}
