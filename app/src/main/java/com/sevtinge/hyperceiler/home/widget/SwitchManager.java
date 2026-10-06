package com.sevtinge.hyperceiler.home.widget;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.sevtinge.hyperceiler.R;

import fan.cardview.HyperCardView;
import fan.core.utils.HyperMaterialUtils;
import fan.core.utils.MaterialDayNightConfig;
import fan.core.utils.RomUtils;
import fan.theme.token.BloomStrokeToken;
import fan.theme.token.ColorBlendToken;
import fan.theme.token.MaterialDayNightToken;
import fan.theme.token.MaterialToken;
import fan.theme.token.hypermaterial.Mask;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

public class SwitchManager {

    private final Context mContext;
    private final ViewGroup mParent;
    private SwitchView mSwitchView;
    /** 液态玻璃样式的 Compose 版底栏（懒创建）。 */
    private com.sevtinge.hyperceiler.home.widget.compose.LiquidGlassBarView mGlassBar;
    private View mBackdropView;
    private int mMenuRes;
    private int mSelectedPosition = 0;

    private boolean isFloatingStyle;

    /** 当前样式，重建底栏时要按它恢复。 */
    private NavigationStyle mCurrentStyle = NavigationStyle.BOTTOM_LABEL;
    private OnSwitchChangeListener mUserListener;

    public SwitchManager(ViewGroup parent) {
        mParent = parent;
        mContext = parent.getContext();
    }

    public boolean isFloatingStyle() {
        return isFloatingStyle;
    }

    /**
     * 初始化并挂载视图
     */
    public void addSwitchView(int menuRes, NavigationStyle style) {
        if (mSwitchView == null) {
            mSwitchView = (SwitchView) LayoutInflater.from(mContext)
                .inflate(R.layout.switch_card_view, mParent, false);
            if (mUserListener != null) {
                mSwitchView.setOnSwitchChangeListener(mUserListener);
            }

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );

            mParent.addView(mSwitchView, lp);

            ViewCompat.requestApplyInsets(mSwitchView);
        }

