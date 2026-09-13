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
        val serviceClass = findClassIfExists(IME_SERVICE_CLASS) ?: return 0
        val helperClass = helperClassOf(serviceClass) ?: return 0
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

    /** 通过稳定的 getter 拿 HyperMaterial helper。 */
    private fun helperOf(service: Any?): Any? {
        if (service == null) return null
        val getter = helperGetterOf(service.javaClass) ?: return null
        return runCatching { getter.invoke(service) }.getOrNull()
    }

    /**
     * 那个 getter：名字固定前缀 + 无参。后缀是构建 flavor（$app_iflytekFullRelease），
     * 可能随构建变化，所以只匹配前缀。
     */
    private fun helperGetterOf(serviceClass: Class<*>): Method? =
        serviceClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name.startsWith(HYPER_MATERIAL_HELPER_GETTER_PREFIX)
        }

    /**
     * helper 类直接从 getter 的**返回类型**拿 —— 不用猜混淆类名。
     * （0.2.790 里上游写死的 bb.t 已经变成另一个无关的 Runnable 类，真 helper 是 bb.x。）
     */
    private fun helperClassOf(serviceClass: Class<*>): Class<*>? =
        helperGetterOf(serviceClass)?.returnType

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

        // 1) 材质支持开关。
        // 注意：**不能**按"第一个 boolean"去找 —— 真 helper（bb.x）里有 5 个 boolean
        // 字段（h/k/l/o/s），挑错会把整个键盘带成深色/普通样式（这就是实测到的回归）。
        // 只认已验证过的字段名，取不到就放弃这一项：宁可不生效，也不破坏外观。
        val supportReady = helperClass.declaredFields.firstOrNull {
            it.type == java.lang.Boolean.TYPE && it.name in MATERIAL_SUPPORT_FIELD_NAMES
        }?.let { field ->
            runCatching {
                field.isAccessible = true
                field.setBoolean(helper, true)
            }.isSuccess
        } ?: false

        // 2) 当前包名：唯一的 String 字段
        helperClass.declaredFields.firstOrNull { it.type == String::class.java }?.let { field ->
            runCatching {
                field.isAccessible = true
                field.set(helper, QUICK_SEARCH_PACKAGE)
            }
        }

        // 3) 包版本表。真 helper 里这个字段**声明类型是 Object**，运行时才装 Map，
        //    所以按 Map 类型找是找不到的（旧实现的第二个 bug）：先认名字，再退化为
        //    "当前值确实是 Map（或还是 null）的 Object 字段"。
        val versionsField = helperClass.declaredFields.firstOrNull {
            it.name in PACKAGE_VERSIONS_FIELD_NAMES
        } ?: helperClass.declaredFields.firstOrNull { field ->
            field.type == Any::class.java &&
                (field.get(helper) == null || field.get(helper) is Map<*, *>)
        }
        val versionsReady = versionsField?.let { field ->
            runCatching {
                field.isAccessible = true
                val versions = LinkedHashMap<Any?, Any?>()
                (field.get(helper) as? Map<*, *>)?.forEach { (k, v) -> versions[k] = v }
                versions[QUICK_SEARCH_PACKAGE] = SUPPORTED_MATERIAL_VERSION
                field.set(helper, versions)
            }.isSuccess
        } ?: false

        // 4) 强制深色/浅色包集合：**只有在材质确实准备好时才动**。
        //    原因：包名对所有输入框都被伪造成搜索包，一旦把它塞进深色集合，
        //    等于把整个键盘强制成深色；如果此时高级材质又没生效，用户看到的就是
        //    "全是深色普通样式"——正是那次回归的观感。宁可不改，也不留这个副作用。
        if (!supportReady || !versionsReady) {
            XposedLog.w(
                TAG,
                "material not ready (support=$supportReady versions=$versionsReady), " +
                    "leave the force light/dark sets untouched"
            )
            return
        }

        val collections = helperClass.declaredFields.filter {
            Collection::class.java.isAssignableFrom(it.type)
        }
        if (collections.size == 2) {
            val dark = isDarkTheme(contextOf(service))
            for ((index, field) in collections.withIndex()) {
                runCatching {
                    field.isAccessible = true
                    val set = LinkedHashSet<Any?>()
                    (field.get(helper) as? Collection<*>)?.forEach { set.add(it) }
                    set.remove(QUICK_SEARCH_PACKAGE)
                    // 约定：先出现的是深色集合，随后是浅色集合（与上游一致）
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
        private const val QUICK_SEARCH_PACKAGE = "com.android.quicksearchbox"

        /** getter 名前缀（后缀是构建 flavor，可能变化）。返回类型就是 helper 类。 */
        private const val HYPER_MATERIAL_HELPER_GETTER_PREFIX = "getHyperMaterialHelper"

        /** 刷新方法：先认上游用的 "k"，取不到就用唯一的无参 void 方法。 */
        private const val REFRESH_METHOD = "k"
        private const val SUPPORTED_MATERIAL_VERSION = 2

        /**
         * 材质支持开关的候选字段名（0.2.790 实测为 h）。
         * 只认名字、不按"第一个 boolean"猜，避免把键盘带成深色/普通样式。
         */
        private val MATERIAL_SUPPORT_FIELD_NAMES = setOf("h")

        /** 包版本表的候选字段名（0.2.790 实测为 u，声明类型是 Object）。 */
        private val PACKAGE_VERSIONS_FIELD_NAMES = setOf("u")
    }
}
