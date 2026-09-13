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

import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook

/**
 * 超级小爱输入法"所有页面高级材质"—— **当前不实现任何东西**（空实现，零副作用）。
 *
 * 下面这份记录来自 0.2.343 / 0.2.790 两版 dex 的逐项核对，留着给以后接手的人，
 * 免得再从零摸一遍。
 *
 * 一、功能原本是什么
 *   让输入法在任何输入框都套用"全局搜索页"那套高级材质。上游 HyperChanger 的做法是
 *   把 `editorInfo.packageName` 伪造成 com.android.quicksearchbox，再去改 helper 的字段
 *   （允许包、包版本表、强制深/浅色集合）。
 *
 * 二、为什么在新版上失效
 *   新版（0.2.790）把"启用材质"改成调用
 *   `android.inputmethodservice.InputMethodServiceInjector#setHyperMaterialEnabled`，
 *   而 HyperOS 4.0 的框架里**没有这个方法**（`/system_ext/framework/miui-framework.jar`
 *   有类、没有该方法，`framework.jar` 里连类都没有）。调用失败后输入法回退成深色不透明
 *   键盘 —— 也就是"大黑块"。上游那套字段伪造因此失去意义：决定权已经不在那些字段上。
 *
 * 三、旧版（0.2.343）是怎么办到的
 *   它**自己画**：helper `bb.s` 带一个 `RuntimeShader` 字段，着色器源码明文在 dex 里
 *   （已抽出为 glass-shader-full.agsl）。内容是一层**纵向 alpha 渐变 + 4x4 Bayer 抖动**
 *   的半透明表面，**不采样背后内容**、**不调用任何系统接口**，所以旧版能强开。
 *
 * 四、为什么最后没有采用"把着色器画进键盘窗口"
 *   试过一版：hook `MiInputMethodService.onWindowShown`，把一层自绘 View 插到键盘
 *   内容的最底下。结果是键盘**直接弹不出来** —— 往输入法窗口里插视图会干扰它自己的
 *   布局/显示流程，风险远大于收益，所以撤掉了。想做的话必须先在旧版上把它的插入时机、
 *   容器、以及它自己那层视图的层级关系全部搞清楚，不能直接往 decor 里塞。
 *
 * 五、其他确认过的事实（省得再查）
 *   - helper 类名不能写死：上游的 `bb.t` 在新版是无关的 Runnable，真 helper 是 `bb.x`
 *     （旧版是 `bb.s`）；应当从 `getHyperMaterialHelper$app_iflytekFullRelease` 这个
 *     稳定 getter 的**返回类型**取。
 *   - 字段名同样不能写死：上游的 f3472t…f3476x / f3458a 全已消失；新 helper 里包版本表
 *     字段**声明类型是 Object**（不是 Map），两个"强制深/浅色集合"只影响主题，
 *     写错会把整个键盘变深色（已复现过）。
 *   - 输入法自己那套偏好键：hyper_material、hyper_material_version、
 *     hyper_material_material_version、hyper_material_allowed_packages、
 *     hyper_material_package_versions、hyper_material_force_dark / force_light。
 *     其中后两个版本键暗示材质是**版本门控**的，而上游只伪造包白名单，抬不起这道门。
 *
 * 入口（XiaoAiIme）与推荐作用域（scope.list / xposed_scope 里的 com.xiaomi.type）保留，
 * 但本 hook 不再做任何事；开关开着也不会影响键盘。
 */
class XiaoAiSearchMaterial : BaseHook() {

    override fun init() {
        XposedLog.w(TAG, "no-op by design: see the notes in this file")
    }
}
