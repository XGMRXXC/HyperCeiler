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

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook

/**
 * 超级小爱输入法的"高级材质"。两级开关（见 xiaoai_ime.xml）：
 *
 *  · 开关一「恢复旧版高级材质实现方式」→ 画出那层玻璃（旧版实现方式的复刻）。
 *  · 开关二「所有页面使用高级材质」（依赖开关一）→ 在此之上再叠加旧版那套判定 hook。
 *
 * 背景（对着 0.2.343 / 0.2.790 两版 dex 核对过）：
 *  - 旧版（0.2.343）自己画：helper `bb.s` 带一个 RuntimeShader，着色器源码明文在 dex 里
 *    （已抽出为 glass-shader-full.agsl）——纵向 alpha 渐变 + 4x4 Bayer 抖动，
 *    不采样背后内容、不调用系统接口。
 *  - 新版（0.2.790）改成调用
 *    `android.inputmethodservice.InputMethodServiceInjector#setHyperMaterialEnabled`，
 *    而 HyperOS 4.0 的框架里没有这个方法，调用失败后回退成深色不透明键盘。
 *
 * 画在哪（这一步试错很多，结论有视图树为证）：
 *  - 往 android.R.id.content 插一层 View ✗：键盘本体是 Compose 画的（inputArea → c → r → w），
 *    它自己画底色，插在背后的东西只在"本来就透明"的材质页面透得出来。
 *  - 换成 parentPanel 的前景 ✗：这块面板还包含候选区，比可见键盘高，白底会溢到输入法之上。
 *  - 现在：**分别**给 inputArea（键盘本体）和 miui_bottom_area（剪贴板/快捷键条）
 *    挂前景 —— 各自边界精确、不会溢出，两截颜色也因此统一，而且是画在内容之上，
 *    所以所有页面都看得见。
 *
 * ⚠️ 绝不碰"材质支持位"：强制它会让输入法去调用那个不存在的系统接口、失败、
 *    然后落回深色不透明键盘（复现过两次的"大黑块"）。
 */
