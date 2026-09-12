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
package com.sevtinge.hyperceiler.dashboard.base;

import com.sevtinge.hyperceiler.dashboard.SecondaryPageDecoration;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.sevtinge.hyperceiler.common.utils.AppLanguageHelper;
import com.sevtinge.hyperceiler.core.R;

import fan.appcompat.app.AppCompatActivity;

public class SettingsBaseActivity extends AppCompatActivity implements ActivityCallback {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLanguageHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //PageDecorator.applyGrayScaleFilter(this);
        //registerObserver(getApplicationContext());
        createUiFromIntent(savedInstanceState, getIntent());
    }

    protected void createUiFromIntent(Bundle savedInstanceState, Intent intent) {
        setContentView(R.layout.settings_sub);
        // OS4 二级页面装饰（顶栏模糊 + 下滑后独立返回键），实现由 app 模块注册
        SecondaryPageDecoration.apply(this, findViewById(android.R.id.content));
        String initialFragmentName = getInitialFragmentName(intent);
        if (!TextUtils.isEmpty(initialFragmentName)) {
            Fragment targetFragment = getTargetFragment(this, initialFragmentName, savedInstanceState);
            if (targetFragment != null) {
                targetFragment.setArguments(getArguments(intent));
                setFragment(targetFragment);
            }
        }
    }


    public void setFragment(Fragment fragment) {
        getSupportFragmentManager()
            .beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.frame_content, fragment)
            .commitNow();
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        return super.onOptionsItemSelected(item);
    }
}
