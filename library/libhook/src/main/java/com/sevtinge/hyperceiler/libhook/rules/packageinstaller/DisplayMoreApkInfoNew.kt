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
package com.sevtinge.hyperceiler.libhook.rules.packageinstaller

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.Resources
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.sevtinge.hyperceiler.libhook.R
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.api.DisplayUtils.dp2px
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.AppsTool.getModuleRes
import java.io.File
import java.text.DecimalFormat
import kotlin.math.roundToInt

/**
 * 显示更多安装包信息（安装包管理组件 5.5.4.0.0）。
 *
 * 原来这一条是 DexKit 写的，锚点字符串却是 "context.ge…ta.versionName"
 * —— 中间那个省略号是真的字符，任何 APK 里都不会有这种字符串（实测 dex 里只有
 * "versionName" / "version_name"），所以 findClass 永远找不到、requiredMember 直接抛错，
 * 整条 hook 被跳过。也就是说这个开关在 5.5.4.0.0 上早就静默失效了，不是后来改坏的。
 *
 * 这里不再用 DexKit，直接按真实类名定位（安装器这几个类都没混淆）：
 *   AppInfoViewObject(+ $ViewHolder) 和 model.ApkInfo
 * 绑定方法是 public void、单参数、参数类型正好是 ViewHolder 的那个（当前叫 D）。
 *
 * 注入方式也和旧实现不同：旧代码把整个卡片 removeAllViews 再重建，那正是最容易白屏的做法。
 * 这里只在「版本」那一行下面**追加**几行，原生视图一个都不动；追加的 View 带 tag，
 * RecyclerView 反复绑定时复用同一批，不会越堆越多。
 */
object DisplayMoreApkInfoNew : BaseHook() {

    private const val VIEW_OBJECT = "com.miui.packageInstaller.ui.listcomponets.AppInfoViewObject"
    private const val VIEW_HOLDER = "$VIEW_OBJECT\$ViewHolder"
    private const val APK_INFO = "com.miui.packageInstaller.model.ApkInfo"

    private const val TAG_ROW = "hyperceiler_more_info_row_"
    private const val MAX_ROWS = 5

    private var loggedNoField = false

    override fun init() {
        val viewObject = findClassIfExists(VIEW_OBJECT)
        if (viewObject == null) {
            log("$VIEW_OBJECT not found")
            return
        }
        val viewHolder = findClassIfExists(VIEW_HOLDER)
        if (viewHolder == null) {
            log("$VIEW_HOLDER not found")
            return
        }
        val apkInfoClass = findClassIfExists(APK_INFO)
        if (apkInfoClass == null) {
            log("$APK_INFO not found")
            return
        }

        val bind = viewObject.declaredMethods.firstOrNull {
            it.returnType == Void.TYPE && it.parameterCount == 1 && it.parameterTypes[0] == viewHolder
        }
        if (bind == null) {
            log("bind(ViewHolder) not found in ${viewObject.simpleName}")
            return
        }

        xposed().hook(bind).intercept { chain ->
            val result = chain.proceed()
            runCatching { fill(chain.thisObject, chain.args[0], apkInfoClass) }
            result
        }
        log("hooked ${viewObject.simpleName}#${bind.name}(${viewHolder.simpleName})")
    }