class XiaoAiSearchMaterial(
    /** 开关二：是否叠加旧版那套判定 hook（包名伪装 + 白名单）。 */
    private val forceAllPages: Boolean
) : BaseHook() {

    private var decorated = false
    private var drawWarned = false
    private var geometryLogged = false

    override fun init() {
        val serviceClass = findClassIfExists(IME_SERVICE_CLASS)
        if (serviceClass == null) {
            log("$IME_SERVICE_CLASS not found, skip")
            return
        }
        hookWindowShown(serviceClass)
        if (forceAllPages) hookLegacyPackageSpoof(serviceClass)
    }

    /** 开关一：每次键盘显示时把玻璃挂到两块真实区域的前景上。 */
    private fun hookWindowShown(serviceClass: Class<*>) {
        val windowShown = findMethodExactIfExists(serviceClass, "onWindowShown", *arrayOf<Class<*>>())
        if (windowShown == null) {
            log("onWindowShown not found, skip")
            return
        }
        xposed().hook(windowShown).intercept { chain ->
            val result = chain.proceed()
            runCatching { decorate(chain.thisObject) }
                .onFailure { log("decorate failed: ${it.message}") }
            result
        }
        log("hooked onWindowShown (glass)")
    }

    /** 开关二：旧版那套"包名伪装成全局搜索 + 补白名单"。 */
    private fun hookLegacyPackageSpoof(serviceClass: Class<*>) {
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

    private fun decorate(service: Any?) {
        if (service !is InputMethodService || decorated) return
        val decor = service.window?.window?.decorView as? ViewGroup ?: return
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return

        // 键盘本体：inputArea 里承载内容的那一层。注意它可能是**整屏**的
        // （输入法窗口本身就是整屏、键盘只占下半部分），所以绘制时会夹到下面的
        // 可见区范围里 —— 之前没夹，白纱直接盖住了除状态栏以外的整个屏幕。
        val inputArea = findAreaById(content, "inputArea")
        val body = (inputArea?.takeIf { it.childCount > 0 }?.getChildAt(0)) ?: inputArea
        // 剪贴板/快捷键条：它本来就是一块纯色背景（ColorDrawable），直接换掉最准
        val bottom = findByIdName(content, "input_bottom_view") ?: findAreaById(content, "miui_bottom_area")
        // 可见区 = 从 inputArea 顶到（底部条或键盘本体）底
        val regionTop = inputArea ?: body
        val regionBottom = bottom ?: body

        if (body == null || regionTop == null || regionBottom == null) {
            log("keyboard body or region not found")
            return
        }
        body.foreground = GlassDrawable(body, regionTop, regionBottom)
        bottom?.let { bar ->
            bar.background = GlassDrawable(bar, regionTop, regionBottom)
        }
        decorated = true
        log("glass applied: body=${body.javaClass.simpleName} bar=${bottom?.javaClass?.simpleName} " +
            "regionTop=${regionTop.javaClass.simpleName} regionBottom=${regionBottom.javaClass.simpleName}")
    }

    /**
     * 旧版那段 AGSL 的绘制层。
     *
     * 两件事必须同时成立：
     *  1. **夹范围**：目标视图可能是整屏的（输入法窗口整屏、键盘只占下半），
     *     所以只画 [regionTop 的顶, regionBottom 的底] 这一段，绝不铺满全屏。
     *  2. **共用一条渐变**：两块区域按"拼起来的整体"算 uHeight，各自只画自己那段，
     *     否则接缝处会从 0.18 跳回 0.03。
     */
    private inner class GlassDrawable(
        private val area: View,
        private val regionTop: View,
        private val regionBottom: View
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shader: RuntimeShader? = null

        override fun draw(canvas: Canvas) {
            val w = bounds.width()
            val h = bounds.height()
            if (w <= 0 || h <= 0 || area.height <= 0) return
            runCatching {
                val s = shader ?: RuntimeShader(GLASS_AGSL).also {
                    shader = it
                    paint.shader = it
                }

                // 键盘可见区（窗口坐标）
                val topLoc = IntArray(2).also(regionTop::getLocationInWindow)
                val bottomLoc = IntArray(2).also(regionBottom::getLocationInWindow)
                val regionTopY = topLoc[1]
                val regionBottomY = bottomLoc[1] + regionBottom.height
                val total = (regionBottomY - regionTopY).coerceAtLeast(1)

                // 自己在这一区里的位置
                val selfLoc = IntArray(2).also(area::getLocationInWindow)
                val offset = selfLoc[1] - regionTopY
                // 夹到可见区：视图比可见区大时（整屏宿主）只画这一段
                val drawTop = maxOf(0, -offset)
                val drawBottom = minOf(h, total - offset)
                if (drawBottom <= drawTop) return

                if (GEOMETRY_LOG && !geometryLogged) {
                    geometryLogged = true
                    log(
                        "geometry: area=${area.javaClass.simpleName} top=${selfLoc[1]} h=$h " +
                            "regionTop=${regionTop.javaClass.simpleName}@$regionTopY " +
                            "regionBottom=${regionBottom.javaClass.simpleName}@$regionBottomY " +
                            "total=$total offset=$offset draw=[$drawTop,$drawBottom] " +
                            "screen=${area.resources.displayMetrics.heightPixels}"
                    )
                }

                setRamp(s, TOP_ALPHA, BOTTOM_ALPHA)
                s.setFloatUniform("uHeight", total.toFloat())
                val dark = (area.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                if (dark) {
                    s.setFloatUniform("uBaseColor", 0.010f, 0.010f, 0.012f, 1f)
                } else {
                    s.setFloatUniform("uBaseColor", 1f, 1f, 1f, 1f)
                }

                // 平移后 fragCoord.y 就是"相对可见区顶部"的位置，渐变自然衔接
                canvas.save()
                canvas.translate(0f, -offset.toFloat())
                canvas.drawRect(
                    0f,
                    (offset + drawTop).toFloat(),
                    w.toFloat(),
                    (offset + drawBottom).toFloat(),
                    paint
                )
                canvas.restore()
            }.onFailure { logDrawFailure(it) }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** 16 段端点铺满 0..1：uPositions0..3 各 4 个，uPosition16 收尾。 */
    private fun setRamp(s: RuntimeShader, topAlpha: Float, bottomAlpha: Float) {
        val positions = FloatArray(17) { it / 16f }
        s.setFloatUniform("uPositions0", positions[0], positions[1], positions[2], positions[3])
        s.setFloatUniform("uPositions1", positions[4], positions[5], positions[6], positions[7])
        s.setFloatUniform("uPositions2", positions[8], positions[9], positions[10], positions[11])
        s.setFloatUniform("uPositions3", positions[12], positions[13], positions[14], positions[15])
        s.setFloatUniform("uPosition16", positions[16])

        val alphas = FloatArray(17) { i -> topAlpha + (bottomAlpha - topAlpha) * (i / 16f) }
        s.setFloatUniform("uAlphas0", alphas[0], alphas[1], alphas[2], alphas[3])
        s.setFloatUniform("uAlphas1", alphas[4], alphas[5], alphas[6], alphas[7])
        s.setFloatUniform("uAlphas2", alphas[8], alphas[9], alphas[10], alphas[11])
        s.setFloatUniform("uAlphas3", alphas[12], alphas[13], alphas[14], alphas[15])
        s.setFloatUniform("uAlpha16", alphas[16])
    }

    private fun logDrawFailure(t: Throwable) {
        if (drawWarned) return
        drawWarned = true
        log("draw failed, glass skipped: ${t.message}")
    }

    /** 把搜索包补进 helper 的白名单/版本表；**不动材质支持位**。 */
    private fun prepareWhitelist(service: Any?) {
        val helper = helperOf(service) ?: return
        val helperClass = helper.javaClass

        helperClass.declaredFields.firstOrNull { it.type == String::class.java }?.let { field ->
            runCatching {
                field.isAccessible = true
                field.set(helper, QUICK_SEARCH_PACKAGE)
            }
        }

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

    /** 通过稳定 getter 拿 HyperMaterial helper（返回类型就是 helper 类）。 */
    private fun helperOf(service: Any?): Any? {
        if (service == null) return null
        val getter = service.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name.startsWith(HYPER_MATERIAL_HELPER_GETTER_PREFIX)
        } ?: return null
        return runCatching { getter.invoke(service) }.getOrNull()
    }

    /** 按资源 id 名找区域（框架的 inputArea / MIUI 的 miui_bottom_area 都这样拿）。 */
    private fun findAreaById(view: View, name: String): ViewGroup? {
        if (view is ViewGroup) {
            if (view.id != View.NO_ID) {
                val entry = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
                if (entry == name) return view
            }
            for (i in 0 until view.childCount) {
                findAreaById(view.getChildAt(i), name)?.let { return it }
            }
        }
        return null
    }

    /** 按资源 id 名找任意视图（不要求是 ViewGroup，比如 input_bottom_view）。 */
    private fun findByIdName(view: View, name: String): View? {
        if (view.id != View.NO_ID) {
            val entry = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            if (entry == name) return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findByIdName(view.getChildAt(i), name)?.let { return it }
            }
        }
        return null
    }

    private fun log(message: String) {
        android.util.Log.w("XiaoAiGlass", message)
        runCatching { XposedLog.w(TAG, message) }
    }

    private companion object {
        const val IME_SERVICE_CLASS = "com.mi.ime.MiInputMethodService"
        const val QUICK_SEARCH_PACKAGE = "com.android.quicksearchbox"
        const val HYPER_MATERIAL_HELPER_GETTER_PREFIX = "getHyperMaterialHelper"

        /** 0.2.790 实测的包版本表字段名（声明类型是 Object）。 */
        val PACKAGE_VERSIONS_FIELD_NAMES = setOf("u")
        const val SUPPORTED_MATERIAL_VERSION = 2

        /** 上淡下浓；先用保守值确认可见与可读，再按观感调。 */
        const val TOP_ALPHA = 0.03f
        const val BOTTOM_ALPHA = 0.18f

        /** 打印一次实际几何（窗口/屏幕坐标），用来精确对齐可见区，稳定后关。 */
        const val GEOMETRY_LOG = true

        /** 旧版 0.2.343 里的着色器原文（未改动）。 */
        val GLASS_AGSL = """
            uniform half4 uPositions0;
            uniform half4 uPositions1;
            uniform half4 uPositions2;
            uniform half4 uPositions3;
            uniform half  uPosition16;
            uniform half4 uAlphas0;
            uniform half4 uAlphas1;
            uniform half4 uAlphas2;
            uniform half4 uAlphas3;
            uniform half  uAlpha16;
            uniform half4 uBaseColor;
            uniform half  uHeight;

            half lerpSeg(half t, half p0, half p1, half a0, half a1) {
                if (t >= p0 && t <= p1) {
                    half u = (t - p0) / max(p1 - p0, 1e-5);
                    return mix(a0, a1, u);
                }
                return -1.0;
            }

            half lerpAlpha(half t) {
                if (t <= uPositions0.x) return uAlphas0.x;
                if (t >= uPosition16)   return uAlpha16;
                half r;
                r = lerpSeg(t, uPositions0.x, uPositions0.y, uAlphas0.x, uAlphas0.y); if (r >= 0.0) return r;
                r = lerpSeg(t, uPositions0.y, uPositions0.z, uAlphas0.y, uAlphas0.z); if (r >= 0.0) return r;
                r = lerpSeg(t, uPositions0.z, uPositions0.w, uAlphas0.z, uAlphas0.w); if (r >= 0.0) return r;
                r = lerpSeg(t, uPositions0.w, uPositions1.x, uAlphas0.w, uAlphas1.x); if (r >= 0.0) return r;
                r = lerpSeg(t, uPositions1.x, uPositions1.y, uAlphas1.x, uAlphas1.y); if (r >= 0.0) return r;
                r = lerpSeg(t, uPositions1.y, uPositions1.z, uAlphas1.y, uAlphas1.z); if (r >= 0.0) return r;
                r = lerpSeg(t, uPositions1.z, uPositions1.w, uAlphas1.z, uAlphas1.w); if (r >= 0.0) return r;
                r = lerpSeg(t, uPositions1.w, uPositions2.x, uAlphas1.w, uAlphas2.x); if (r >= 0.0) return r;
                r = lerpSeg(t, uPositions2.x, uPositions2.y, uAlphas2.x, uAlphas2.y); if (r >= 0.0) return r;
                r = lerpSeg(t, uPositions2.y, uPositions2.z, uAlphas2.y, uAlphas2.z); if (r >= 0.0) return r;
                r = lerpSeg(t, uPositions2.z, uPositions2.w, uAlphas2.z, uAlphas2.w); if (r >= 0.0) return r;
                r = lerpSeg(t, uPositions2.w, uPositions3.x, uAlphas2.w, uAlphas3.x); if (r >= 0.0) return r;
                r = lerpSeg(t, uPositions3.x, uPositions3.y, uAlphas3.x, uAlphas3.y); if (r >= 0.0) return r;
                r = lerpSeg(t, uPositions3.y, uPositions3.z, uAlphas3.y, uAlphas3.z); if (r >= 0.0) return r;
                r = lerpSeg(t, uPositions3.z, uPositions3.w, uAlphas3.z, uAlphas3.w); if (r >= 0.0) return r;
                r = lerpSeg(t, uPositions3.w, uPosition16,    uAlphas3.w, uAlpha16);    if (r >= 0.0) return r;
                return uAlpha16;
            }

            half bayer4(half2 fc) {
                half x = mod(fc.x, 4.0);
                half y = mod(fc.y, 4.0);
                half v;
                if (y < 1.0) {
                    if      (x < 1.0) v =  0.0;
                    else if (x < 2.0) v =  8.0;
                    else if (x < 3.0) v =  2.0;
                    else              v = 10.0;
                } else if (y < 2.0) {
                    if      (x < 1.0) v = 12.0;
                    else if (x < 2.0) v =  4.0;
                    else if (x < 3.0) v = 14.0;
                    else              v =  6.0;
                } else if (y < 3.0) {
                    if      (x < 1.0) v =  3.0;
                    else if (x < 2.0) v = 11.0;
                    else if (x < 3.0) v =  1.0;
                    else              v =  9.0;
                } else {
                    if      (x < 1.0) v = 15.0;
                    else if (x < 2.0) v =  7.0;
                    else if (x < 3.0) v = 13.0;
                    else              v =  5.0;
                }
                return v / 16.0 - 0.5;
            }

            half4 main(float2 fragCoord) {
                half t = clamp(half(fragCoord.y) / uHeight, 0.0, 1.0);
                half af = lerpAlpha(t);
                half dither = bayer4(half2(fragCoord)) / 255.0;
                half outA = clamp(uBaseColor.a * af + dither, 0.0, 1.0);
                return half4(uBaseColor.rgb * outA, outA);
            }
        """.trimIndent()
    }
}
