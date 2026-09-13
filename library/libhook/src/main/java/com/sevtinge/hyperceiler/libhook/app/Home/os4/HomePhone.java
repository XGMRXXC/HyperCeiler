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
package com.sevtinge.hyperceiler.libhook.app.Home.os4;

import com.hchen.database.HookBase;
import com.sevtinge.hyperceiler.libhook.base.BaseLoad;

/**
 * OS4 桌面的入口（HyperOS 4 的桌面已用 Flutter + Dart + Rust 重写：APK 里是
 * libhyper_os_flutter.so / libapp.so / libresources_frb.so，逻辑不在 Java 里）。
 *
 * 目前**没有挂任何 hook**：os3 那一套是针对旧 Java 桌面的类写的，在这里找不到目标；
 * 而 OS4 上可行的做法（改系统属性、绕设备档位判断、写 secure 设置等）都要求模块能
 * 注入桌面进程 —— 当前这条通道没打通，接口设了也是空转，所以 OS4 页面只标注"暂不支持"。
 *
 * 保留这个入口是为了以后通道打通时有个落脚点：届时 hook 加在这里，并把 home_os4.xml
 * 的条目补回来即可。
 */
@HookBase(targetPackage = "com.miui.home", deviceType = 2, minOSVersion = 4.0F)
public class HomePhone extends BaseLoad {

    @Override
    public void onPackageLoaded() {
        // 只留一行日志：便于确认这个进程里模块到底有没有起来
        android.util.Log.w("Os4HomePhone", "entry loaded (no hooks yet)");
    }
}
