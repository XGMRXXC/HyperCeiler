package com.sevtinge.hyperceiler.home.widget;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
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
import fan.theme.token.hypermaterial.Mask;

public class SwitchView extends HyperCardView {

    /**
     * 新增的液态玻璃样式：背景模糊比原来两种样式（40dp）更重，
     * 才有「隔着一层毛玻璃」的观感。
     */
    private static final int LIQUID_GLASS_BLUR_RADIUS_DP = 60;

    /** 选中指示器相对每个 item 的内缩，和 KernelSU FloatingBottomBar 的 4dp 对齐。 */
    private static final int LIQUID_INDICATOR_INSET_DP = 4;

    // --- 内部视图 ---
    private View mDividerLine;
    private View mIndicatorView;
    private LinearLayout mTabContainer;
    private final List<View> mItemViews = new ArrayList<>();

    // --- 状态与数据 ---
    private final ViewState mCapsuleState = new ViewState();
    private final ViewState mBottomState = new ViewState();
    private final ViewState mLiquidState = new ViewState();

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

        // 选中指示器（仅液态玻璃样式可见）。必须加在 Tab 容器之前，
        // 这样图标是盖在指示器上面的。
        mIndicatorView = new View(getContext());
        mIndicatorView.setVisibility(View.GONE);
        addView(mIndicatorView, new FrameLayout.LayoutParams(0, 0));

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
                applyStyleState(stateFor(mCurrentStyle));
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
        mBottomState.materialConfig = getDayNightConfig();

        mBottomState.dividerVisibility = View.VISIBLE;
        mBottomState.containerWidth = ViewGroup.LayoutParams.MATCH_PARENT;
        mBottomState.containerHeight = ViewGroup.LayoutParams.WRAP_CONTENT;
        mBottomState.containerGravity = Gravity.TOP;

        mBottomState.itemWidth = 0;
        mBottomState.itemHeight = res.getDimensionPixelSize(fan.navigator.R.dimen.miuix_design_bottom_navigation_height);
        mBottomState.itemWeight = 1.0f;
        mBottomState.showText = true;
        mBottomState.itemPaddingH = 0;

        // --- 液态玻璃悬浮底栏（新增样式，参考 KernelSU manager 的 FloatingBottomBar）---
        // 悬浮药丸：宽度贴合图标、圆角=高度一半、等宽图标、带滑动选中指示器
        mLiquidState.selfWidth = ViewGroup.LayoutParams.WRAP_CONTENT;
        mLiquidState.selfHeight = res.getDimensionPixelSize(R.dimen.switch_view_liquid_height);
        mLiquidState.selfGravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        mLiquidState.selfBaseBottomMargin = res.getDimensionPixelSize(R.dimen.switch_view_margin_bottom);
        mLiquidState.radius = res.getDimensionPixelSize(R.dimen.switch_view_liquid_radius);
        mLiquidState.enableShadow = true;
        mLiquidState.materialConfig = getLiquidGlassDayNightConfig();
        mLiquidState.glass = true;

        mLiquidState.dividerVisibility = View.GONE;
        mLiquidState.showIndicator = true;
        mLiquidState.containerWidth = ViewGroup.LayoutParams.WRAP_CONTENT;
        mLiquidState.containerHeight = ViewGroup.LayoutParams.MATCH_PARENT;
        mLiquidState.containerGravity = Gravity.CENTER;
        mLiquidState.containerPaddingH = dpToPx(LIQUID_INDICATOR_INSET_DP);

