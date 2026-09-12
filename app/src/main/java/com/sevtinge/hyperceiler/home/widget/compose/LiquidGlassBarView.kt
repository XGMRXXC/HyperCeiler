/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.home.widget.compose

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.sevtinge.hyperceiler.home.widget.OnSwitchChangeListener
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 液态玻璃底栏的 Compose 宿主。
 *
 * 内部直接用 KernelSU 的 [FloatingBottomBar]（已移植到本包），backdrop 用
 * [NativeViewBackdrop] 接的是**原生 View**（[setBackdropSource] 传进来的那一层，
 * 通常是 ViewPager），这样 blur / lens / 色散 / 重力高光才采得到东西。
 *
 * 与 View 版底栏（SwitchView）保持同一套对外接口：inflateMenu / setSelectedTab /
 * setOnSwitchChangeListener，这样 SwitchManager 可以无差别切换。
 */
class LiquidGlassBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private data class BarTab(val id: Int, val icon: Drawable?, val title: CharSequence)

    private var listener: OnSwitchChangeListener? = null
    private var selectedPosition = 0

    private val tabsState = mutableStateOf<List<BarTab>>(emptyList())
    private val sourceState = mutableStateOf<View?>(null)
    private val selectedState = mutableIntStateOf(0)
    /** 每次底层内容重绘都 +1，用来驱动 backdrop 重新录制。 */
    private val backdropVersion = mutableIntStateOf(0)

    private var preDrawSource: View? = null
    private val composeOwner = ComposeViewOwner()
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (preDrawSource?.isDirty == true) {
            backdropVersion.intValue++
        }
        true
    }

    init {
        // ComposeView 在有些版本里是 final，不能继承，所以外面套一层 FrameLayout。
        // MIUIX 的 AppCompatActivity 不是 ComponentActivity，视图树上没有
        // ViewTreeLifecycleOwner / SavedStateRegistryOwner，Compose 会直接抛
        // "ViewTreeLifecycleOwner not found"，所以这里自己装一对最小的 owner。
        val composeView = ComposeView(context)
        composeView.setViewTreeLifecycleOwner(composeOwner)
        composeView.setViewTreeSavedStateRegistryOwner(composeOwner)
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        composeView.setContent {
            val tabs = tabsState.value
            val source = sourceState.value
            if (tabs.isEmpty() || source == null) return@setContent

            // 订阅版本号：底层内容动一次，这张玻璃就跟着重画一次
            backdropVersion.intValue
            val backdrop = remember(source) { NativeViewBackdrop(source) }
            val controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.System) }
            val index = selectedState.intValue

            MiuixTheme(controller = controller) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .navigationBarsPadding()
                        // 上下留白不能省：透镜折射/高光会画到药丸轮廓之外，
                        // 高度 wrap_content 时会被裁掉（长按时上方那点切割就是它）
                        .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 30.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    FloatingBottomBar(
                        // 每个 tab 64dp：外层是 IntrinsicSize.Min 且 tab 用 weight，
                        // 不给宽度的话药丸会按 26dp 图标宽算，看起来短一截
                        modifier = Modifier.width((tabs.size * 64 + 8).dp),
                        selectedIndex = index.coerceIn(0, tabs.lastIndex),
                        onSelected = { select(it, true) },
                        backdrop = backdrop,
                        tabsCount = tabs.size
                    ) { activate ->
                        tabs.forEachIndexed { i, tab ->
                            FloatingBottomBarItem(
                                selected = i == index,
                                onClick = { activate(i) }
                            ) {
                                tab.icon?.let { DrawableIcon(it, 26.dp) }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 和 SwitchView 一样，用 PopupMenu 解析菜单资源，拿图标和标题。 */
    fun inflateMenu(menuRes: Int) {
        val popup = PopupMenu(context, null)
        popup.inflate(menuRes)
        val menu = popup.menu
        val tabs = ArrayList<BarTab>(menu.size())
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            tabs.add(BarTab(item.itemId, item.icon, item.title ?: ""))
        }
        tabsState.value = tabs
    }

    fun setSelectedTab(position: Int, notify: Boolean) {
        if (tabsState.value.isEmpty()) return
        if (position < 0 || position >= tabsState.value.size) return
        selectedPosition = position
        selectedState.intValue = position
        if (notify) {
            listener?.onSwitchChange(position, tabsState.value[position].id)
        }
    }

    fun setOnSwitchChangeListener(l: OnSwitchChangeListener?) {
        listener = l
    }

    /** 玻璃要采样的那一层原生 View。 */
    fun setBackdropSource(view: View?) {
        sourceState.value = view
        preDrawSource?.let { old ->
            if (old.viewTreeObserver.isAlive) {
                old.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            }
        }
        preDrawSource = view
        view?.viewTreeObserver?.addOnPreDrawListener(preDrawListener)
    }

    private fun select(index: Int, notify: Boolean) {
        if (index == selectedState.intValue && notify) return
        setSelectedTab(index, notify)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Compose 找 ViewTreeLifecycleOwner 时是从上往下/从父容器找的，只设在
        // 内部 ComposeView 上不够（真机上会报 ...id/container not found），
        // 所以把这一层的父容器和整棵树的根都补上。
        setViewTreeLifecycleOwner(composeOwner)
        setViewTreeSavedStateRegistryOwner(composeOwner)
        (rootView as? View)?.let { root ->
            root.setViewTreeLifecycleOwner(composeOwner)
            root.setViewTreeSavedStateRegistryOwner(composeOwner)
        }
    }

    override fun onDetachedFromWindow() {
        preDrawSource?.let { old ->
            if (old.viewTreeObserver.isAlive) {
                old.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            }
        }
        preDrawSource = null
        super.onDetachedFromWindow()
    }
}

/** 把原生 Drawable 画进 Compose，避免为此引入图片加载库。 */
@Composable
private fun DrawableIcon(drawable: Drawable, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val checkpoint = native.save()
            drawable.setBounds(0, 0, this.size.width.toInt(), this.size.height.toInt())
            drawable.draw(native)
            native.restoreToCount(checkpoint)
        }
    }
}

/** 最小可用的 Compose 宿主 owner（生命周期直接挂到 RESUMED）。 */
private class ComposeViewOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }
}
