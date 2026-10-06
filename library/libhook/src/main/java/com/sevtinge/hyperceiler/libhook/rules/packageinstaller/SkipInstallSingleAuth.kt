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
package com.sevtinge.hyperceiler.libhook.rules.packageinstaller

import android.app.Activity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 跳过「单次安装授权」。
 *
 * 现象：安全守护处于「增强防护 / 中级模式」时，安装未经应用商店检测的应用会被拦下，
 * 提示「请前往页面右上角"︙" > 单次安装授权」，必须手动授权一次才能继续。
 * 目标：点了「确认安装」就直接开始安装。
 *
 * 链路（全部来自 com.miui.packageinstaller 的 smali 反汇编，不是推断）：
 *
 * 1. 设置项读写类 `B0.c`（混淆名），键名是明文字符串：
 *      B0.c.f:()Ljava/lang/String;   → 读 "security_mode_install_once_date"（默认 ""）
 *      B0.c.B:(Ljava/lang/String;)V  → 写同一个键
 *
 * 2. 判定类 `Ly2.L$a`：
 *      b(Context)Z:
 *          B0.c.f() 为空            → false
 *          否则与 utils.h.a() 比较，相等才 true        ← 即"今天授权过"
 *      a(Context)V: B0.c.B("")   → 清除授权
 *
 * 3. 「今天」的格式在 `com.android.packageinstaller.utils.h.a()` 里（明文类名）：
 *      new SimpleDateFormat("yyyy-MM-dd").format(new Date())
 *
 * 4. 拦截点 `Lo2.d.e(...)`（校验方式分发）：
 *      invoke-virtual {v6, v0}, Ly2/L$a;.b:(Landroid/content/Context;)Z
 *      if-eqz v6, +0x12
 *          false → 走"未授权"分支（就是提示去右上角"︙"的那条）
 *          true  → 走"今天已授权"分支，直接放行
 *
 * 5. 而 `B0.c.f()` 在整个 dex 里**只有第 2 步这一处调用**（已核对：1 处），
 *    所以把它改成"今天已授权"最精准：只影响这一个判定，
 *    不动云端配置、不动风险检测、也不写任何持久化数据。
 */
object SkipInstallSingleAuth : BaseHook() {

    override fun useDexKit() = true

    override fun initDexKit(): Boolean {
        // requiredMember 只能在 initDexKit() 里解析，三个成员都要在这里先取一次。
        onceDateGetter
        onceDateSetter
        singleAuthAction
        return true
    }

    /**
     * 读 `security_mode_install_once_date` 的 getter。
     *
     * 用这个键名做锚点的方法有两个（getter 与 setter），所以必须同时限定
     * 无参数 + 返回 String，否则 `.single()` 会因为命中两个而失败。
     */
    private val onceDateGetter by lazy {
        requiredMember("SkipInstallSingleAuth1") {
            it.findMethod {
                matcher {
                    addUsingString("security_mode_install_once_date", StringMatchType.Equals)
                    paramCount = 0
                    returnType = "java.lang.String"
                }
            }.single()
        } as Method
    }

    override fun init() {
        android.util.Log.w(TAG, "hook target = ${onceDateGetter.declaringClass?.name}.${onceDateGetter.name}")
        onceDateGetter.createHook {
            before { param ->
                // 与 utils.h.a() 完全同款格式（默认 Locale），保证 TextUtils.equals 判定为真
                param.result = today()
                android.util.Log.w(TAG, "once-date getter called -> ${param.result}")
            }
        }

        // 5.5.6 上「继续安装」这条路径不会读这个 getter（实测打点确认），
        // 顺手把授权日期写好，等价于提前替用户点一次"单次安装授权"。
        runCatching {
            onceDateGetter.declaringClass.declaredConstructors.forEach { ctor ->
                runCatching {
                    ctor.createHook {
                        after { param ->
                            runCatching {
                                onceDateSetter.invoke(param.thisObject, today())
                                android.util.Log.w(TAG, "auto wrote once-date = ${today()}")
                            }.onFailure {
                                android.util.Log.w(TAG, "auto write failed: $it")
                            }
                        }
                    }
                }
            }
        }.onFailure { android.util.Log.w(TAG, "hook constructors failed: $it") }

        hookContinueButton()
    }

