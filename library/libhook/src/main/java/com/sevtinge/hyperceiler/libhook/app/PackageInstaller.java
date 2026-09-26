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
package com.sevtinge.hyperceiler.libhook.app;

import android.text.TextUtils;

import com.hchen.database.HookBase;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseLoad;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.AllAsSystemApp;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.DisableAd;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.DisableAppInfoUpload;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.DisableCloudCheck;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.DisableCloudCheckFix;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.HideSafeModeTip;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.AutoConfirmInstallDialog;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.HideSafeModeDialog;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.DisableCountChecking;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.DisableInstallerFullSafeVersion;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.DisableSafeModelTip;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.DisplayMoreApkInfoNew;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.InstallRiskDisable;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.InstallSource;

@HookBase(targetPackage = "com.miui.packageinstaller")
public class PackageInstaller extends BaseLoad {


    public void onPackageLoaded() {

        //
        /*initHook(new MiuiPackageInstallModify(), PrefsBridge.getBoolean("miui_package_installer_modify"));*/

        // 禁用广告
        initHook(new DisableAd(), PrefsBridge.getBoolean("miui_package_installer_disable_ad"));

        // 禁用风险检测
        initHook(InstallRiskDisable.INSTANCE, PrefsBridge.getBoolean("miui_package_installer_install_risk"));

        // 阻断云端配置下发
        initHook(DisableCloudCheck.INSTANCE, PrefsBridge.getBoolean("miui_package_installer_disable_cloud_check"));
        // 同一个开关的 5.5.4.0.0 适配：旧实现的锚点在新版已消失（见 DisableCloudCheckFix 注释），
        // 这一份按新版的类/签名定位，负责跳过「未查询到 ICP 备案信息」弹窗
        initHook(DisableCloudCheckFix.INSTANCE, PrefsBridge.getBoolean("miui_package_installer_disable_cloud_check"));
        // ICP 备案弹窗改成"自动点确认"：伪造云端结果会导致后续拿不到数据、卡在准备页（实测）
        initHook(new AutoConfirmInstallDialog(new java.util.HashSet<>(java.util.Arrays.asList("继续安装", "继续", "仍要安装"))), PrefsBridge.getBoolean("miui_package_installer_disable_cloud_check"));

        // 禁用安全守护提示
        initHook(DisableSafeModelTip.INSTANCE, PrefsBridge.getBoolean("miui_package_installer_safe_model_tip"));
        // 同一开关的 5.5.4.0.0 适配：旧实现按一个已不存在的 boolean 成员匹配，initDexKit 直接失败被跳过
        initHook(HideSafeModeTip.INSTANCE, PrefsBridge.getBoolean("miui_package_installer_safe_model_tip"));
        // 准备页里"建议开启安全守护"那个面板：自动点「继续安装」
        initHook(HideSafeModeDialog.INSTANCE, PrefsBridge.getBoolean("miui_package_installer_safe_model_tip"));
        // 自动允许「xxx 安装应用，是否继续」这类弹窗
        initHook(new AutoConfirmInstallDialog(new java.util.HashSet<>(java.util.Arrays.asList("允许"))), PrefsBridge.getBoolean("miui_package_installer_auto_allow_install"));

        // 允许更新系统应用
        initHook(AllAsSystemApp.INSTANCE, PrefsBridge.getBoolean("miui_package_installer_update_system_app"));

        // 自定义安装来源
        initHook(new InstallSource(), !TextUtils.isEmpty(PrefsBridge.getString("miui_package_installer_install_source", "com.android.fileexplorer")));

        // 显示更多安装包信息
        // initHook(new DisplayMoreApkInfo(), PrefsBridge.getBoolean("miui_package_installer_apk_info"));
        initHook(DisplayMoreApkInfoNew.INSTANCE, PrefsBridge.getBoolean("miui_package_installer_apk_info"));
        initHook(new DisableInstallerFullSafeVersion(), PrefsBridge.getBoolean("miui_package_installer_apk_info"));

        // 禁用频繁安装应用检查
        initHook(DisableCountChecking.INSTANCE, PrefsBridge.getBoolean("miui_package_installer_count_checking"));

        // 禁用安装前后上传应用信息, 开启后会无法扫描病毒
        initHook(DisableAppInfoUpload.INSTANCE, PrefsBridge.getBoolean("miui_package_installer_upload_appinfo"));

    }
}
