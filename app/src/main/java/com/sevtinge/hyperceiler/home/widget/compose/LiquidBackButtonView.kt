/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * OS4 风格"下滑后独立返回键"。
 * 参考 HyperChanger (https://github.com/ColdP/HyperChanger) 二级页面顶部的处理，
 * Copyright 2026 btm_m, licensed under Apache-2.0。
 *
 * 与顶栏模糊带的分工：
 *  - LiquidTopBarView 负责顶栏那条背景模糊（整条，不吃触摸）
 *  - 这个视图负责独立的返回圆钮（只占按钮那一小块，可点击）
 */
package com.sevtinge.hyperceiler.home.widget.compose

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 悬浮玻璃返回钮：页面下滑后淡入，点击等同于返回。
 *
 * 只占 [BUTTON_SIZE_DP] 那一小块；其余区域 [dispatchTouchEvent] 直接放行，
 * 所以不会挡住页面的滑动。
 */
class LiquidBackButtonView(context: Context) : FrameLayout(context) {

    private val composeOwner = BackButtonOwner()
    private val sourceState = mutableStateOf<View?>(null)
    private val visibleState = mutableStateOf(false)

    /** 点击回调，由 Activity 接到返回逻辑上。 */
    var onBack: (() -> Unit)? = null

    init {
        isClickable = false
        val composeView = ComposeView(context)
        composeView.setViewTreeLifecycleOwner(composeOwner)
        composeView.setViewTreeSavedStateRegistryOwner(composeOwner)
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        composeView.setContent {
            val source = sourceState.value
            val visible = visibleState.value
            val backdrop = remember(source) {
                source?.let { NativeViewBackdrop(it) }
            }
            MiuixTheme(controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.System) }) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (backdrop != null) {
                                Modifier.drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { CircleShape },
                                    effects = {
                                        vibrancy()
                                        blur(2.dp.toPx(), 2.dp.toPx())
                                        lens(
                                            refractionHeight = 8.dp.toPx(),
                                            refractionAmount = 12.dp.toPx(),
                                            chromaticAberration = 0.4f,
                                        )
                                    },
                                    highlight = { backButtonHighlight },
                                    onDrawSurface = {
                                        drawCircle(
                                            color = Color(0xFF1C1C1E).copy(alpha = 0.55f),
                                            alpha = if (visible) 1f else 0f,
                                        )
                                    },
                                )
                            } else {
                                Modifier
                            }
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            val listener = onBack
                            if (listener != null) {
                                listener.invoke()
                            } else {
                                (context as? android.app.Activity)?.onBackPressed()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // 返回箭头（←）自己画，避免引入图标依赖
                    Canvas(Modifier.fillMaxSize()) {
                        val color = Color.White.copy(alpha = if (visible) 0.95f else 0f)
                        val w = size.width
                        val h = size.height
                        val stroke = w * 0.075f
                        val startX = w * 0.34f
                        val endX = w * 0.66f
                        val midY = h * 0.5f
                        drawLine(
                            color = color,
                            start = Offset(startX, midY),
                            end = Offset(endX, midY),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                        val headX = w * 0.44f
                        val dy = h * 0.115f
                        drawLine(
                            color = color,
                            start = Offset(startX + stroke * 0.1f, midY),
                            end = Offset(headX, midY - dy),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = color,
                            start = Offset(startX + stroke * 0.1f, midY),
                            end = Offset(headX, midY + dy),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0f),
                            radius = w * 0.5f,
                            style = Stroke(width = 0f),
                        )
                    }
                }
            }
        }
    }

    fun setBackdropSource(view: View?) {
        sourceState.value = view
        if (view != null) composeOwner.attach()
    }

    fun setButtonVisible(visible: Boolean) {
        if (visibleState.value != visible) {
            visibleState.value = visible
        }
    }

    /** 只有按钮那一小块接收触摸，其余放行给页面。 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!visibleState.value) return false
        val inside = ev.x >= 0 && ev.x <= width && ev.y >= 0 && ev.y <= height
        return inside && super.dispatchTouchEvent(ev)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        composeOwner.attach()
    }

    override fun onDetachedFromWindow() {
        composeOwner.detach()
        super.onDetachedFromWindow()
    }

    private class BackButtonOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        private var restored = false

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun attach() {
            if (!restored) {
                restored = true
                savedStateController.performRestore(null)
            }
            if (lifecycleRegistry.currentState != Lifecycle.State.RESUMED) {
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            }
        }

        fun detach() {
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            }
        }
    }

    companion object {
        /** 按钮直径（dp），Activity 用它算布局尺寸。 */
        const val BUTTON_SIZE_DP = 40
    }
}

/** 返回钮的高光：一圈很淡的白描边。 */
private val backButtonHighlight = Highlight(
    width = 1.dp,
    alpha = 0.8f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        dualPeak = false,
    ),
)