        mLiquidState.itemWidth = res.getDimensionPixelSize(R.dimen.switch_view_liquid_item_width);
        mLiquidState.itemHeight = ViewGroup.LayoutParams.MATCH_PARENT;
        mLiquidState.itemWeight = 0f;
        mLiquidState.showText = false;
        mLiquidState.itemPaddingH = 0;
    }

    /**
     * 唯一入口：更新样式
     */
    public void updateStyle(NavigationStyle style) {
        if (mCurrentStyle == style) return;
        mCurrentStyle = style;
        // 开启内部元素的丝滑形变动画
        TransitionManager.beginDelayedTransition(this, new AutoTransition().setDuration(50));
        applyStyleState(stateFor(style));
    }

    /** 样式 → 配置池的映射。 */
    private ViewState stateFor(NavigationStyle style) {
        if (style == NavigationStyle.CAPSULE_ICON) return mCapsuleState;
        if (style == NavigationStyle.LIQUID_GLASS) return mLiquidState;
        return mBottomState;
    }

    /** 悬浮形态（胶囊 / 液态玻璃）才会飘起来并把系统横条高度算进 margin。 */
    private static boolean isFloating(NavigationStyle style) {
        return style == NavigationStyle.CAPSULE_ICON || style == NavigationStyle.LIQUID_GLASS;
    }

    /**
     * 将预设的状态变量应用到当前视图层级
     */
    private void applyStyleState(ViewState state) {
        if (getLayoutParams() == null) return;

        boolean isCapsule = isFloating(mCurrentStyle);

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

        // 贴地底栏要把系统横条高度加到 padding 里把内容顶上去；悬浮形态则不需要
        mTabContainer.setPadding(state.containerPaddingH, 0, state.containerPaddingH,
            isCapsule ? 0 : mSystemBottomInset);

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

        // 样式切换后要等布局稳定，指示器才能量到真实的 item 位置
        post(() -> updateIndicator(false));
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
            applyStyleState(stateFor(mCurrentStyle));
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

        updateIndicator(true);

        if (notify && mInternalListener != null) {
            mInternalListener.onSwitchChange(position, (int) mItemViews.get(position).getTag());
        }
    }

    /**
     * 液态玻璃样式的滑动选中指示器（对应 KernelSU FloatingBottomBar 里那块跟着
     * 选中项走的玻璃高亮）。其他样式下隐藏。
     */
    private void updateIndicator(boolean animate) {
        if (mIndicatorView == null) return;

        boolean visible = mCurrentStyle != null && stateFor(mCurrentStyle).showIndicator
            && mSelectedPosition >= 0 && mSelectedPosition < mItemViews.size();
        if (!visible) {
            mIndicatorView.animate().cancel();
            mIndicatorView.setVisibility(View.GONE);
            return;
        }

        View item = mItemViews.get(mSelectedPosition);
        if (item.getWidth() == 0 || item.getHeight() == 0) return; // 还没布局完

        int inset = dpToPx(LIQUID_INDICATOR_INSET_DP);
        int width = Math.max(0, item.getWidth() - inset * 2);
        int height = Math.max(0, item.getHeight() - inset * 2);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mIndicatorView.getLayoutParams();
        lp.width = width;
        lp.height = height;
        lp.gravity = Gravity.TOP | Gravity.START;
        mIndicatorView.setLayoutParams(lp);

        float targetX = mTabContainer.getLeft() + item.getLeft() + inset;
        float targetY = mTabContainer.getTop() + item.getTop() + inset;

        applyIndicatorBackground(height / 2f); // 圆角跟着高度走，保证是药丸形

        boolean wasVisible = mIndicatorView.getVisibility() == View.VISIBLE;
        if (animate && wasVisible) {
            mIndicatorView.animate()
                .translationX(targetX)
                .translationY(targetY)
                .setDuration(260)
                .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f))
                .start();
        } else {
            mIndicatorView.animate().cancel();
            mIndicatorView.setTranslationX(targetX);
            mIndicatorView.setTranslationY(targetY);
        }
        mIndicatorView.setVisibility(View.VISIBLE);
    }

    /** 指示器外观：半透明填充 + 细描边，KernelSU 在无模糊时也是这么画的。 */
    private void applyIndicatorBackground(float radiusPx) {
        boolean night = (getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(radiusPx);
        background.setColor(night ? 0x1AFFFFFF : 0x1A000000);
        background.setStroke(dpToPx(1), night ? 0x1FFFFFFF : 0x14000000);
        mIndicatorView.setBackground(background);
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
     * 液态玻璃样式的卡片底色必须是透明的：{@code switch_view_background_color}
     * 是 alpha=0xfa 的近不透明色，留着它会把模糊和玻璃描边整个盖住。原来两种
     * 样式保持原样（glass=false），外观和上游完全一致。
     *
     * 设备没开「背景模糊」（{@code Settings.Secure.background_blur_enable}）时
     * HyperMaterial 不可用，此时退回实心底色，保证底栏依然清晰可读。
     */
    private void applyMaterial(ViewState state) {
        boolean hyperMaterial = HyperMaterialUtils.isFeatureEnable(getContext())
            && RomUtils.getHyperOsVersion() >= 2;

        setCardBackgroundColor(state.glass && hyperMaterial
            ? Color.TRANSPARENT
            : getContext().getColor(R.color.switch_view_background_color));

        if (hyperMaterial) setMaterial(state.materialConfig);
    }

    /** 悬浮胶囊（原有样式，未改动）。 */
    public MaterialDayNightConfig getBloomStrokeDayNightConfig() {
        MaterialToken lightToken = new MaterialToken.Builder(30, "frosted-pured-regular", "light")
            .setBlur(1, 1, 0, 40)
            .setColorBlend(ColorBlendToken.Pured_Regular_Light)
            .setBloomStroke(BloomStrokeToken.Glass_Stroke_Small_Light)
            .build();

        MaterialToken darkToken = new MaterialToken.Builder(30, "frosted-pured-extra-thick", "dark")
            .setBlur(1, 1, 0, 40)
            .setColorBlend(ColorBlendToken.Pured_Extra_Thick_Dark)
            .setBloomStroke(BloomStrokeToken.Glass_Stroke_Small_Dark)
            .build();

        return MaterialDayNightConfig.create(new MaterialDayNightToken(lightToken, darkToken));
    }

    /** 贴地底栏（原有样式，未改动）。 */
    public MaterialDayNightConfig getDayNightConfig() {
        return MaterialDayNightConfig.create(Mask.Pured_Regular);
    }

    /**
     * 新增的液态玻璃样式：比胶囊更薄的填充 + 更重的背景模糊，
     * 目标是 KernelSU 那个「隔着毛玻璃的悬浮药丸」的观感。
     */
    public MaterialDayNightConfig getLiquidGlassDayNightConfig() {
        MaterialToken lightToken = new MaterialToken.Builder(32, "frosted-pured-thin", "light")
            .setBlur(1, 1, 0, LIQUID_GLASS_BLUR_RADIUS_DP)
            .setColorBlend(ColorBlendToken.Pured_Thin_Light)
            .setBloomStroke(BloomStrokeToken.Glass_Stroke_Small_Light)
            .build();

        MaterialToken darkToken = new MaterialToken.Builder(32, "frosted-pured-thin", "dark")
            .setBlur(1, 1, 0, LIQUID_GLASS_BLUR_RADIUS_DP)
            .setColorBlend(ColorBlendToken.Pured_Thin_Dark)
            .setBloomStroke(BloomStrokeToken.Glass_Stroke_Small_Dark)
            .build();

        return MaterialDayNightConfig.create(new MaterialDayNightToken(lightToken, darkToken));
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
        /** true 时底栏自带透明+模糊材质（新增的液态玻璃样式）。 */
        boolean glass;

        int dividerVisibility;
        boolean showIndicator;
        int containerWidth, containerHeight, containerGravity, containerPaddingH;

        int itemWidth, itemHeight, itemPaddingH;
        float itemWeight;
        boolean showText;
    }
}
