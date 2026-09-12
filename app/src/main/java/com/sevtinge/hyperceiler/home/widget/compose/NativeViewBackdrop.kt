/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Recording + redraw strategy adapted from HyperChanger
 * (https://github.com/ColdP/HyperChanger),
 * app/src/main/java/btm/m/liquidglass/hook/NativeViewBackdrop.kt and
 * app/src/main/java/btm/m/liquidglass/hook/GlassNavigation.kt,
 * Copyright 2026 btm_m, licensed under Apache-2.0.
 *
 * This file is part of HyperCeiler (AGPL-3.0). Apache-2.0 is compatible with
 * AGPL-3.0; the original copyright notices above are kept as required.
 */
package com.sevtinge.hyperceiler.home.widget.compose

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.blur.Backdrop
import java.util.IdentityHashMap

/**
 * 把**原生 View 树**接成 miuix-blur 能吃的 backdrop。
 *
 * miuix-blur 的 backdrop 只能录到 Compose 自己的内容，而 HyperCeiler 的界面是
 * View（ViewPager / RecyclerView / MIUIX 控件），所以要把源 View 录进一个
 * GraphicsLayer，再在 backdrop 的绘制流程里把这一层按窗口坐标对齐画出来。
 *
 * 两个必须注意的点（前者导致过画面残留，后者是 HyperChanger 踩过的坑）：
 *  - 不能每次都直接 `sourceView.draw(canvas)`：源视图正处在自己的绘制过程中，
 *    硬件加速的内容会拿到不完整的旧帧，看起来就是抹不掉的残影。改成先
 *    [record] 到一个 layer，只在版本号变化时重新录。
 *  - 直接 native 绘制会跳过一部分内容（原生 TextView 的文字、ComposeView 的
 *    文字走的是 RenderNode），所以录完之后要把这些单独重画一遍。
 */
class NativeViewBackdrop(
    private val graphicsLayer: GraphicsLayer,
    private val sourceView: View,
    private val density: Density,
    private val layoutDirection: LayoutDirection
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    private var recordedVersion = Int.MIN_VALUE
    private val composeSnapshots = IdentityHashMap<View, Bitmap>()

    /** 版本号变化时重新录制；由调用方在 backdrop 绘制里带上当前版本。 */
    fun record(version: Int) {
        if (version == recordedVersion) return
        val width = sourceView.width
        val height = sourceView.height
        if (width <= 0 || height <= 0) return
        recordedVersion = version

        graphicsLayer.record(density, layoutDirection, IntSize(width, height)) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val checkpoint = native.save()
                native.translate(-sourceView.scrollX.toFloat(), -sourceView.scrollY.toFloat())
                sourceView.draw(native)
                native.restoreToCount(checkpoint)
                redrawTextViews(native)
                redrawComposeViews(native)
            }
        }
    }

    private fun redrawTextViews(canvas: android.graphics.Canvas) {
        val sourceLocation = IntArray(2).also(sourceView::getLocationInWindow)
        val pending = ArrayDeque<View>()
        if (sourceView is ViewGroup) {
            for (index in 0 until sourceView.childCount) pending.addLast(sourceView.getChildAt(index))
        }
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (!view.isShown || view.alpha <= 0f || view.width <= 0 || view.height <= 0) continue
            if (view is TextView) {
                val location = IntArray(2).also(view::getLocationInWindow)
                val checkpoint = canvas.save()
                canvas.translate(
                    (location[0] - sourceLocation[0]).toFloat(),
                    (location[1] - sourceLocation[1]).toFloat()
                )
                canvas.clipRect(0, 0, view.width, view.height)
                view.draw(canvas)
                canvas.restoreToCount(checkpoint)
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) pending.addLast(view.getChildAt(index))
            }
        }
    }

    private fun redrawComposeViews(canvas: android.graphics.Canvas) {
        val sourceLocation = IntArray(2).also(sourceView::getLocationInWindow)
        val pending = ArrayDeque<View>()
        if (sourceView is ViewGroup) {
            for (index in 0 until sourceView.childCount) pending.addLast(sourceView.getChildAt(index))
        }
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (!view.isShown || view.alpha <= 0f || view.width <= 0 || view.height <= 0) continue
            if (view is ComposeView) {
                val bitmap = composeSnapshots[view].let { cached ->
                    if (cached == null || cached.width != view.width || cached.height != view.height) {
                        cached?.recycle()
                        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                            composeSnapshots[view] = it
                        }
                    } else {
                        cached
                    }
                }
                bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                view.draw(android.graphics.Canvas(bitmap))
                val location = IntArray(2).also(view::getLocationInWindow)
                val checkpoint = canvas.save()
                canvas.translate(
                    (location[0] - sourceLocation[0]).toFloat(),
                    (location[1] - sourceLocation[1]).toFloat()
                )
                canvas.clipRect(0, 0, view.width, view.height)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                canvas.restoreToCount(checkpoint)
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) pending.addLast(view.getChildAt(index))
            }
        }
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int
    ) {
        val consumer = coordinates ?: return
        val consumerInWindow = consumer.positionInWindow()
        val sourceInWindow = IntArray(2).also(sourceView::getLocationInWindow)
        val offsetX = consumerInWindow.x - sourceInWindow[0]
        val offsetY = consumerInWindow.y - sourceInWindow[1]

        val scale = 1f / downscaleFactor.coerceAtLeast(1)
        val canvas = drawContext.canvas
        canvas.save()
        try {
            canvas.scale(scale, scale)
            translate(offsetX, offsetY) {
                drawLayer(graphicsLayer)
            }
        } finally {
            canvas.restore()
        }
    }
}
