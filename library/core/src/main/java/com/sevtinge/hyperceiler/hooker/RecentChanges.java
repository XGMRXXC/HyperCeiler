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

/**
 * 「最新更改」页面的数据：最近 12 个版本（= 最近 12 个提交，卡纳利版本号即提交数）里
 * 新增或改动的功能。由 git 历史生成，{日期, 说明}。
 */
public final class RecentChanges {

    private RecentChanges() {
    }

    public static final String[][] CHANGES = {
        {"2026-10-06", "净化安装过程说明压缩到约18字"},
        {"2026-10-06", "把新增的三处开关说明各缩短为一句话"},
        {"2026-10-06", "停用「页面以悬浮窗弹出」开关（功能未完成）"},
        {"2026-10-06", "让 system_server 认为安装器 Activity 可缩放（freeform 前置条件，尚未生效）"},
        {"2026-10-06", "用悬浮主题让整个安装器变成悬浮窗样式"},
        {"2026-10-06", "悬浮窗开关加入\"让安装器以为自己是平板\"的手段"},
        {"2026-10-06", "新增「页面以悬浮窗弹出（PAD 风格）」开关（实验性，默认关闭）"},
        {"2026-10-06", "「净化安装过程」说明里加入关闭安全守护/增强防护的引导"},
        {"2026-10-06", "禁止联网改为直接hook java.net.Socket，整进程断网"},
        {"2026-10-06", "只保留「直接禁止联网」，去掉所有状态伪装与增强防护自动处理"},
        {"2026-10-06", "新增「禁止安装器联网」开关（默认开启）"},
        {"2026-10-06", "安装器全程走离线（云端请求 1ms 超时），安装不再卡\"扫描中\""},
    };
}
