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

package com.sevtinge.hyperceiler.hooker;


import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreHyperOSVersion;

import com.sevtinge.hyperceiler.prefs.LayoutPreference;
import com.sevtinge.hyperceiler.core.R;
import com.sevtinge.hyperceiler.dashboard.DashboardFragment;
import com.sevtinge.hyperceiler.libhook.utils.pkg.CheckModifyUtils;

public class HomeFragment extends DashboardFragment {

    LayoutPreference mHeader;

    @Override
    public int getPreferenceScreenResId() {
        // 桌面分区按系统版本分流：
        //   OS4（桌面已被 Flutter + Rust 重写）→ home_os4（目前只标注"暂不支持"）
        //   OS3 及以下                        → 原来那一页 home_new
        // 注意 isMoreHyperOSVersion 的语义是 ">="（hyperOSSDK >= code），
        // 所以这里写 4 表示"OS4 及以上"，OS3 会落到原来那一页。
        if (isMoreHyperOSVersion(4f)) {
            return R.xml.home_os4;
        }
        return R.xml.home_new;
    }

    @Override
    public void initPrefs() {
        mHeader = findPreference("prefs_key_home_unsupported");

        boolean check = CheckModifyUtils.INSTANCE.getCheckResult(getContext(), "com.miui.home");
        boolean isDebugMode = getSharedPreferences().getBoolean("prefs_key_development_debug_mode", false);

        // 两个页面都有这个条目，但分流改了之后仍防它缺失，避免空指针
        if (mHeader != null) {
            mHeader.setVisible(check && !isDebugMode);
        }
    }

}
