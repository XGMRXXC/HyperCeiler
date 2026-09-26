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
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.HideReportEntry;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.HideSafeModeTip;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.AutoConfirmInstallDialog;
import com.sevtinge.hyperceiler.libhook.rules.packageinstaller.AutoExitOnInstallCancel;
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

        // 「净化安装过程」：安装过程中所有会打断、劝退的环节都由这一个开关控制，
        // 细分项在这段注释里列清楚，方便对账：
        //   推广 / 风险检测 / 云端配置下发 / ICP 备案弹窗 / 安全守护提示 /
        //   安装确认弹窗自动允许 / 取消安装后自动退出 / 右上角「举报」图标 /
        //   频繁安装检查 / 解除系统应用安装限制 / 禁止上传应用信息
        boolean purify = PrefsBridge.getBoolean("miui_package_installer_purify_install");

        //
        /*initHook(new MiuiPackageInstallModify(), purify);*/

        // 禁用广告
        initHook(new DisableAd(), purify);

        // 禁用风险检测
        initHook(InstallRiskDisable.INSTANCE, purify);

        // 阻断云端配置下发
        initHook(DisableCloudCheck.INSTANCE, purify);
        // 旧实现的锚点在新版已消失（见 DisableCloudCheckFix 注释），
        // 这一份按 5.5.4.0.0 的类/签名定位，负责跳过「未查询到 ICP 备案信息」弹窗
        initHook(DisableCloudCheckFix.INSTANCE, purify);
        // ICP 备案弹窗改成"自动点确认"：伪造云端结果会导致后续拿不到数据、卡在准备页（实测）
        initHook(new AutoConfirmInstallDialog(new java.util.HashSet<>(java.util.Arrays.asList("继续安装", "继续", "仍要安装"))), purify);

        // 禁用安全守护提示
        initHook(DisableSafeModelTip.INSTANCE, purify);
        // 同一开关的 5.5.4.0.0 适配：旧实现按一个已不存在的 boolean 成员匹配，initDexKit 直接失败被跳过
        initHook(HideSafeModeTip.INSTANCE, purify);
        // 准备页里"建议开启安全守护"那个面板：把它藏掉，按钮一律不碰
        initHook(HideSafeModeDialog.INSTANCE, purify);
        // 自动允许「xxx 安装应用，是否继续」这类弹窗
        initHook(new AutoConfirmInstallDialog(new java.util.HashSet<>(java.util.Arrays.asList("允许"))), purify);

        // 取消安装后自动退出，不再停留在「已取消安装 / 完成」页
        initHook(AutoExitOnInstallCancel.INSTANCE, purify);

        // 去掉安装器右上角的「举报」图标
        initHook(HideReportEntry.INSTANCE, purify);

        // 允许更新系统应用
        initHook(AllAsSystemApp.INSTANCE, purify);

        // 自定义安装来源
        initHook(new InstallSource(), !TextUtils.isEmpty(PrefsBridge.getString("miui_package_installer_install_source", "com.android.fileexplorer")));

        // 显示更多安装包信息
        // initHook(new DisplayMoreApkInfo(), PrefsBridge.getBoolean("miui_package_installer_apk_info"));
        initHook(DisplayMoreApkInfoNew.INSTANCE, PrefsBridge.getBoolean("miui_package_installer_apk_info"));
        initHook(new DisableInstallerFullSafeVersion(), PrefsBridge.getBoolean("miui_package_installer_apk_info"));

        // 禁用频繁安装应用检查
        initHook(DisableCountChecking.INSTANCE, purify);

        // 禁止安装前后上传应用信息, 开启后会无法扫描病毒
        initHook(DisableAppInfoUpload.INSTANCE, purify);

    }
}
