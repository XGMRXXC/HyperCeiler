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
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.sevtinge.hyperceiler.dashboard.DashboardFragment;

/**
 * 「最新更改」：列出最近 12 个版本（卡纳利版本号即提交数）中新增或改动的功能。
 * 列表由 {@link RecentChanges} 提供，直接在该版本发布时的构建里编译进去。
 */
public class RecentChangesFragment extends DashboardFragment {

    @Override
    public int getPreferenceScreenResId() {
        // 不用 XML，列表在下面按数据生成
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

        for (String[] change : RecentChanges.CHANGES) {
            Preference preference = new Preference(context);
            preference.setTitle(change[1]);
            preference.setSummary(change[0]);
            preference.setIconSpaceReserved(false);
            preference.setSelectable(false);
            screen.addPreference(preference);
        }
    }
}