    /**
     * 让「继续安装」按钮执行和"︙ → 单次安装授权"完全相同的那一项。
     *
     * 实测（Xiaomi 17 Pro，安装器 5.5.6.0.0-20260914）：
     *   - 联网检测失败时页面停在"网络异常，无法进行联网检测"，点「继续安装」毫无反应；
     *   - 手动点"︙ → 单次安装授权"后安装**立刻开始**（直接进 InstallProgressActivity，
     *     随后 `pm list packages` 能看到已安装）；
     *   - 反编译：那一项最终执行 `NewInstallerPrepareActivity.Z1()`，而 Z1 在这个 dex 里
     *     只有这一个调用者 —— 即「继续安装」按钮那条路根本不经过它。
     *
     * 所以不去猜按钮原本的逻辑，直接复用那一项：给按钮换上"选中同一个菜单项"的点击。
     * 菜单对象来自页面自己的 onCreateOptionsMenu（声明在父类，需沿继承链找）。
     */
    private fun hookContinueButton() {
        runCatching {
            val prepare = findClassIfExists(PREPARE_ACTIVITY)
            if (prepare == null) {
                android.util.Log.w(TAG, "prepare activity not found")
                return
            }
            runCatching {
                findMethodInHierarchy(prepare, "onCreateOptionsMenu", Menu::class.java)?.createHook {
                    after { param -> mMenu = param.args?.getOrNull(0) as? Menu }
                } ?: android.util.Log.w(TAG, "onCreateOptionsMenu not found")
            }.onFailure { android.util.Log.w(TAG, "menu hook failed: $it") }

            runCatching {
                findMethodInHierarchy(prepare, "onResume")?.createHook {
                    after { param ->
                        val act = param.thisObject as? Activity ?: return@after
                        act.window?.decorView?.post { attachContinueShortcut(act, 0) }
                    }
                } ?: android.util.Log.w(TAG, "onResume not found")
            }.onFailure { android.util.Log.w(TAG, "resume hook failed: $it") }

            // 云校验结果回来时页面内容一定已经建好，也补一次（此时通常就能挂上）。
            val clearHandler = prepare.declaredMethods.firstOrNull { m ->
                m.parameterTypes.size == 1 && m.parameterTypes[0].name == CLOUD_PARAMS
            }
            clearHandler?.createHook {
                after { param ->
                    val act = param.thisObject as? Activity ?: return@after
                    act.window?.decorView?.post { attachContinueShortcut(act, 0) }
                }
            }
        }.onFailure { android.util.Log.w(TAG, "hookContinueButton failed: $it") }
    }

    private fun attachContinueShortcut(act: Activity, attempt: Int) {
        runCatching {
            val decor = act.window?.decorView as? ViewGroup ?: return
            val label = findLabelView(decor, CONTINUE_LABELS, 0)
            if (label == null) {
                // 页面内容是异步填进 fragment_container 的，onResume 时还看不到，
                // 所以每 200ms 重试一次，最多约 3 秒。
                if (attempt < 15) {
                    decor.postDelayed({ attachContinueShortcut(act, attempt + 1) }, 200)
                } else {
                    android.util.Log.w(TAG, "continue label not found after retries; tree dump:")
                    dumpTree(decor, 0, intArrayOf(0))
                }
                return
            }
            // 文字本身通常不可点，真正的点击落在某个祖先容器上。
            var target: View? = label
            while (target != null && !target.isClickable) target = target.parent as? View
            if (target == null) {
                android.util.Log.w(TAG, "no clickable ancestor for '${(label as? TextView)?.text}'")
                return
            }
            if (target === mHookedButton) return
            // 只在联网检测失败（按钮点了没反应）时才接管；正常校验通过时保持原流程，
            // 免得把 MIUI 的风险提示一起跳过。结果还没回来就再等一会儿。
            if (!CloudCheckState.failed) {
                if (attempt < 25) {
                    decor.postDelayed({ attachContinueShortcut(act, attempt + 1) }, 200)
                } else {
                    android.util.Log.w(TAG, "cloud check ok, leave continue button alone")
                }
                return
            }
            mHookedButton = target
            target.setOnClickListener {
                android.util.Log.w(TAG, "continue clicked -> single-authorize")
                if (!triggerSingleAuthorize(act)) {
                    android.util.Log.w(TAG, "single-authorize not triggered")
                }
            }
            android.util.Log.w(TAG, "continue hooked on ${target.javaClass.simpleName}")
        }.onFailure { android.util.Log.w(TAG, "attachContinueShortcut failed: $it") }
    }

