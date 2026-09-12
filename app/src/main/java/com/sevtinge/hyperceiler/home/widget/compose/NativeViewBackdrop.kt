/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Backdrop-for-native-Views idea taken from HyperChanger
 * (https://github.com/ColdP/HyperChanger),
 * app/src/main/java/btm/m/liquidglass/hook/GlassNavigation.kt,
 * Copyright 2026 btm_m, licensed under Apache-2.0.
 *
 * This file is part of HyperCeiler (AGPL-3.0). Apache-2.0 is compatible with
 * AGPL-3.0; the original copyright notice above is kept as required.
 */
package com.sevtinge.hyperceiler.home.widget.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.os.SystemClock
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
 * miuix-blur 的 backdrop 只能录到 Compose 自己的内容，而 HyperCeiler 的界面是
 * View（ViewPager / RecyclerView / MIUIX 控件），所以必须自己把源视图喂进去。
 *
 * 实现上试过两条路：
 *  - 直接 `sourceView.draw(nativeCanvas)`：内容是对的，但源视图正处于自己的
 *    硬件加速绘制过程中，被重画会拿到不完整的一帧，界面留下抹不掉的残影。
 *  - 录进 GraphicsLayer 再画：干净，但真机上这一层始终是空的（黑底）。
 * 现在用的是**软件画布抓快照**：一次抓一整帧，不会有重复绘制的残影，
 * 也不会空白；顺带因为软件绘制本身就不会跳过 TextView / ComposeView 的文字，
 * 不需要额外补画。
 */
class NativeViewBackdrop(private val sourceView: View) : Backdrop {

    companion object {
        /** 调试：把抓到的快照写到外部缓存，用 adb pull 出来核对。 */
        private const val DEBUG_DUMP = false
    }

    private var dumped = false
    private var loggedOnce = false

    override val isCoordinatesDependent: Boolean = true

    /** 快照降采样倍率：整屏 ARGB 太大，减半后再由模糊盖过去，肉眼无差。 */
    private val captureScale = 0.5f

    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var recordedVersion = Int.MIN_VALUE
    private var lastCaptureAt = 0L

    /** 软件抓整屏很贵，滚动时按最小间隔节流。 */
    private val minIntervalMs = 70L

    /** 版本号变化时重新抓一帧（调用方在 backdrop 绘制前带上当前版本）。 */
    fun record(version: Int) {
        if (version == recordedVersion) return
        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureAt < minIntervalMs) return
        val viewWidth = sourceView.width
        val viewHeight = sourceView.height
        if (viewWidth <= 0 || viewHeight <= 0) return
        recordedVersion = version
        lastCaptureAt = now

        val width = (viewWidth * captureScale).toInt().coerceAtLeast(1)
        val height = (viewHeight * captureScale).toInt().coerceAtLeast(1)
        if (bitmap?.width != width || bitmap?.height != height) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmapCanvas = Canvas(bitmap!!)
        }
        val canvas = bitmapCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val checkpoint = canvas.save()
        canvas.scale(captureScale, captureScale)
        canvas.translate(-sourceView.scrollX.toFloat(), -sourceView.scrollY.toFloat())
        try {
            sourceView.draw(canvas)
        } finally {
            canvas.restoreToCount(checkpoint)
        }

        if (DEBUG_DUMP && !dumped) {
            dumped = true
            runCatching {
                val dir = sourceView.context.getExternalCacheDir() ?: return@runCatching
                val out = java.io.File(dir, "backdrop.png")
                java.io.FileOutputStream(out).use { bitmap?.compress(Bitmap.CompressFormat.PNG, 100, it) }
                android.util.Log.w("NativeViewBackdrop", "dumped to ${out.absolutePath}")
            }
        }
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int
    ) {
        val snapshot = bitmap
        if (!loggedOnce) {
            loggedOnce = true
            android.util.Log.w(
                "NativeViewBackdrop",
                "drawBackdrop: snapshot=" + (snapshot != null) +
                    " size=" + (snapshot?.width ?: 0) + "x" + (snapshot?.height ?: 0) +
                    " coord=" + (coordinates != null) +
                    " attached=" + sourceView.isAttachedToWindow +
                    " factor=" + downscaleFactor
            )
        }
        if (snapshot == null) return
        val consumer = coordinates ?: return
        if (!sourceView.isAttachedToWindow) return

        val consumerInWindow = consumer.positionInWindow()
        val sourceInWindow = IntArray(2).also(sourceView::getLocationInWindow)

        val native = drawContext.canvas.nativeCanvas
        val checkpoint = native.save()
        try {
            val scale = 1f / downscaleFactor.coerceAtLeast(1)
            // 之后所有坐标都按"内容坐标"（像素）来算
            native.scale(scale, scale)
            // 关键：偏移是 src - consumer。写成 consumer - src 会把快照画到屏幕外，
            // 药丸里就只剩容器底色（看起来就是黑底）
            native.translate(
                (sourceInWindow[0] - consumerInWindow.x),
                (sourceInWindow[1] - consumerInWindow.y)
            )
            // 快照是 captureScale 倍，放大回内容坐标
            native.scale(1f / captureScale, 1f / captureScale)
            native.drawBitmap(snapshot, 0f, 0f, paint)
        } finally {
            native.restoreToCount(checkpoint)
        }
    }
}
