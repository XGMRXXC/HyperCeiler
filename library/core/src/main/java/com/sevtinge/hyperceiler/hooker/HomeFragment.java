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

import android.content.Context;
import android.provider.Settings;

import androidx.preference.Preference;

import com.sevtinge.hyperceiler.prefs.LayoutPreference;
import com.sevtinge.hyperceiler.core.R;
import com.sevtinge.hyperceiler.dashboard.DashboardFragment;
import com.sevtinge.hyperceiler.libhook.utils.pkg.CheckModifyUtils;

public class HomeFragment extends DashboardFragment {

    LayoutPreference mHeader;

    /** OS4：去除壁纸缩放。 */
    private static final String KEY_OS4_WALLPAPER_SCALE = "prefs_key_home_os4_wallpaper_scale_fix";

    /** 桌面读取的壁纸缩放基准（实测本机 Settings.Secure 里是 1.05）。 */
    private static final String SECURE_WALLPAPER_SCALE_BASE = "home_wallpaper_scale_base";

    /** 改之前的值存在应用自己的 prefs 里，关掉开关时好还原。 */
    private static final String PREF_WALLPAPER_SCALE_BACKUP = "os4_wallpaper_scale_base_backup";

    @Override
    public int getPreferenceScreenResId() {
        // 桌面分区按系统版本分流：
        //   OS4（桌面已被 Flutter + Rust 重写）→ 新开的 OS4 专用页 home_os4
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

        initOs4Prefs();
    }

    /**
     * OS4 页面的开关。这些条目只在 OS4 页面里存在，找不到就什么都不做。
     */
    private void initOs4Prefs() {
        Context context = getContext();
        if (context == null) return;

        Preference wallpaperScale = findPreference(KEY_OS4_WALLPAPER_SCALE);
        if (wallpaperScale == null) return;

        wallpaperScale.setOnPreferenceChangeListener((preference, newValue) -> {
            applyWallpaperScaleFix(context, (Boolean) newValue);
            return true;
        });
        // 进页面时对齐一次：开关状态和系统里的实际值保持一致
        applyWallpaperScaleFix(context, getSharedPreferences().getBoolean(KEY_OS4_WALLPAPER_SCALE, false));
    }

    /**
     * 去除壁纸缩放。
     *
     * OS4 桌面的壁纸缩放读的是 Settings.Secure 的 home_wallpaper_scale_base，
     * 本机默认 1.05 —— 也就是壁纸会被放大一点点再随桌面动。把它写成 1.0 就没有这个缩放。
     * 关掉开关时还原成改之前的值（存在应用自己的 prefs 里）。
     *
     * 桌面是在启动时读这个值，所以页面里带 app:quick_restart="com.miui.home"，
     * 改完由框架重启桌面生效。
     */
    private void applyWallpaperScaleFix(Context context, boolean enabled) {
        try {
            if (enabled) {
                String current = Settings.Secure.getString(context.getContentResolver(), SECURE_WALLPAPER_SCALE_BASE);
                if (current != null && !getSharedPreferences().contains(PREF_WALLPAPER_SCALE_BACKUP)) {
                    getSharedPreferences().edit().putString(PREF_WALLPAPER_SCALE_BACKUP, current).apply();
                }
                Settings.Secure.putString(context.getContentResolver(), SECURE_WALLPAPER_SCALE_BASE, "1.0");
            } else {
                String backup = getSharedPreferences().getString(PREF_WALLPAPER_SCALE_BACKUP, null);
                if (backup != null) {
                    Settings.Secure.putString(context.getContentResolver(), SECURE_WALLPAPER_SCALE_BASE, backup);
                    getSharedPreferences().edit().remove(PREF_WALLPAPER_SCALE_BACKUP).apply();
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("HomeFragment", "applyWallpaperScaleFix failed: " + t.getMessage());
        }
    }

}