    private fun fill(viewObject: Any, holder: Any, apkInfoClass: Class<*>) {
        val apkInfo = viewObject.fieldOfType(apkInfoClass)
        if (apkInfo == null) {
            if (!loggedNoField) {
                loggedNoField = true
                log("no ApkInfo field in ${viewObject.javaClass.simpleName}")
            }
            return
        }
        val tvVersion = holder.call("getTvAppVersion") as? TextView ?: return
        val parent = tvVersion.parent as? ViewGroup ?: return
        val pkgInfo = apkInfo.call("getPackageInfo") as? PackageInfo ?: return
        val installed = apkInfo.call("getInstalledPackageInfo") as? ApplicationInfo
        val modRes = getModuleRes(tvVersion.context) as? Resources ?: return

        val rows = ArrayList<Pair<String, String>>()
        val newCode = pkgInfo.longVersionCode
        val newName = pkgInfo.versionName.orEmpty()
        val newMin = pkgInfo.applicationInfo?.minSdkVersion ?: 0
        val newTarget = pkgInfo.applicationInfo?.targetSdkVersion ?: 0
        val newSize = sizeOf((apkInfo.call("getFileSize") as? Long) ?: 0L)

        if (installed != null) {
            // 升级/覆盖安装：把"旧 ➟ 新"摆出来，这才是这个开关当初想给的东西
            val oldName = (apkInfo.call("getInstalledVersionName") as? String).orEmpty()
            val oldCode = (apkInfo.call("getInstalledVersionCode") as? Number)?.toLong() ?: 0L
            val oldSize = sizeOf(runCatching { File(installed.sourceDir).length() }.getOrDefault(0L))
            rows += modRes.getString(R.string.various_install_app_info_version_name) to "$oldName ➟ $newName"
            rows += modRes.getString(R.string.various_install_app_info_version_code) to "$oldCode ➟ $newCode"
            rows += modRes.getString(R.string.various_install_app_info_sdk) to
                "${installed.minSdkVersion}-${installed.targetSdkVersion} ➟ $newMin-$newTarget"
            rows += modRes.getString(R.string.various_install_app_size) to "$oldSize ➟ $newSize"
        } else {
            rows += modRes.getString(R.string.various_install_app_info_version_code) to "$newCode"
            rows += modRes.getString(R.string.various_install_app_info_sdk) to "$newMin-$newTarget"
        }
        // 包名放在最后一行，不带标签（和旧实现一致）
        rows += "" to pkgInfo.packageName.orEmpty()

        applyRows(tvVersion, parent, rows)
    }

    /** 复用已追加的行，缺哪行补哪行；原生视图一律不动 */
    private fun applyRows(tvVersion: TextView, parent: ViewGroup, rows: List<Pair<String, String>>) {
        var index = parent.indexOfChild(tvVersion) + 1
        rows.take(MAX_ROWS).forEachIndexed { i, (label, value) ->
            val text = if (label.isEmpty()) value else "$label: $value"
            val existing = parent.findViewWithTag<TextView>(TAG_ROW + i)
            val row = existing ?: TextView(tvVersion.context).also { created ->
                copyAppearance(tvVersion, created, parent)
                created.tag = TAG_ROW + i
                parent.addView(created, index)
            }
            if (row.text?.toString() != text) row.text = text
            if (row.visibility != View.VISIBLE) row.visibility = View.VISIBLE
            // tag 的行可能已经被挪动过，重新摆到版本行下面保持顺序
            val want = parent.indexOfChild(tvVersion) + 1 + i
            if (parent.indexOfChild(row) != want) {
                parent.removeView(row)
                parent.addView(row, want)
            }
            index++
        }
        // 多出来的旧行（比如从"升级"换成"新装"，行数变少）藏掉，不删
        for (i in rows.size until MAX_ROWS) {
            parent.findViewWithTag<TextView>(TAG_ROW + i)?.visibility = View.GONE
        }
        parent.requestLayout()
    }

    private fun copyAppearance(from: TextView, to: TextView, parent: ViewGroup) {
        to.textSize = 17f
        runCatching { to.setTextColor(from.textColors) }
        runCatching { to.typeface = from.typeface }
        to.gravity = Gravity.START
        to.ellipsize = TextUtils.TruncateAt.MARQUEE
        to.isSingleLine = true
        to.isHorizontalFadingEdgeEnabled = true
        to.isSelected = true
        to.setHorizontallyScrolling(true)
        // 父容器是 LinearLayout 才用它的 LayoutParams（带一点上边距）；否则用通用的，别硬套
        to.layoutParams = if (parent is LinearLayout) {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp2px(4f) }
        } else {
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun sizeOf(bytes: Long): String {
        val df = DecimalFormat("0.00")
        val kb = bytes / 1024f
        val mb = kb / 1024f
        return when {
            mb >= 1024f -> df.format(mb / 1024f) + "GB"
            mb >= 1f -> df.format(mb) + "MB"
            else -> df.format(kb) + "KB"
        }
    }

    private fun Any.call(name: String): Any? =
        runCatching { javaClass.getMethod(name).invoke(this) }.getOrNull()

    private fun Any.fieldOfType(type: Class<*>): Any? {
        var clazz: Class<*>? = javaClass
        while (clazz != null) {
            clazz.declaredFields.firstOrNull { type.isAssignableFrom(it.type) }?.let { field ->
                return runCatching {
                    field.isAccessible = true
                    field.get(this)
                }.getOrNull()
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun log(message: String) {
        android.util.Log.w("InstallerMoreInfo", message)
    }
}
