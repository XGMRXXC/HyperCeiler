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
package com.sevtinge.hyperceiler.dashboard;

import android.app.Activity;

/**
 * 二级页面的额外装饰（OS4 顶栏模糊 + 下滑后独立返回键）的挂接点。
 *
 * 实现放在 app 模块（Compose / miuix-blur 依赖都在那边），core 只留这个接口，
 * 避免模块依赖方向反过来。app 在启动时调用 {@link #setProvider(Provider)} 注册。
 */
public final class SecondaryPageDecoration {

    public interface Provider {
        /**
         * @param activity 二级页面所在的 Activity
         * @param contentRoot 该 Activity 的内容根（android.R.id.content）
         */
        void decorate(Activity activity, android.view.View contentRoot);
    }

    private static Provider sProvider;

    private SecondaryPageDecoration() {}

    public static void setProvider(Provider provider) {
        sProvider = provider;
    }

    /** 由 SettingsBaseActivity 在 setContentView 之后调用。 */
    public static void apply(Activity activity, android.view.View contentRoot) {
        Provider provider = sProvider;
        if (provider != null && activity != null && contentRoot != null) {
            provider.decorate(activity, contentRoot);
        }
    }
}
