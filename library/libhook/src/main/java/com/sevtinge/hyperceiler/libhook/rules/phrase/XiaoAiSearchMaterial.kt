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
 * 让超级小爱输入法在所有页面使用"全局搜索页"的高级材质。
 *
 * 思路来自 HyperChanger 的 SearchPageAppearance（Apache-2.0，Copyright 2026 btm_m）：
 * 输入法按 `editorInfo.packageName` 决定材质档位，把包名伪造成全局搜索
 * （com.android.quicksearchbox）就能让任何输入框套上搜索页那套 HyperMaterial。
 *
 * **当前状态：不生效，且强制反而有害，因此本 hook 不做任何写入。** 依据是输入法自己的日志：
 *
 *     W ime_SystemClipboard: setHyperMaterialEnabled failed; ignoring
 *     D WmSystemUiDebug: set navigation bar color, Alpha=1.0, RGB:24,25,27
 *        caller: MiInputMethodService.reapplyHyperMaterialState:111
 *                MiInputMethodService.applyHyperMaterialRunnable$lambda$58:46
 *     D backgroundBlur: setMiViewMaterialType not update,0
 *
 * 也就是说：一旦把材质支持位强制为 true，输入法就会去启用材质、启用**失败**，
 * 然后回退成深色不透明键盘（用户看到的"大黑块"）。上游那套（以及本文件早先的移植）
 * 正是这么写的，所以在这个输入法版本上它的净效果是负面的。
 *
 * 移植过程中确认过的、值得留给后来者的东西（都对着 0.2.790 的 dex 核对过）：
 *  - helper 类名不能写死：上游写的 `bb.t` 现在是个无关的 Runnable，真 helper 是 `bb.x`，
 *    而且应当从 `getHyperMaterialHelper$app_iflytekFullRelease` 这个**稳定 getter 的返回类型**取；
 *  - 字段名也不能写死：上游的 f3472t…f3476x/f3458a 全部已消失；`bb.x` 中，
 *    材质支持位是唯一的那个约定名 boolean，包版本表字段**声明类型是 Object**（不是 Map）；
 *  - 两个"强制深/浅色集合"只影响主题，写错会让整个键盘变成深色，与材质无关；
 *  - 输入法自己那套偏好键（dex 里可查到）：hyper_material、hyper_material_version、
 *    hyper_material_material_version、hyper_material_allowed_packages、
 *    hyper_material_package_versions、hyper_material_force_dark / force_light
 *    —— 高级材质看起来是**版本门控**的，而上游只伪造了包白名单。
 *
 * 入口（XiaoAiIme）与推荐作用域（scope.list / xposed_scope 里的 com.xiaomi.type）保留，
 * 等输入法自身真的支持这一档、或者找到能通过那道版本门的方法后再启用。
 */
class XiaoAiSearchMaterial : BaseHook() {

    override fun init() {
        XposedLog.w(TAG, DISABLED_REASON)
    }

    private companion object {
        const val DISABLED_REASON =
            "skipped on purpose: forcing the material makes this keyboard call " +
                "setHyperMaterialEnabled, which fails, and it then falls back to a dark " +
                "opaque keyboard (its own log: 'setHyperMaterialEnabled failed; ignoring')"
    }
}