    /** 触发那一项：直接调用它的处理方法（最可靠），其它方式作为兜底。 */
    private fun triggerSingleAuthorize(act: Activity): Boolean {
        // 首选：DexKit 按两个特征字符串锁定的那个方法（= 菜单项真正执行的动作）。
        runCatching {
            android.util.Log.w(TAG, "invoking ${singleAuthAction.name}()")
            singleAuthAction.invoke(act)
            return true
        }.onFailure { android.util.Log.w(TAG, "invoke action failed: $it") }

        mMenu?.let { menu ->
            val items = (0 until menu.size()).map { menu.getItem(it) }
            val target = items.firstOrNull { AUTHORIZE_LABELS.contains(it.title?.toString().orEmpty()) }
            if (target != null) {
                android.util.Log.w(TAG, "selecting captured menu item '${target.title}'")
                return act.onOptionsItemSelected(target)
            }
        }
        val id = act.resources.getIdentifier(AUTHORIZE_ITEM_NAME, "id", act.packageName)
        if (id != 0) {
            val item = PopupMenu(act, null).menu.add(0, id, 0, "")
            android.util.Log.w(TAG, "selecting synthesized item id=0x${Integer.toHexString(id)}")
            return act.onOptionsItemSelected(item)
        }
        android.util.Log.w(TAG, "no way to trigger single-authorize")
        return false
    }

    /**
     * "单次安装授权"那一项的处理方法。
     *
     * 反编译 5.5.6 可见 `NewInstallerPrepareActivity` 里有两个方法用到
     * "single_authorize_btn"：其中一个（埋点用的 `a2`）只用这一个字符串，
     * 而真正干活的 `J1` 还额外用了 "verify_method" 并调用 `Z1()`（安装入口）。
     * 用这两个字符串 + 无参无返回 就能唯一定位到它，与混淆名无关。
     */
    private val singleAuthAction by lazy {
        requiredMember("SkipInstallSingleAuth3") {
            it.findMethod {
                matcher {
                    addUsingString("single_authorize_btn", StringMatchType.Equals)
                    addUsingString("verify_method", StringMatchType.Equals)
                    paramCount = 0
                    returnType = "void"
                }
            }.single()
        } as Method
    }

    /** 找文字命中 labels 的控件（不管可点击性）。 */
    private fun findLabelView(root: ViewGroup?, labels: Set<String>, depth: Int): View? {
        if (root == null || depth > 30) return null
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is ViewGroup) findLabelView(child, labels, depth + 1)?.let { return it }
            if (child is TextView &&
                labels.contains(child.text?.toString()?.trim().orEmpty())
            ) return child
        }
        return null
    }

    /** 视图树转储：找不到按钮时用来看真实结构（限量输出，避免刷屏）。 */
    private fun dumpTree(root: ViewGroup?, depth: Int, budget: IntArray) {
        if (root == null || depth > 12 || budget[0] > 24) return
        for (i in 0 until root.childCount) {
            if (budget[0] > 24) return
            val child = root.getChildAt(i)
            budget[0]++
            var id = ""
            runCatching { id = child.resources.getResourceEntryName(child.id) }
            val text = (child as? TextView)?.text?.toString()?.trim().orEmpty()
            android.util.Log.w(
                TAG,
                "  ".repeat(depth) + "${child.javaClass.simpleName} id=$id clickable=${child.isClickable}" +
                    if (text.isNotEmpty()) " text='$text'" else ""
            )
            if (child is ViewGroup) dumpTree(child, depth + 1, budget)
        }
    }

    /** 沿继承链查找方法（onResume / onCreateOptionsMenu 这类声明在父类里）。 */
    private fun findMethodInHierarchy(cls: Class<*>, name: String, vararg params: Class<*>): Method? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            runCatching { return c.getDeclaredMethod(name, *params) }
            c = c.superclass
        }
        return null
    }

    /**
     * 写 `security_mode_install_once_date` 的 setter（与 getter 同一个类，单个 String 参数）。
     */
    private val onceDateSetter by lazy {
        requiredMember("SkipInstallSingleAuth2") {
            it.findMethod {
                matcher {
                    addUsingString("security_mode_install_once_date", StringMatchType.Equals)
                    paramCount = 1
                    paramTypes("java.lang.String")
                }
            }.single()
        } as Method
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd").format(Date())

    private var mMenu: Menu? = null
    private var mHookedButton: View? = null

    private const val TAG = "HCSingleAuth"
    private const val PREPARE_ACTIVITY = "com.miui.packageInstaller.NewInstallerPrepareActivity"
    private const val CLOUD_PARAMS = "com.miui.packageInstaller.model.CloudParams"

    /** "单次安装授权"那一项的 id 资源名（5.5.6.0.0 里就是它）。 */
    private const val AUTHORIZE_ITEM_NAME = "Y3"

    private val CONTINUE_LABELS = setOf("继续安装", "繼續安裝", "继续", "仍要安装", "Continue")
    private val AUTHORIZE_LABELS =
        setOf("单次安装授权", "單次安裝授權", "Single-use authorization", "Single install authorization")
}
