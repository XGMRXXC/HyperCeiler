package com.sevtinge.hyperceiler.home.widget;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.sevtinge.hyperceiler.R;

import java.util.ArrayList;
import java.util.List;

import fan.cardview.HyperCardView;
import fan.core.utils.HyperMaterialUtils;
import fan.core.utils.MaterialDayNightConfig;
import fan.core.utils.RomUtils;
import fan.internal.utils.AttributeResolver;
import fan.theme.token.BloomStrokeToken;
import fan.theme.token.ColorBlendToken;
import fan.theme.token.MaterialDayNightToken;
import fan.theme.token.MaterialToken;

public class SwitchView extends HyperCardView {

    // --- 内部视图 ---
    private View mDividerLine;
    private LinearLayout mTabContainer;
    private final List<View> mItemViews = new ArrayList<>();

    // --- 状态与数据 ---
    private final ViewState mCapsuleState = new ViewState();
    private final ViewState mBottomState = new ViewState();

    private NavigationStyle mCurrentStyle;
    private int mSelectedPosition = -1;
    private int mCurrentMenuRes = -1;

    // 系统底部导航栏高度缓存 (用于 Edge-to-Edge)
    private int mSystemBottomInset = 0;

    private OnSwitchChangeListener mInternalListener;

    public SwitchView(@NonNull Context context) {
        this(context, null);
    }