        mSwitchView.inflateMenu(menuRes);
        mMenuRes = menuRes;
        setStyle(style);
    }

    /**
     * 统一入口：切换底栏样式。
     *
     * 液态玻璃走 Compose 版（要 miuix-blur 的折射/色散/重力高光），
     * 另外两种样式仍用原来的 View 版。
     */
    public void setStyle(NavigationStyle style) {
        this.mCurrentStyle = style;
        this.isFloatingStyle = style != NavigationStyle.BOTTOM_LABEL;
        boolean liquid = style == NavigationStyle.LIQUID_GLASS;

        if (liquid) {
            ensureGlassBar();
            mGlassBar.setVisibility(View.VISIBLE);
            mGlassBar.setBackdropSource(mBackdropView);
            mGlassBar.setSelectedTab(mSelectedPosition, false);
            if (mSwitchView != null) mSwitchView.setVisibility(View.GONE);
        } else if (mGlassBar != null) {
            mGlassBar.setVisibility(View.GONE);
            if (mSwitchView != null) mSwitchView.setVisibility(View.VISIBLE);
        }

        if (mSwitchView != null) {
            mSwitchView.updateStyle(style);
            // 保险：样式切换之后可见性必须和样式保持一致，否则两套底栏会同时可见。
            // （updateStyle 本身只改布局参数，不会动可见性，这里显式压一次更稳。）
            mSwitchView.setVisibility(liquid ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * 重建液态玻璃底栏（只在当前是液态玻璃时生效）。
     *
     * 只重推状态不够：搜索框展开/收起、键盘弹出/收起都会改变页面几何，而玻璃的采样
     * 纹理与尺寸会停在旧布局上 —— 表现就是底栏出现重影（玻璃里残留上一次的画面）。
     * 直接把它从父容器摘掉再重建，等于按当前布局重新初始化一遍：采样源、尺寸、
     * 选中态全部重新走一次，比逐个补同步可靠。
     */
    public void recreateGlassBar() {
        if (mGlassBar == null || mCurrentStyle != NavigationStyle.LIQUID_GLASS) return;
        // 探针：重建前先数一数父容器里有多少个子视图 —— 如果每次重建都多留一个底栏，
        // 屏幕上就会是两个底栏错位叠加，看起来正是"重影"。
        android.util.Log.w(
            "GlassReload",
            "recreate before: parentChildren=" + mParent.getChildCount() +
                " glassBarAttached=" + (mGlassBar.getParent() != null) +
                " switchView=" + (mSwitchView != null)
        );
        mParent.removeView(mGlassBar);
        mGlassBar = null;
        setStyle(NavigationStyle.LIQUID_GLASS);
        android.util.Log.w(
            "GlassReload",
            "recreate after: parentChildren=" + mParent.getChildCount()
        );
    }

    private void ensureGlassBar() {
        if (mGlassBar != null) return;
        mGlassBar = new com.sevtinge.hyperceiler.home.widget.compose.LiquidGlassBarView(mContext);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.BOTTOM;
        mParent.addView(mGlassBar, lp);
        mGlassBar.setOnSwitchChangeListener(mUserListener);
        if (mMenuRes != 0) {
            mGlassBar.inflateMenu(mMenuRes);
        }
    }

    /**
     * 液态玻璃要采样的背后内容（通常是承载页面的 ViewPager）
     */
    public void setBackdropView(View view) {
        mBackdropView = view;
        if (mSwitchView != null) {
            mSwitchView.setBackdropSource(view);
        }
        if (mGlassBar != null) {
            mGlassBar.setBackdropSource(view);
        }
    }

    /**
     * 兼容旧调用：true = 悬浮胶囊，false = 贴地底栏
     */
    public void setFloatingStyle(boolean useFloating) {
        setStyle(useFloating ? NavigationStyle.CAPSULE_ICON : NavigationStyle.BOTTOM_LABEL);
    }

    /**
     * 外部控制选中：按索引
     */
    public void setSelectedPosition(int position, boolean notify) {
        mSelectedPosition = position;
        if (mSwitchView != null) {
            mSwitchView.setSelectedTab(position, notify);
        }
        if (mGlassBar != null) {
            mGlassBar.setSelectedTab(position, notify);
        }
    }

    /**
     * 外部控制选中：按 Menu ID
     */
    public void setSelectedItemId(int itemId, boolean notify) {
        if (mSwitchView != null) {
            int pos = mSwitchView.getPositionById(itemId);
            if (pos != -1) setSelectedPosition(pos, notify);
        }
    }

    /**
     * 代理设置监听器
     */
    public void setOnSwitchChangeListener(OnSwitchChangeListener listener) {
        mUserListener = listener;
        if (mSwitchView != null) {
            mSwitchView.setOnSwitchChangeListener(listener);
        }
        if (mGlassBar != null) {
            mGlassBar.setOnSwitchChangeListener(listener);
        }
    }

    public void show() {
        // 只显示当前样式对应的那一套底栏。
        //
        // View 版（mSwitchView）与液态玻璃版（mGlassBar）是互斥的两套视图，
        // 之前这里无条件把两个都设为 VISIBLE：退出搜索的调用顺序是
        // 「先 recreateGlassBar()（内部 setStyle 会把 View 版藏掉）再 show()」，
        // 于是 show() 又把 View 版显示回来，两个底栏在同一个位置叠在一起 —— 看起来
        // 就是底栏突然变高变大、选中态错位（用户描述为"两种底栏打架"）。
        boolean liquid = mCurrentStyle == NavigationStyle.LIQUID_GLASS;
        if (mSwitchView != null) {
            mSwitchView.setVisibility(liquid ? View.GONE : View.VISIBLE);
        }
        if (mGlassBar != null) {
            if (liquid) {
                mGlassBar.setVisibility(View.VISIBLE);
                mGlassBar.setBackdropSource(mBackdropView);
            } else {
                mGlassBar.setVisibility(View.GONE);
            }
        }
    }

    public void hide() {
        if (mSwitchView != null) mSwitchView.setVisibility(View.GONE);
        if (mGlassBar != null) mGlassBar.setVisibility(View.GONE);
    }

    public SwitchView getSwitchView() {
        return mSwitchView;
    }
}
