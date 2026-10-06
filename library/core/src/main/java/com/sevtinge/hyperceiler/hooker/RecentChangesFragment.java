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
package com.sevtinge.hyperceiler.hooker;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.sevtinge.hyperceiler.dashboard.DashboardFragment;
import com.sevtinge.hyperceiler.dashboard.SubSettings;
import com.sevtinge.hyperceiler.utils.SettingLauncherHelper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「最新更改」：列出**当前运行版本之前** 12 个版本里改动过的应用。
 *
 * 版本号就是构建时的提交数（卡纳利版本号 rNNNN），当前版本号从 PackageManager 读，
 * 所以以后每个新版本运行起来，看到的都是它自己之前 12 个版本改动过的应用，不需要改代码。
 *
 * 列表按应用去重（顺序 = 最近改动优先），点击某个应用就跳到该应用自己的设置页
 * （标题与目标页面都取自主页 header，走的还是搜索结果那条 SettingLauncherHelper 路径）。
 */
public class RecentChangesFragment extends DashboardFragment {

    @Override
    public int getPreferenceScreenResId() {
        // 不用 XML，列表按数据生成
        return 0;
    }

    @Override
    public void onCreatePreferencesAfter(Bundle bundle, String s) {
        super.onCreatePreferencesAfter(bundle, s);

        Context context = getContext();
        if (context == null) {
            return;
        }

        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);

        for (String pkg : collectChangedPackages(context)) {
            Object[] target = findTarget(pkg);
            if (target == null) {
                continue;
            }

            // 资源按名字解析：数据文件里存的是资源名，避免生成代码依赖具体 R 常量。
            String titleName = (String) target[1];
            String fragment = (String) target[2];
            String pageName = (String) target[3];

            int titleRes = context.getResources()
                .getIdentifier(titleName, "string", context.getPackageName());
            int pageRes = pageName == null || pageName.isEmpty() ? 0
                : context.getResources().getIdentifier(pageName, "xml", context.getPackageName());

            Preference preference = new Preference(context);
            if (titleRes != 0) {
                preference.setTitle(titleRes);
            } else {
                preference.setTitle(pkg);
            }
            preference.setSummary(pkg);
            preference.setIconSpaceReserved(false);
            preference.setOnPreferenceClickListener(p -> {
                openTarget(context, fragment, pageRes);
                return true;
            });
            screen.addPreference(preference);
        }
    }

    /** 取"不高于当前版本"的最近 12 个版本，收集它们改动过的包（去重、最近优先）。 */
    private List<String> collectChangedPackages(Context context) {
        int currentVersion = getCurrentVersionCode(context);
        Set<String> packages = new LinkedHashSet<>();
        int seenVersions = 0;

        for (Object[] entry : RecentChanges.HISTORY) {
            int version = (Integer) entry[0];
            if (currentVersion > 0 && version > currentVersion) {
                continue;
            }
            if (seenVersions >= RecentChanges.RECENT_VERSION_COUNT) {
                break;
            }
            seenVersions++;
            String pkg = (String) entry[1];
            if (pkg != null) {
                packages.add(pkg);
            }
        }
        return new ArrayList<>(packages);
    }

    private Object[] findTarget(String pkg) {
        for (Object[] target : RecentChanges.TARGETS) {
            if (pkg.equals(target[0])) {
                return target;
            }
        }
        return null;
    }

    private void openTarget(Context context, String fragment, int pageRes) {
        boolean hasFragment = fragment != null && !fragment.isEmpty();
        Bundle args = new Bundle();
        if (pageRes != 0) {
            args.putInt(":settings:fragment_resId", pageRes);
        }
        SettingLauncherHelper.onStartSettingsForArguments(
            context,
            SubSettings.class,
            hasFragment ? fragment : DashboardFragment.class.getName(),
            args,
            0);
    }

    private int getCurrentVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
            return (int) info.getLongVersionCode();
        } catch (Throwable t) {
            return 0;
        }
    }
}
