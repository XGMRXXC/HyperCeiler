package com.sevtinge.hyperceiler.home.banner;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.sevtinge.hyperceiler.R;

import java.util.ArrayList;
import java.util.List;

public class HomePageBannerHelper {

    public static List<View> getBannerViews(Context context, View.OnClickListener listener) {
        List<View> views = new ArrayList<>();
        List<BannerBean> beans = HomePageBannerManager.getLocalBannerBeans(context);
        for (BannerBean bean : beans) {
            views.add(createViewFromBean(context, bean, listener));
        }
        return views;
    }

    public static Drawable getBannerIcon(Context context, BannerBean bannerBean) throws NumberFormatException {
        if (context == null) {
            return null;
        }
        if (bannerBean.getIconResId() != -1) {
            return context.getDrawable(bannerBean.getIconResId());
        }
        return null;
    }

    /**
     * 将 BannerBean 转换为 View
     */
    /**
     * 4. 统一渲染 View 的方法
     */
    private static View createViewFromBean(Context context, BannerBean bean, View.OnClickListener listener) {
        // 关键：attachToRoot 传 false
        View v = LayoutInflater.from(context).inflate(R.layout.settings_banner_main_layout, null, false);

        TextView title = v.findViewById(android.R.id.title);
        TextView summary = v.findViewById(android.R.id.summary);
        ImageView iconView = v.findViewById(android.R.id.icon);
        ImageView arrowRightView = v.findViewById(R.id.arrow_right);

        ViewGroup containerView = v.findViewById(R.id.container);

        if (title != null) {
            if (bean.getTitle() != null) {
                title.setText(bean.getTitle());
            } else {
                title.setVisibility(View.GONE);
            }
            if (bean.getTitleColorResId() != -1) {
                title.setTextColor(ContextCompat.getColor(context, bean.getTitleColorResId()));
            } else if (!TextUtils.isEmpty(bean.getTitleColor())) {
                title.setTextColor(Color.parseColor(bean.getTitleColor()));
            }
        }
        if (summary != null) {
            if (bean.getSummary() != null) {
                summary.setText(bean.getSummary());
            } else {
                summary.setVisibility(View.GONE);
            }

            if (bean.getSubTitleColorResId() != -1) {
                summary.setTextColor(ContextCompat.getColor(context, bean.getSubTitleColorResId()));
            } else if (!TextUtils.isEmpty(bean.getSummaryColor())) {
                summary.setTextColor(Color.parseColor(bean.getSummaryColor()));
            }
        }

        if (iconView != null) {
            Drawable icon = getBannerIcon(context, bean);
            if (icon != null) {
                iconView.setImageDrawable(icon);
                iconView.setVisibility(View.VISIBLE);
            } else {
                iconView.setVisibility(View.GONE);
            }
        }

        if (bean.getArrowIcon() != -1) {

        } else {
            arrowRightView.setVisibility(View.GONE);
        }

        if (bean.getBackgroundColorResId() != -1) {
            containerView.setBackgroundResource(bean.getBackgroundColorResId());
        } else if (!TextUtils.isEmpty(bean.getBackgroundColor())) {
            containerView.setBackgroundColor(Color.parseColor(bean.getBackgroundColor()));
        }

        // 红色警告这类提示，用户没义务一直看着 —— 给它们加一个"✕"，点了就永久关掉
        // （按 id 记住，见 HomePageBannerManager.dismissBanner）。
        // 用代码加而不是改布局：这套布局别的横幅也在用，改布局会波及它们。
        String bannerId = bean.getId();
        if (bannerId != null && bannerId.startsWith("warning") && containerView != null) {
            ImageView closeView = new ImageView(context);
            int size = Math.round(20 * context.getResources().getDisplayMetrics().density);
            android.widget.LinearLayout.LayoutParams closeParams =
                new android.widget.LinearLayout.LayoutParams(size, size);
            closeParams.setMarginStart(Math.round(6 * context.getResources().getDisplayMetrics().density));
            closeView.setLayoutParams(closeParams);
            closeView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            closeView.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            closeView.setContentDescription(context.getString(android.R.string.cancel));
            closeView.setClickable(true);
            closeView.setOnClickListener(view -> {
                HomePageBannerManager.dismissBanner(view.getContext(), bannerId);
                // 立刻从当前界面上拿走，不用等下次刷新列表
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(v);
                }
            });
            containerView.addView(closeView);
            // 有 ✕ 的时候右箭头没意义
            if (arrowRightView != null) arrowRightView.setVisibility(View.GONE);
        }

        // 点击事件处理
        v.setTag(bean); // 将数据存在 tag 里方便回调获取
        v.setOnClickListener(listener);

        //Folme.useAt(v).touch().setScale(0.95f).handleTouchOf(v, new AnimConfig[0]);

        return v;
    }
}
