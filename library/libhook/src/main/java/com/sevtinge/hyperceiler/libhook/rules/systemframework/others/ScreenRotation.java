/*
  * This file is part of HyperCeiler.

  * HyperCeiler is free software: you can redistribute it and/or modify
  * it under the terms of the GNU Affero General Public License as
  * published by the Free Software Foundation, either version 3 of the
  * License.

  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU Affero General Public License for more details.

  * You should have received a copy of the GNU Affero General Public License
  * along with this program.  If not, see <https://www.gnu.org/licenses/>.

  * Copyright (C) 2023-2026 HyperCeiler Contributions
*/
package com.sevtinge.hyperceiler.libhook.rules.systemframework.others;

import android.content.Context;

import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

public class ScreenRotation extends BaseHook {

    @Override
    public void init() {
        findAndHookMethod("com.android.internal.view.RotationPolicy", "areAllRotationsAllowed", Context.class, returnConstant(PrefsBridge.getBoolean("system_framework_screen_all_rotations")));

        hookAllConstructors("com.android.server.wm.DisplayRotation", new IMethodHook() {
            @Override
            public void after(HookParam param) {
                com.sevtinge.hyperceiler.libhook.base.BaseHook.setIntField(param.getThisObject(), "mAllowAllRotations", PrefsBridge.getBoolean("system_framework_screen_all_rotations") ? 1 : 0);
            }
        });

        // 这里原本还有一句资源替换：
        //   setObjectReplacement("android", "bool", "config_allowAllRotations", ...)
        // 它是冗余的 —— 上面两个 hook 已经把策略查询（areAllRotationsAllowed）和
        // DisplayRotation 的内部字段都按同一个开关强制了。去掉它之后，
        // system_server 里就再没有任何地方需要模块资源，于是 BaseLoad 可以彻底不在
        // system_server 注入 EzResources（见 BaseLoad#loadModuleResources 的说明：
        // 覆盖安装模块后正是那套注入状态失效，导致 system_server 解析资源时段错误）。
    }
}
