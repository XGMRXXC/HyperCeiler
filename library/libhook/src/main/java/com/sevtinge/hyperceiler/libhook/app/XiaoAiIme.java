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
package com.sevtinge.hyperceiler.libhook.app;

import com.hchen.database.HookBase;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseLoad;
import com.sevtinge.hyperceiler.libhook.rules.phrase.XiaoAiSearchMaterial;

/**
 * Xiaomi Hyper XiaoAi Keyboard (com.xiaomi.type).
 *
 * 这里单独开一个入口，是因为它和 com.miui.voiceassist（超级小爱）是两个不同的应用，
 * 而 @HookBase 是按目标包名匹配的。
 */
@HookBase(targetPackage = "com.xiaomi.type")
public class XiaoAiIme extends BaseLoad {

    @Override
    public void onPackageLoaded() {
        initHook(new XiaoAiSearchMaterial(),
            PrefsBridge.getBoolean("phrase_xiaoai_search_material"));
    }
}
