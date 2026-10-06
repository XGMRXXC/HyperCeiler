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
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;

import com.sevtinge.hyperceiler.dashboard.DashboardFragment;
import com.sevtinge.hyperceiler.dashboard.SubSettings;
import com.sevtinge.hyperceiler.utils.SettingLauncherHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「最新更改」：列出**当前运行版本之前** 12 个版本里改动过的应用，并在每个应用下面
 * 列出它自己的更新日志。
 *
 * 版本号就是构建时的提交数（卡纳利版本号 rNNNN），当前版本号从 PackageManager 读，
 * 所以以后每个新版本运行起来，看到的都是它自己之前 12 个版本的改动，不需要改代码。
 *
 * 每个应用一张卡片：第一行是应用本身，点击跳到该应用的设置页（标题与目标页面取自主页
 * header，走的还是搜索结果那条 SettingLauncherHelper 路径）；下面几行是它在这些版本里的更新日志。
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

        for (Map.Entry<String, List<Object[]>> entry : collectChanges(context).entrySet()) {
            String pkg = entry.getKey();
            Object[] target = findTarget(pkg);
            if (target == null) {
                continue;
            }

            PreferenceCategory category = new PreferenceCategory(context);
            category.setIconSpaceReserved(false);
            screen.addPreference(category);

            // 第一行：应用本身，点了跳到它的设置页
            String fragment = (String) target[2];
            String pageName = (String) target[3];
            int pageRes = pageName == null || pageName.isEmpty() ? 0
                : context.getResources().getIdentifier(pageName, "xml", context.getPackageName());

            Preference app = new Preference(context);
            app.setTitle(resolveString(context, (String) target[1], pkg));
            app.setSummary(pkg);
            app.setIconSpaceReserved(false);
            app.setOnPreferenceClickListener(p -> {
                openTarget(context, fragment, pageRes);
                return true;
            });
            category.addPreference(app);

            // 下面几行：这个应用在这些版本里的更新日志（纯展示，不可点）
            for (Object[] change : entry.getValue()) {
                int version = (Integer) change[0];
                String date = (String) change[1];
                String text = (String) change[2];

                Preference item = new ReadOnlyPreference(context);
                item.setTitle(text);
                item.setSummary("r" + version + " · " + date);
                item.setIconSpaceReserved(false);
                item.setSelectable(false);
                category.addPreference(item);
            }
        }
    }

    /**
     * 只用来展示的条目：除了不可选，连 itemView 的点击/焦点也关掉，
     * 否则点它还是会有按压高亮。
     */
    private static class ReadOnlyPreference extends Preference {

        ReadOnlyPreference(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            holder.itemView.setClickable(false);
            holder.itemView.setLongClickable(false);
            holder.itemView.setFocusable(false);
            holder.itemView.setFocusableInTouchMode(false);
            // 直接把触摸事件吞掉：否则按下时仍会出现水波纹/按压高亮
            holder.itemView.setOnTouchListener((v, event) -> true);
        }
    }

    /** 取"不高于当前版本"的最近 12 个版本，按包名分组（最近改动优先）。 */
    private Map<String, List<Object[]>> collectChanges(Context context) {
        int currentVersion = getCurrentVersionCode(context);
        Map<String, List<Object[]>> grouped = new LinkedHashMap<>();
        int seenVersions = 0;

        for (Object[] record : RecentChanges.HISTORY) {
            int version = (Integer) record[0];
            if (currentVersion > 0 && version > currentVersion) {
                continue;
            }
            if (seenVersions >= RecentChanges.RECENT_VERSION_COUNT) {
                break;
            }
            seenVersions++;

            String pkg = (String) record[1];
            if (pkg == null) {
                continue;
            }
            List<Object[]> list = grouped.get(pkg);
            if (list == null) {
                list = new ArrayList<>();
                grouped.put(pkg, list);
            }
            // {版本号, 日期, 说明}
            list.add(new Object[]{version, record[2], record[3]});
        }
        return grouped;
    }

    private Object[] findTarget(String pkg) {
        for (Object[] target : RecentChanges.TARGETS) {
            if (pkg.equals(target[0])) {
                return target;
            }
        }
        return null;
    }

    private CharSequence resolveString(Context context, String name, String fallback) {
        int res = context.getResources().getIdentifier(name, "string", context.getPackageName());
        return res != 0 ? context.getText(res) : fallback;
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
