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
        }
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
        if (mSwitchView != null) mSwitchView.setVisibility(View.VISIBLE);
    }

    public void hide() {
        if (mSwitchView != null) mSwitchView.setVisibility(View.GONE);
    }

    public SwitchView getSwitchView() {
        return mSwitchView;
    }
}
