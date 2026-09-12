/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Adapted from HyperChanger (https://github.com/ColdP/HyperChanger),
 * app/src/main/java/btm/m/liquidglass/hook/GlassNavigation.kt,
 * Copyright 2026 btm_m, licensed under Apache-2.0.
 *
 * This file is part of HyperCeiler (AGPL-3.0). Apache-2.0 is compatible with
 * AGPL-3.0; the original copyright notice above is kept as required.
 */
package com.sevtinge.hyperceiler.home.widget.compose

import android.view.View
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * 把**原生 View 树**接成 miuix-blur 能吃的 backdrop。
 *
 * 这是整件事的关键：miuix-blur 的 backdrop 只能录到 Compose 自己的内容，
 * 而 HyperCeiler 的界面是 View（ViewPager / RecyclerView / MIUIX 控件），
 * 所以直接用它永远采不到东西。这里在 backdrop 的绘制流程里把 sourceView
 * 原样画进同一个 canvas（按窗口坐标对齐、按 downscaleFactor 缩小以获得
 * 更省的模糊采样），后面的 blur / lens / vibrancy / highlight 就都有料了。
 */
class NativeViewBackdrop(private val sourceView: View) : Backdrop {

    private var dumpTick = 0

    private companion object {
        /** 调试：把"抓到的源视图"另存到外部缓存，用 adb pull 核对抓的是哪一页。 */
        const val DEBUG_DUMP = false

        /** 调试：把采样倍率/源视图打到 logcat（Kotlin tag 不会被 LSParanoid 混淆）。 */
        const val DEBUG_LOG = false
    }

    override val isCoordinatesDependent: Boolean = true

    /**
     * 解出"当前显示的那一页"。
     *
     * 本项目用的是 fan.viewpager.widget.ViewPager（v1）：页面是它的直接子视图、
     * 左右并排，当前页的 left 区间包含 scrollX。放在 Kotlin 侧做，是为了跟绘制
     * 用同一个对象 —— Java 侧设置源、Compose 侧绘制，跨层容易拿到不同步的旧值。
     */
    private fun resolveCurrentPage(root: View): View {
        if (root !is android.view.ViewGroup) return root
        val scrollX = root.scrollX
        val verbose = dumpTick % 240 == 0
        val info = if (verbose) StringBuilder() else null
        var firstVisible: View? = null
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (info != null) {
                info.append(" [").append(i).append(':').append(child.javaClass.simpleName)
                    .append(" left=").append(child.left)
                    .append(" tx=").append(child.translationX)
                    .append(" w=").append(child.width)
                    .append(" vis=").append(child.visibility).append(']')
            }
            if (child.width <= 0 || child.visibility != View.VISIBLE) continue
            if (child.left <= scrollX && child.left + child.width > scrollX) return child
            if (firstVisible == null) firstVisible = child
        }
        if (info != null) {
            android.util.Log.w(
                "NativeViewBackdrop",
                "resolve root=" + root.javaClass.simpleName + " scrollX=" + scrollX +
                    " children=" + root.childCount + info
            )
        }
        return firstVisible ?: root
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int
    ) {
        val surfacePosition = coordinates?.positionInWindow() ?: return
        if (!sourceView.isAttachedToWindow || sourceView.width <= 0 || sourceView.height <= 0) return

        if (DEBUG_DUMP && dumpTick++ % 20 == 0) {
            runCatching {
                val scale = 0.4f
                val bmp = android.graphics.Bitmap.createBitmap(
                    (sourceView.width * scale).toInt().coerceAtLeast(1),
                    (sourceView.height * scale).toInt().coerceAtLeast(1),
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val c = android.graphics.Canvas(bmp)
                c.scale(scale, scale)
                sourceView.draw(c)
                val dir = sourceView.context.getExternalCacheDir() ?: return@runCatching
                java.io.FileOutputStream(java.io.File(dir, "backdrop.png")).use {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                android.util.Log.w(
                    "NativeViewBackdrop",
                    "dumped source=" + sourceView.javaClass.simpleName +
                        " size=" + sourceView.width + "x" + sourceView.height
                )
            }
        }

        // 每次都从源视图里解出"当前显示的那一页"，避免采到别的页
        val target = resolveCurrentPage(sourceView)

        if (DEBUG_LOG && dumpTick++ % 60 == 0) {
            // Kotlin 侧日志不受 LSParanoid 混淆，可直接在 logcat 看
            android.util.Log.w(
                "NativeViewBackdrop",
                "factor=" + downscaleFactor +
                    " source=" + sourceView.javaClass.simpleName +
                    " target=" + target.javaClass.simpleName
            )
        }

        val sourcePosition = IntArray(2).also(target::getLocationInWindow)
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        try {
            val scale = 1f / downscaleFactor.coerceAtLeast(1)
            canvas.scale(scale, scale)
            canvas.translate(
                sourcePosition[0] - surfacePosition.x,
                sourcePosition[1] - surfacePosition.y
            )
            target.draw(canvas)
        } finally {
            canvas.restore()
        }
    }
}
