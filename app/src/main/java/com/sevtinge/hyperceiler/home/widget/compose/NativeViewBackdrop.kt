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

    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int
    ) {
        val surfacePosition = coordinates?.positionInWindow() ?: return
        if (!sourceView.isAttachedToWindow || sourceView.width <= 0 || sourceView.height <= 0) return

        val sourcePosition = IntArray(2).also(sourceView::getLocationInWindow)
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        try {
            val scale = 1f / downscaleFactor.coerceAtLeast(1)
            canvas.scale(scale, scale)
            canvas.translate(
                sourcePosition[0] - surfacePosition.x,
                sourcePosition[1] - surfacePosition.y
            )
            sourceView.draw(canvas)
        } finally {
            canvas.restore()
        }
    }
}