    public SwitchView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initStructure();
        prepareStates();
        setupEdgeToEdge();
    }

    private void initStructure() {
        setClickable(true);
        setFocusable(true);
        setCardBackgroundColor(getContext().getColor(R.color.switch_view_background_color));

        // 分割线
        mDividerLine = new View(getContext());
        mDividerLine.setBackgroundColor(AttributeResolver.resolveColor(getContext(), fan.theme.R.attr.colorDividerLine));
        addView(mDividerLine, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // Tab 容器
        mTabContainer = new LinearLayout(getContext());
        mTabContainer.setOrientation(LinearLayout.HORIZONTAL);
        addView(mTabContainer);
    }

    /**
     * Edge-to-Edge 核心逻辑
     */
    private void setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            mSystemBottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            // 收到 Insets 更新后，主动刷新一次当前样式，以应用正确的 Padding/Margin
            if (mCurrentStyle != null) {
                applyStyleState(mCurrentStyle == NavigationStyle.CAPSULE_ICON ? mCapsuleState : mBottomState);
            }
            return insets;
        });
    }

    /**
     * 物理隔离的变量配置池
     */
    private void prepareStates() {
        Resources res = getResources();

        // --- 药丸悬浮模式 ---
        mCapsuleState.selfWidth = res.getDimensionPixelSize(R.dimen.switch_view_width);
        mCapsuleState.selfHeight = res.getDimensionPixelSize(R.dimen.switch_view_height);
        mCapsuleState.selfGravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        mCapsuleState.selfBaseBottomMargin = res.getDimensionPixelSize(R.dimen.switch_view_margin_bottom);
        mCapsuleState.radius = res.getDimensionPixelSize(R.dimen.switch_card_view_radius);
        mCapsuleState.enableShadow = true;
        mCapsuleState.materialConfig = getBloomStrokeDayNightConfig();
        mCapsuleState.glass = true;

        mCapsuleState.dividerVisibility = View.GONE;
        mCapsuleState.containerWidth = ViewGroup.LayoutParams.MATCH_PARENT;
        mCapsuleState.containerHeight = ViewGroup.LayoutParams.MATCH_PARENT;
        mCapsuleState.containerGravity = Gravity.CENTER;

        mCapsuleState.itemWidth = res.getDimensionPixelSize(R.dimen.switch_view_capsule_item_width);
        mCapsuleState.itemHeight = ViewGroup.LayoutParams.MATCH_PARENT;
        mCapsuleState.itemWeight = 1f;
        mCapsuleState.showText = false;
        mCapsuleState.itemPaddingH = dpToPx(16);

        // --- 底部模式 ---
        mBottomState.selfWidth = ViewGroup.LayoutParams.MATCH_PARENT;
        mBottomState.selfHeight = ViewGroup.LayoutParams.WRAP_CONTENT;
        mBottomState.selfGravity = Gravity.BOTTOM;
        mBottomState.selfBaseBottomMargin = 0;
        mBottomState.radius = 0;
        mBottomState.enableShadow = false;
        // 贴地底栏同样走液态玻璃材质：整条磨砂 + 背景模糊 + 玻璃描边
        mBottomState.materialConfig = getBottomGlassDayNightConfig();
        mBottomState.glass = true;

        // 材质自带描边，那条 1px 硬分割线只会显脏
        mBottomState.dividerVisibility = View.GONE;
        mBottomState.containerWidth = ViewGroup.LayoutParams.MATCH_PARENT;
        mBottomState.containerHeight = ViewGroup.LayoutParams.WRAP_CONTENT;
        mBottomState.containerGravity = Gravity.TOP;

        mBottomState.itemWidth = 0;
        mBottomState.itemHeight = res.getDimensionPixelSize(fan.navigator.R.dimen.miuix_design_bottom_navigation_height);
        mBottomState.itemWeight = 1.0f;
        mBottomState.showText = true;
        mBottomState.itemPaddingH = 0;
    }

    /**
     * 唯一入口：更新样式
     */
    public void updateStyle(NavigationStyle style) {
        if (mCurrentStyle == style) return;
        mCurrentStyle = style;
        boolean isCapsule = (style == NavigationStyle.CAPSULE_ICON);
        // 开启内部元素的丝滑形变动画
        TransitionManager.beginDelayedTransition(this, new AutoTransition().setDuration(50));
        applyStyleState(isCapsule ? mCapsuleState : mBottomState);
    }

    /**
     * 将预设的状态变量应用到当前视图层级
     */
    private void applyStyleState(ViewState state) {
        if (getLayoutParams() == null) return;

        boolean isCapsule = (mCurrentStyle == NavigationStyle.CAPSULE_ICON);

        if (isCapsule) {
            setElevation(dpToPx(8));
            setTranslationZ(dpToPx(4)); // 额外增加 Z 轴偏移量
        } else {
            setElevation(0);
            setTranslationZ(0);
        }

        // HyperCardView 自身参数 (包含 Edge-to-Edge 适配)
        FrameLayout.LayoutParams selfLp = (FrameLayout.LayoutParams) getLayoutParams();
        selfLp.width = state.selfWidth;
        selfLp.height = state.selfHeight;
        selfLp.gravity = state.selfGravity;

        // 悬浮药丸要把系统横条高度加到 margin 里避免遮挡；贴地底栏则不留 margin
        selfLp.bottomMargin = isCapsule ? (state.selfBaseBottomMargin + mSystemBottomInset) : 0;
        setLayoutParams(selfLp);

        setRadius(state.radius);
        applyShadow(state.enableShadow);
        applyMaterial(state);

        // 配置 Tab 容器 (包含 Edge-to-Edge 适配)
        mDividerLine.setVisibility(state.dividerVisibility);

        FrameLayout.LayoutParams containerLp = (FrameLayout.LayoutParams) mTabContainer.getLayoutParams();
        containerLp.width = state.containerWidth;
        containerLp.height = state.containerHeight;
        containerLp.gravity = state.containerGravity;
        mTabContainer.setLayoutParams(containerLp);

        // 贴地底栏要把系统横条高度加到 padding 里把内容顶上去；药丸模式则不需要
        mTabContainer.setPadding(0, 0, 0, isCapsule ? 0 : mSystemBottomInset);

        // 配置子项
        for (View itemView : mItemViews) {
            LinearLayout.LayoutParams itemLp = (LinearLayout.LayoutParams) itemView.getLayoutParams();
            itemLp.width = state.itemWidth;
            itemLp.height = state.itemHeight;
            itemLp.weight = state.itemWeight;
            itemView.setLayoutParams(itemLp);
            itemView.setPadding(state.itemPaddingH, 0, state.itemPaddingH, 0);

            View tv = itemView.findViewById(android.R.id.text1);
            if (tv != null) tv.setVisibility(state.showText ? View.VISIBLE : View.GONE);
        }
    }

    // --- 菜单与 Item 渲染逻辑 ---
    public void inflateMenu(int menuRes) {
        if (mCurrentMenuRes == menuRes) return;
        mCurrentMenuRes = menuRes;

        mTabContainer.removeAllViews();
        mItemViews.clear();

        PopupMenu pm = new PopupMenu(getContext(), null);
        pm.inflate(menuRes);
        Menu menu = pm.getMenu();

        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            View tabView = createUnifiedTabView(item, i);
            mItemViews.add(tabView);
            mTabContainer.addView(tabView);
        }

        // 刷新一下状态
        if (mCurrentStyle != null) {
            applyStyleState(mCurrentStyle == NavigationStyle.CAPSULE_ICON ? mCapsuleState : mBottomState);
        }

        post(() -> setSelectedTab(Math.max(0, mSelectedPosition), false));
    }

    private View createUnifiedTabView(MenuItem item, int index) {
        LinearLayout itemView = new LinearLayout(getContext());
        itemView.setOrientation(LinearLayout.VERTICAL);
        itemView.setGravity(Gravity.CENTER);
        itemView.setTag(item.getItemId());

        ImageView iv = new ImageView(getContext());
        iv.setImageDrawable(item.getIcon());
        int iconSize = getResources().getDimensionPixelSize(fan.navigator.R.dimen.miuix_design_bottom_navigation_icon_size);
        itemView.addView(iv, new LinearLayout.LayoutParams(iconSize, iconSize));

        TextView tv = new TextView(getContext());
        tv.setId(android.R.id.text1);
        tv.setText(item.getTitle());
        tv.setTextSize(12f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dpToPx(2), 0, 0);
        itemView.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        itemView.setOnClickListener(v -> setSelectedTab(mItemViews.indexOf(itemView), true));
        return itemView;
    }

    public void setSelectedTab(int position, boolean notify) {
        if (position < 0 || position >= mItemViews.size()) return;
        mSelectedPosition = position;

        for (int i = 0; i < mItemViews.size(); i++) {
            mItemViews.get(i).setAlpha(i == position ? 1.0f : 0.4f);
        }

        if (notify && mInternalListener != null) {
            mInternalListener.onSwitchChange(position, (int) mItemViews.get(position).getTag());
        }
    }

    // --- 辅助方法 ---
    private void applyShadow(boolean enable) {
        if (enable) {
            setShadowColor(getContext().getColor(R.color.switch_card_shadow_color));
            setShadowDx(getResources().getDimensionPixelOffset(R.dimen.switch_view_card_shadow_dx));
            setShadowDy(getResources().getDimensionPixelOffset(R.dimen.switch_view_card_shadow_dy));
            setShadowRadius(getResources().getDimensionPixelOffset(R.dimen.switch_view_shadow_radius));
        } else {
            setShadowColor(Color.TRANSPARENT);
            setShadowRadius(0);
        }
    }

    /**
     * 应用材质。
     *
     * 玻璃材质生效时卡片底色必须是透明的：{@code switch_view_background_color}
     * 是 alpha=0xfa 的近不透明色，留着它会把模糊和玻璃描边整个盖住。
     * 设备没开「背景模糊」（{@code Settings.Secure.background_blur_enable}）时，
     * HyperMaterial 不可用，退回原来的实心底色，保证底栏依然清晰可读。
     */
    private void applyMaterial(ViewState state) {
        boolean hyperMaterial = HyperMaterialUtils.isFeatureEnable(getContext())
            && RomUtils.getHyperOsVersion() >= 2;

        setCardBackgroundColor(state.glass && hyperMaterial
            ? Color.TRANSPARENT
            : getContext().getColor(R.color.switch_view_background_color));

        if (hyperMaterial) setMaterial(state.materialConfig);
    }

    /**
     * 液态玻璃材质：磨砂模糊 + 玻璃描边。参数与 HyperOS 系统里的
     * frosted 材质对齐，只是圆角和描边尺寸按两种形态各取一套。
     *
     * @param radius      材质自身的圆角，悬浮胶囊 30dp，贴地底栏 0
     * @param lightStroke 浅色下的玻璃描边
     * @param darkStroke  深色下的玻璃描边
     */
    private MaterialDayNightConfig buildGlassConfig(int radius, float[] lightStroke, float[] darkStroke) {
        MaterialToken lightToken = new MaterialToken.Builder(radius, "frosted-pured-regular", "light")
            .setBlur(1, 1, 0, 40)
            .setColorBlend(ColorBlendToken.Pured_Regular_Light)
            .setBloomStroke(lightStroke)
            .build();

        MaterialToken darkToken = new MaterialToken.Builder(radius, "frosted-pured-extra-thick", "dark")
            .setBlur(1, 1, 0, 40)
            .setColorBlend(ColorBlendToken.Pured_Extra_Thick_Dark)
            .setBloomStroke(darkStroke)
            .build();

        return MaterialDayNightConfig.create(new MaterialDayNightToken(lightToken, darkToken));
    }

    /** 悬浮胶囊的玻璃材质：30dp 圆角 + 小尺寸描边。 */
    public MaterialDayNightConfig getBloomStrokeDayNightConfig() {
        return buildGlassConfig(30, BloomStrokeToken.Glass_Stroke_Small_Light, BloomStrokeToken.Glass_Stroke_Small_Dark);
    }

    /** 贴地底栏的玻璃材质：直角整条，用大尺寸描边才压得住。 */
    public MaterialDayNightConfig getBottomGlassDayNightConfig() {
        return buildGlassConfig(0, BloomStrokeToken.Glass_Stroke_Big_Light, BloomStrokeToken.Glass_Stroke_Big_Dark);
    }

    public int getPositionById(int itemId) {
        for (int i = 0; i < mItemViews.size(); i++) {
            if ((int) mItemViews.get(i).getTag() == itemId) return i;
        }
        return -1;
    }

    public int getSelectedPosition() {
        return mSelectedPosition;
    }

    public void setOnSwitchChangeListener(OnSwitchChangeListener l) {
        mInternalListener = l;
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // --- 状态结构体 ---
    private static class ViewState {
        int selfWidth, selfHeight, selfGravity, selfBaseBottomMargin;
        float radius;
        boolean enableShadow;
        MaterialDayNightConfig materialConfig;
        boolean glass;

        int dividerVisibility;
        int containerWidth, containerHeight, containerGravity;

        int itemWidth, itemHeight, itemPaddingH;
        float itemWeight;
        boolean showText;
    }
}
