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
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseLoad;
import com.sevtinge.hyperceiler.libhook.rules.home.os4.WallpaperScale;

/**
 * OS4 桌面的入口（HyperOS 4 的桌面已用 Flutter + Rust 重写）。
 *
 * 单独开这一份、并且限定 minOSVersion = 4，是因为 os3 那一套 hook 全是针对旧 Java 桌面
 * 的类写的，在这台新桌面上找不到目标、等于空转；OS4 能做的效果要另写。
 */
@HookBase(targetPackage = "com.miui.home", deviceType = 2, minOSVersion = 4.0F)
public class HomePhone extends BaseLoad {

    @Override
    public void onPackageLoaded() {
        // 去除壁纸缩放：在桌面进程内把 home_wallpaper_scale_base 写回 1.0
        initHook(new WallpaperScale(), PrefsBridge.getBoolean("home_os4_wallpaper_scale_fix"));
    }
}
