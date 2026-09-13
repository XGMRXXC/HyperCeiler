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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook

/**
 * 超级小爱输入法：所有页面使用"高级材质"。
 *
 * 来龙去脉（都对着 0.2.343 与 0.2.790 两版 dex 核对过）：
 *
 *  - 旧版（0.2.343）**自己画**这层材质：helper `bb.s` 里有个 `RuntimeShader` 字段，
 *    着色器源码明文躺在 dex 里（已抽出为 glass-shader-full.agsl）。它是一层
 *    **纵向 alpha 渐变 + 4x4 Bayer 抖动**的半透明表面，**不采样背后内容**、
 *    也**不向系统申请权限**，所以旧版能强开。
 *  - 新版（0.2.790）改成调用
 *    `android.inputmethodservice.InputMethodServiceInjector#setHyperMaterialEnabled`，
 *    而这个方法在 HyperOS 4.0 的框架里**不存在**（miui-framework.jar 有类、没这个方法），
 *    调用失败后输入法回退成深色不透明键盘（"大黑块"）。
 *  - 上游 HyperChanger 那套（伪造 helper 字段 + 包名）因此在这个版本上失去意义：
 *    决定权已经不在那些字段上了。
 *
 * 这里沿用**旧版的做法**：把同一段 AGSL 画在键盘窗口里，完全不碰系统接口。
 * 唯一与旧版不同的地方是渐变端点用我们自己的默认值（旧版是运行期算的，dex 里没有常量）。
 */
class XiaoAiSearchMaterial : BaseHook() {

    override fun init() {
        val serviceClass = findClassIfExists(IME_SERVICE_CLASS)
        if (serviceClass == null) {
            log("$IME_SERVICE_CLASS not found, skip")
            return
        }
        val windowShown = findMethodExactIfExists(serviceClass, "onWindowShown", *arrayOf<Class<*>>())
        if (windowShown == null) {
            log("onWindowShown not found, skip")
            return
        }
        xposed().hook(windowShown).intercept { chain ->
            val result = chain.proceed()
            // 每次显示都对齐一次（尺寸可能变化），已挂上则跳过
            runCatching { attachGlassLayer(chain.thisObject) }
                .onFailure { log("attach failed: ${it.message}") }
            result
        }
        log("hooked onWindowShown")
    }

    /** 把着色器层插到键盘内容里：键盘背景之上、按键之下。 */
    private fun attachGlassLayer(service: Any?) {
        if (service !is InputMethodService) return
        val decor = service.window?.window?.decorView as? ViewGroup ?: return
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: decor
        if (content.findViewWithTag<View>(GLASS_TAG) != null) return

        if (DEBUG_TREE) dumpTree(content, 0)

        val layer = GlassLayerView(service)
        layer.tag = GLASS_TAG
        // index 0 在最底层，会被键盘自己的不透明背景挡住（实测：原生没材质的地方完全看不到）。
        // 往上挪一层，压在背景之上、按键之下。
        val index = if (content.childCount >= 2) 1 else 0
        content.addView(
            layer, index,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        log("glass layer attached at index $index of ${content.javaClass.simpleName}, children=${content.childCount}")
    }

    /** 打印键盘视图树（限深度），用来判断该插到哪一层。 */
    private fun dumpTree(view: View, depth: Int) {
        if (depth > 4) return
        val indent = "  ".repeat(depth)
        val id = try {
            if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else "-"
        } catch (e: Exception) {
            "?"
        }
        log("$indent${view.javaClass.simpleName} id=$id children=${(view as? ViewGroup)?.childCount ?: 0} h=${view.height}")
        (view as? ViewGroup)?.let { group ->
            for (i in 0 until group.childCount) dumpTree(group.getChildAt(i), depth + 1)
        }
    }

    private fun log(message: String) {
        android.util.Log.w("XiaoAiGlass", message)
        runCatching { XposedLog.w(TAG, message) }
    }

    /**
     * 用旧版那段 AGSL 画一层"上淡下浓"的玻璃表面。
     * 只画不接收触摸，避免影响按键。
     */
    private class GlassLayerView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shader: RuntimeShader? = null
        private val bounds = RectF()
        private var warned = false

        init {
            shader = runCatching { RuntimeShader(GLASS_AGSL) }
                .onFailure { android.util.Log.w("XiaoAiGlass", "shader compile failed: ${it.message}") }
                .getOrNull()
            paint.shader = shader
            isClickable = false
            isFocusable = false
        }

        override fun onDraw(canvas: Canvas) {
            val s = shader ?: return
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            bounds.set(0f, 0f, w, h)

            // 兜底：绘制里出任何问题只让这一层不画，绝不再把输入法带崩。
            // 之前两次崩溃都是这里 —— setColorUniform 用在了非 layout(color) 的 uniform 上。
            runCatching {
                setRamp(s, TOP_ALPHA, BOTTOM_ALPHA)
                s.setFloatUniform("uHeight", h)
                setBaseColor(s)
                canvas.drawRect(bounds, paint)
            }.onFailure {
                if (!warned) {
                    warned = true
                    android.util.Log.w("XiaoAiGlass", "draw failed, layer skipped: ${it.message}")
                }
            }
        }

        /**
         * uBaseColor 是**普通 half4**，必须用 setFloatUniform。
         * setColorUniform 只适用于 `layout(color)` 声明的 uniform，用错会抛
         * "attempting to set a color uniform using the non-color specific APIs"
         * —— 输入法在绘制里崩溃，键盘直接弹不出来（已复现两次）。
         * 取值用**线性**分量，AGSL 工作在线性空间。
         */
        private fun setBaseColor(s: RuntimeShader) {
            val dark = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (dark) {
                s.setFloatUniform("uBaseColor", 0.010f, 0.010f, 0.012f, 1f)
            } else {
                s.setFloatUniform("uBaseColor", 1f, 1f, 1f, 1f)
            }
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
    }

    private companion object {
        const val IME_SERVICE_CLASS = "com.mi.ime.MiInputMethodService"
        const val GLASS_TAG = "hyperceiler.xiaoai.glass"

        /**
         * 上淡下浓。上一版 0.30→0.92 是我瞎填的，实测把键盘冲成一片白（按键都看不清），
         * 这里按"能看出渐变、但不影响按键可读性"重新取小值。
         */
        const val TOP_ALPHA = 0.03f
        const val BOTTOM_ALPHA = 0.18f

        /** 打印键盘视图树（定位插入层级用，稳定后可关）。 */
        const val DEBUG_TREE = false

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
