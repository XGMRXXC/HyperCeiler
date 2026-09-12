/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * OS4 风格顶栏：顶部一条背景模糊带。滚动时页面内容从它下面经过并被模糊，
 * 对应 HyperChanger (https://github.com/ColdP/HyperChanger) 里二级页面顶部的做法，
 * Copyright 2026 btm_m, licensed under Apache-2.0。
 *
 * 这一半只做"模糊带"；返回按钮的脱离效果留给二级页面复用同一套玻璃。
 */
package com.sevtinge.hyperceiler.home.widget.compose

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 顶部玻璃模糊带。
 *
 * 放在内容层里（底栏同级的思路），因此它位于 MIUIX ActionBar 之下；
 * 设置页的 ActionBar 本来就是透明的，所以滚动时内容会先经过这条带子再被标题覆盖。
 */
class LiquidTopBarView(context: Context) : FrameLayout(context) {

    private val composeOwner = TopBarOwner()
    private val sourceState = mutableStateOf<View?>(null)
    private val visibleState = mutableStateOf(false)

    init {
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
            if (source == null) return@setContent
            val backdrop = remember(source) { NativeViewBackdrop(source) }
            MiuixTheme(controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.System) }) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { androidx.compose.foundation.shape.RoundedCornerShape(0.dp) },
                            effects = {
                                vibrancy()
                                blur(2.dp.toPx(), 2.dp.toPx())
                            },
                            onDrawSurface = {
                                // 越往下越淡，避免和内容硬切
                                drawRect(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.30f),
                                        0.72f to Color.Black.copy(alpha = 0.16f),
                                        1f to Color.Transparent,
                                    ),
                                    alpha = if (visible) 1f else 0f,
                                )
                            },
                        )
                )
            }
        }
    }

    fun setBackdropSource(view: View?) {
        sourceState.value = view
        if (view != null) composeOwner.attach()
    }

    fun setStripVisible(visible: Boolean) {
        visibleState.value = visible
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        composeOwner.attach()
        // Compose 是从父链往上找 ViewTreeLifecycleOwner 的，只设在内部 ComposeView 上不够：
        // 二级页面的 decor 上没有 owner，会直接抛 "not found from DecorView"。根视图也补上。
        setViewTreeLifecycleOwner(composeOwner)
        setViewTreeSavedStateRegistryOwner(composeOwner)
        (rootView as? View)?.let { root ->
            root.setViewTreeLifecycleOwner(composeOwner)
            root.setViewTreeSavedStateRegistryOwner(composeOwner)
        }
    }

    /** 这条带子只负责画，不参与触摸分发，否则会挡住下面页面的滑动。 */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean = false

    override fun onDetachedFromWindow() {
        composeOwner.detach()
        super.onDetachedFromWindow()
    }

    /** MIUIX 的 Activity 不提供 ViewTreeLifecycleOwner，这里自己装一对最小的。 */
    private class TopBarOwner : LifecycleOwner, SavedStateRegistryOwner {
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
}
