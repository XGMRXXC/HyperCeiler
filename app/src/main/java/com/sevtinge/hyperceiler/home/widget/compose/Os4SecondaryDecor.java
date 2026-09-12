/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * OS4 二级页面装饰：顶栏背景模糊 + 下滑后独立的返回键。
 * 参考 HyperChanger (https://github.com/ColdP/HyperChanger)，
 * Copyright 2026 btm_m, licensed under Apache-2.0。
 */
package com.sevtinge.hyperceiler.home.widget.compose;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.sevtinge.hyperceiler.dashboard.SecondaryPageDecoration;

/**
 * 把 OS4 顶栏效果挂到二级页面上。
 *
 * 二级页面是独立的 Activity（SubSettings，源码在 library/core），跨模块不能直接引用
 * 这边的 Compose 组件，所以由 core 提供 {@link SecondaryPageDecoration} 接口、这里注册实现。
 *
 * 视图插在"内容"和"ActionBar"之间（contentRoot 的父容器里、ActionBar 之前），
 * 于是层级是：页面内容 → 模糊带/返回钮 → ActionBar，ActionBar 的标题浮在模糊之上。
 */
public final class Os4SecondaryDecor implements SecondaryPageDecoration.Provider {

    /** SettingsBaseActivity 用的标记，标明这是二级页面。 */
    private static final String EXTRA_SECOND_LAYER = ":settings:is_second_layer_page";

    public static void install() {
        SecondaryPageDecoration.setProvider(new Os4SecondaryDecor());
    }

    @Override
    public void decorate(Activity activity, View contentRoot) {
        if (activity == null || contentRoot == null) return;
        if (activity.getIntent() == null
            || !activity.getIntent().getBooleanExtra(EXTRA_SECOND_LAYER, false)) {
            return;
        }
        ViewGroup parent = contentRoot.getParent() instanceof ViewGroup
            ? (ViewGroup) contentRoot.getParent() : null;
        if (parent == null) return;

        float density = activity.getResources().getDisplayMetrics().density;
        int statusBar = getStatusBarHeight(activity);
        int barHeight = statusBar + (int) (56 * density);

        // 模糊带：整条顶栏宽度，只画不接收触摸
        LiquidTopBarView topBar = new LiquidTopBarView(activity);
        topBar.setBackdropSource(contentRoot);
        FrameLayout.LayoutParams stripParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, barHeight);
        stripParams.gravity = Gravity.TOP;
        parent.addView(topBar, indexBeforeActionBar(parent, contentRoot), stripParams);

        // 悬浮返回钮：内容下滑后出现
        LiquidBackButtonView backButton = new LiquidBackButtonView(activity);
        backButton.setBackdropSource(contentRoot);
        int size = (int) (LiquidBackButtonView.BUTTON_SIZE_DP * density);
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(size, size);
        buttonParams.gravity = Gravity.TOP | Gravity.START;
        buttonParams.leftMargin = (int) (12 * density);
        buttonParams.topMargin = statusBar + (int) (6 * density);
        parent.addView(backButton, indexBeforeActionBar(parent, contentRoot), buttonParams);

        int threshold = (int) (24 * density);
        contentRoot.getViewTreeObserver().addOnScrollChangedListener(() -> {
            int offset = maxScrollOffset(contentRoot);
            backButton.setButtonVisible(offset > threshold);
        });
    }

    /** 插在 ActionBar 之前，保证 ActionBar 的标题浮在模糊层之上。 */
    private int indexBeforeActionBar(ViewGroup parent, View contentRoot) {
        int index = parent.indexOfChild(contentRoot);
        if (index < 0) return parent.getChildCount();
        // 每次插入都放在内容之后，多次调用时自然形成 内容 → 带子 → 按钮 → ActionBar
        return Math.min(index + 1, parent.getChildCount());
    }

    private static int getStatusBarHeight(Activity activity) {
        int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? activity.getResources().getDimensionPixelSize(id) : 0;
    }

    /** 取视图树里最大的纵向滚动量（只用公开 API）。 */
    private static int maxScrollOffset(View view) {
        if (!(view instanceof ViewGroup)) {
            return view.getScrollY();
        }
        ViewGroup group = (ViewGroup) view;
        int max = view.getScrollY();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            max = Math.max(max, maxScrollOffset(child));
        }
        // RecyclerView 靠移动子视图滚动，scrollY 恒为 0，用第一个子视图的偏移代替
        if (group.getChildCount() > 0) {
            View first = group.getChildAt(0);
            max = Math.max(max, group.getPaddingTop() - first.getTop());
        }
        return max;
    }
}
