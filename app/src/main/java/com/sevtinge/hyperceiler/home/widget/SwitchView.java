package com.sevtinge.hyperceiler.home.widget;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
import com.sevtinge.hyperceiler.home.widget.liquid.LiquidGlassOverlay;
import com.sevtinge.hyperceiler.home.widget.liquid.SpringValue;

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

public class SwitchView extends HyperCardView implements SensorEventListener {

    /**
     * 新增的液态玻璃样式：背景模糊比原来两种样式（40dp）更重，
     * 才有「隔着一层毛玻璃」的观感。
     */
    private static final int LIQUID_GLASS_BLUR_RADIUS_DP = 60;

    /** 选中指示器相对每个 item 的内缩，和 KernelSU FloatingBottomBar 的 4dp 对齐。 */
    private static final int LIQUID_INDICATOR_INSET_DP = 4;

    /** 按下时整条药丸的缩放增量（KernelSU 用 16dp / 宽度）。 */
    private static final int LIQUID_PRESS_SCALE_DP = 16;

    /** 拖动时药丸的橡皮筋最大位移。 */
    private static final int LIQUID_RUBBER_BAND_DP = 4;

    /** 重力的方向阈值：|g_xy| > 0.1（约 6°）才认为有倾斜。 */
    private static final float GRAVITY_THRESHOLD_SQ = 0.01f;

    /** 高光角度按 3° 量化，避免传感器抖动导致高光乱飘。 */
    private static final float GRAVITY_ANGLE_STEP = (float) (3.0 * Math.PI / 180.0);

    // --- 内部视图 ---
    private View mDividerLine;
    private View mIndicatorView;
    private final Paint mGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 液态玻璃的背景渲染器（自己抓快照做模糊+折射+色散）。 */
    private LiquidGlassOverlay mGlassBackdrop;
    private LinearLayout mTabContainer;
    private final List<View> mItemViews = new ArrayList<>();

    // --- 状态与数据 ---
    private final ViewState mCapsuleState = new ViewState();
    private final ViewState mBottomState = new ViewState();
    private final ViewState mLiquidState = new ViewState();

    // --- 液态玻璃：弹簧、手势、传感器 ---
    private SpringValue mIndicatorSpring;
    private SpringValue mPressSpring;
    private SpringValue mScaleSpring;
    private SpringValue mPanelSpring;
    private SpringValue mTouchAlphaSpring;
    private SpringValue mDispersionSpring;
    private SensorManager mSensorManager;
    private boolean mTiltRegistered;
    private float mLightAngle = (float) (-Math.PI / 2.0);
    private float mTouchX;
    private float mTouchY;
    private float mDragValue;
    private float mDownX;
    private float mDownY;
    private float mLastDragX;
    private boolean mDragging;
    private int mTouchSlop;
    private int mTabWidthPx;
    private int mTotalWidthPx;

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
        initLiquid();
        setupEdgeToEdge();
    }

    /** 液态玻璃样式的弹簧与手势参数，全部对齐 KernelSU 的 DampedDragAnimation。 */
    private void initLiquid() {
        mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        if (LiquidGlassOverlay.isSupported()) {
            mGlassBackdrop = new LiquidGlassOverlay(getContext());
        }

        mIndicatorSpring = new SpringValue(1000f, 1f, 0.001f, 0f);
        mPressSpring = new SpringValue(1000f, 1f, 0.001f, 0f);
        mScaleSpring = new SpringValue(250f, 0.6f, 0.001f, 1f);
        mPanelSpring = new SpringValue(300f, 0.5f, 0.01f, 0f);
        mTouchAlphaSpring = new SpringValue(300f, 0.5f, 0.001f, 0f);
        mDispersionSpring = new SpringValue(300f, 0.5f, 0.001f, 0f);

        Runnable frame = this::applyLiquidFrame;
        mIndicatorSpring.setOnUpdate(frame);
        mPressSpring.setOnUpdate(frame);
        mScaleSpring.setOnUpdate(frame);
        mPanelSpring.setOnUpdate(frame);
        mTouchAlphaSpring.setOnUpdate(frame);
        mDispersionSpring.setOnUpdate(frame);
    }

    private void initStructure() {
        setClickable(true);
        setFocusable(true);
        setCardBackgroundColor(getContext().getColor(R.color.switch_view_background_color));

        // 分割线
        mDividerLine = new View(getContext());
        mDividerLine.setBackgroundColor(AttributeResolver.resolveColor(getContext(), fan.theme.R.attr.colorDividerLine));
        addView(mDividerLine, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // 选中指示器（仅悬浮药丸样式可见）。必须加在 Tab 容器之前，
        // 这样图标是盖在指示器上面的。
        mIndicatorView = new View(getContext());
        mIndicatorView.setVisibility(View.GONE);
        addView(mIndicatorView, new FrameLayout.LayoutParams(0, 0));

        // 注意：这里**不能**再挂一个全尺寸的自绘子 View（试过 AGSL 玻璃层）——
        // 只要 HyperCardView 里多出一个用 RuntimeShader 画的子 View，HyperOS 的
        // 背景模糊就整个失效（日志里 setMiViewMaterialType 一直是 0），药丸会变成
        // 完全没有底色的图标。所以高光改成在 dispatchDraw 里用渐变直接画。

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

        // --- 悬浮药丸（原「悬浮胶囊」：形态换成新药丸，材质仍是原来那套 frosted）---
        applyPillShape(mCapsuleState, res);
        mCapsuleState.materialConfig = getCapsuleGlassDayNightConfig();

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
        // 和上面的药丸同形态，额外带 AGSL 玻璃层 + 弹簧拖拽 + 倾斜高光
        applyPillShape(mLiquidState, res);
        mLiquidState.glassOverlay = true;
        mLiquidState.materialConfig = getLiquidGlassDayNightConfig();
    }

    /**
     * 两种悬浮药丸（「悬浮胶囊」和「液态玻璃」）共用的形态：
     * 宽度贴合图标、圆角=高度一半、等宽图标、带滑动选中指示器。
     */
    private void applyPillShape(ViewState state, Resources res) {
        state.selfWidth = ViewGroup.LayoutParams.WRAP_CONTENT;
        state.selfHeight = res.getDimensionPixelSize(R.dimen.switch_view_liquid_height);
        state.selfGravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        state.selfBaseBottomMargin = res.getDimensionPixelSize(R.dimen.switch_view_margin_bottom);
        state.radius = res.getDimensionPixelSize(R.dimen.switch_view_liquid_radius);
        state.enableShadow = true;
        state.glass = true;

        state.dividerVisibility = View.GONE;
        state.showIndicator = true;
        state.containerWidth = ViewGroup.LayoutParams.WRAP_CONTENT;
        state.containerHeight = ViewGroup.LayoutParams.MATCH_PARENT;
        state.containerGravity = Gravity.CENTER;
        state.containerPaddingH = dpToPx(LIQUID_INDICATOR_INSET_DP);

        state.itemWidth = res.getDimensionPixelSize(R.dimen.switch_view_liquid_item_width);
        state.itemHeight = ViewGroup.LayoutParams.MATCH_PARENT;
        state.itemWeight = 0f;
        state.showText = false;
        state.itemPaddingH = 0;
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

        if (!state.glassOverlay) {
            // 离开液态玻璃样式时把交互形变复位
            setScaleX(1f);
            setScaleY(1f);
            mTabContainer.setTranslationX(0f);
        } else {
            invalidate();
        }

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

        // 液态玻璃样式的位置/形变全部由弹簧驱动，走 applyLiquidFrame()
        if (mCurrentStyle == NavigationStyle.LIQUID_GLASS) {
            if (animate) {
                mIndicatorSpring.animateTo(mSelectedPosition);
            } else {
                mIndicatorSpring.snapTo(mSelectedPosition);
            }
            applyLiquidFrame();
            return;
        }

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

        // 液态玻璃样式自己把整块药丸画出来（背后快照 + 模糊 + 折射 + 色散 + 高光），
        // 系统的 HyperMaterial 会盖在自绘内容之上，所以这条样式不用它。
        boolean useMaterial = hyperMaterial && !state.glassOverlay;

        setCardBackgroundColor(state.glass && useMaterial
            ? Color.TRANSPARENT
            : getContext().getColor(R.color.switch_view_background_color));
        if (state.glassOverlay) {
            setCardBackgroundColor(Color.TRANSPARENT);
        }

        if (useMaterial) setMaterial(state.materialConfig);
    }

    /**
     * 「悬浮胶囊」的玻璃材质。
     *
     * 形态换成新药丸之后，这里沿用原来胶囊那套混合（亮色 Regular / 暗色
     * Extra_Thick），所以不会像早期版本那样「透明到跟没有一样」。
     */
    public MaterialDayNightConfig getCapsuleGlassDayNightConfig() {
        return buildPillGlassConfig(40, ColorBlendToken.Pured_Regular_Light,
            ColorBlendToken.Pured_Extra_Thick_Dark);
    }

    /** 贴地底栏（原有样式，未改动）。 */
    public MaterialDayNightConfig getDayNightConfig() {
        return MaterialDayNightConfig.create(Mask.Pured_Regular);
    }

    /**
     * 「液态玻璃」的材质：在胶囊基础上把背景模糊加大到 60dp，
     * 边缘的折射/色散/高光由 {@link LiquidGlassOverlay} 那层 AGSL 叠上去。
     */
    public MaterialDayNightConfig getLiquidGlassDayNightConfig() {
        return buildPillGlassConfig(LIQUID_GLASS_BLUR_RADIUS_DP, ColorBlendToken.Pured_Regular_Light,
            ColorBlendToken.Pured_Extra_Thick_Dark);
    }

    /**
     * 药丸共用的 frosted 材质。
     *
     * @param blurRadius 背景模糊半径（dp）
     * @param light      浅色混合，越「厚」越不透明
     * @param dark       深色混合
     */
    private MaterialDayNightConfig buildPillGlassConfig(int blurRadius, ColorBlendToken light,
                                                       ColorBlendToken dark) {
        MaterialToken lightToken = new MaterialToken.Builder(32, "frosted-pured-regular", "light")
            .setBlur(1, 1, 0, blurRadius)
            .setColorBlend(light)
            .setBloomStroke(BloomStrokeToken.Glass_Stroke_Small_Light)
            .build();

        MaterialToken darkToken = new MaterialToken.Builder(32, "frosted-pured-extra-thick", "dark")
            .setBlur(1, 1, 0, blurRadius)
            .setColorBlend(dark)
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

    // ================= 液态玻璃样式：弹簧驱动的手势与光学效果 =================

    /**
     * 每帧把弹簧状态刷到视图上。这些量对应 KernelSU FloatingBottomBar 里的
     * dampedDragAnimation（位置/按下进度/缩放/位移）与 InteractiveHighlight。
     */
    private void applyLiquidFrame() {
        if (mCurrentStyle != NavigationStyle.LIQUID_GLASS || mItemViews.isEmpty()) return;

        updatePillMetrics();
        int inset = dpToPx(LIQUID_INDICATOR_INSET_DP);
        float value = mIndicatorSpring.get();
        float press = mPressSpring.get();
        float panelOffset = mPanelSpring.get();

        if (mIndicatorView != null && mTabWidthPx > 0) {
            int width = Math.max(0, mTabWidthPx - inset * 2);
            int height = Math.max(0, mTabContainer.getHeight() - inset * 2);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mIndicatorView.getLayoutParams();
            if (lp.width != width || lp.height != height) {
                lp.width = width;
                lp.height = height;
                lp.gravity = Gravity.TOP | Gravity.START;
                mIndicatorView.setLayoutParams(lp);
                applyIndicatorBackground(height / 2f);
            }
            mIndicatorView.setVisibility(View.VISIBLE);
            mIndicatorView.setTranslationX(value * mTabWidthPx + inset + panelOffset);
            mIndicatorView.setTranslationY(inset);

            // 拖动速度让指示器沿运动方向拉伸（对应 KernelSU 的 layerBlock）
            float velocity = mIndicatorSpring.getVelocity() / 10f;
            float scaleX = 1f / (1f - clamp(velocity * 0.75f, -0.2f, 0.2f));
            float scaleY = 1f - clamp(velocity * 0.25f, -0.2f, 0.2f);
            mIndicatorView.setScaleX(clamp(scaleX, 0.6f, 1.6f));
            mIndicatorView.setScaleY(clamp(scaleY, 0.6f, 1.6f));
        }

        // 整条药丸按下放大 + 拖动橡皮筋位移
        float scale = mScaleSpring.get();
        setScaleX(scale);
        setScaleY(scale);
        mTabContainer.setTranslationX(panelOffset);

        // 手指底下那一格的图标跟着放大
        int activeIndex = Math.max(0, Math.min(mItemViews.size() - 1, Math.round(value)));
        for (int i = 0; i < mItemViews.size(); i++) {
            float itemScale = 1f + (i == activeIndex ? 0.2f * press : 0f);
            mItemViews.get(i).setScaleX(itemScale);
            mItemViews.get(i).setScaleY(itemScale);
        }

        updateGlassOverlay();
    }

    private void updateGlassOverlay() {
        if (mCurrentStyle != NavigationStyle.LIQUID_GLASS) return;
        // 高光在 dispatchDraw 里画，这里只要触发一次重绘
        invalidate();
    }

    private void updatePillMetrics() {
        mTabWidthPx = mItemViews.isEmpty() ? 0 : mItemViews.get(0).getWidth();
        mTotalWidthPx = mTabContainer.getWidth();
    }

    /** 按下：整条放大、触摸高光淡入、轻微震动。 */
    private void beginPress(float x, float y) {
        if (mCurrentStyle != NavigationStyle.LIQUID_GLASS) return;
        mDragValue = mSelectedPosition;
        mTouchX = x;
        mTouchY = y;
        mPressSpring.animateTo(1f);
        mScaleSpring.animateTo(1f + (float) dpToPx(LIQUID_PRESS_SCALE_DP) / Math.max(1, getWidth()));
        mTouchAlphaSpring.animateTo(1f);
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }

    /** 抬手：复位所有交互状态，并吸附到最近的一格。 */
    private void endPress(boolean cancelled) {
        if (mCurrentStyle != NavigationStyle.LIQUID_GLASS) return;
        mPressSpring.animateTo(0f);
        mScaleSpring.animateTo(1f);
        mTouchAlphaSpring.animateTo(0f);
        mDispersionSpring.animateTo(0f);
        mPanelSpring.animateTo(0f);

        if (cancelled) {
            mIndicatorSpring.animateTo(mSelectedPosition);
            return;
        }
        int index = Math.max(0, Math.min(mItemViews.size() - 1, Math.round(mIndicatorSpring.get())));
        boolean changed = index != mSelectedPosition;
        setSelectedTab(index, changed);
        if (!changed) {
            mIndicatorSpring.animateTo(index);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mCurrentStyle != NavigationStyle.LIQUID_GLASS) {
            return super.onInterceptTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                mLastDragX = ev.getX();
                mDragging = false;
                beginPress(ev.getX(), ev.getY());
                return false;
            case MotionEvent.ACTION_MOVE:
                // 超过 touch slop 才把事件从图标手里抢过来，保证单击仍然可用
                if (!mDragging && Math.abs(ev.getX() - mDownX) > mTouchSlop) {
                    mDragging = true;
                    mDispersionSpring.animateTo(1f);
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endPress(ev.getActionMasked() == MotionEvent.ACTION_CANCEL);
                return false;
            default:
                return super.onInterceptTouchEvent(ev);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mCurrentStyle != NavigationStyle.LIQUID_GLASS) {
            return super.onTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLastDragX = ev.getX();
                mDownX = ev.getX();
                beginPress(ev.getX(), ev.getY());
                return true;
            case MotionEvent.ACTION_MOVE: {
                updatePillMetrics();
                if (mTabWidthPx <= 0) return true;
                float dx = ev.getX() - mLastDragX;
                mLastDragX = ev.getX();
                float max = Math.max(0, mItemViews.size() - 1);
                mDragValue = clamp(mDragValue + dx / mTabWidthPx, 0f, max);
                mIndicatorSpring.animateTo(mDragValue);
                // 越界时的橡皮筋位移：4dp × easeOut(越界比例)
                float fraction = clamp((ev.getX() - mDownX) / Math.max(1f, mTotalWidthPx), -1f, 1f);
                float direction = fraction < 0 ? -1f : 1f;
                float eased = 1f - (1f - Math.abs(fraction)) * (1f - Math.abs(fraction));
                mPanelSpring.animateTo(dpToPx(LIQUID_RUBBER_BAND_DP) * direction * eased);
                mTouchX = ev.getX();
                mTouchY = ev.getY();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endPress(ev.getActionMasked() == MotionEvent.ACTION_CANCEL);
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(mScrollListener);
        if (mSensorManager == null) {
            mSensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        }
        Sensor gravity = mSensorManager == null ? null : mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (gravity != null && !mTiltRegistered) {
            mSensorManager.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME);
            mTiltRegistered = true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnScrollChangedListener(mScrollListener);
        if (mTiltRegistered && mSensorManager != null) {
            mSensorManager.unregisterListener(this);
            mTiltRegistered = false;
        }
        if (mGlassBackdrop != null) {
            mGlassBackdrop.release();
        }
        super.onDetachedFromWindow();
    }

    /**
     * 重力传感器 → 高光方向。
     *
     * 重力指向地面，屏幕坐标里「世界上方」= -g，光源就来自那个方向；
     * 按 3° 量化（对应 KernelSU 里 GRAVITY_ANGLE_STEP 的做法）避免高光抖动，
     * 再偏移 -45°（对应 rememberGravityRotatedHighlight 的 extraDegrees）。
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_GRAVITY) return;
        float gx = event.values[0];
        float gy = event.values[1];
        float angle;
        if (gx * gx + gy * gy > GRAVITY_THRESHOLD_SQ) {
            angle = (float) Math.atan2(-gy, -gx);
            angle = Math.round(angle / GRAVITY_ANGLE_STEP) * GRAVITY_ANGLE_STEP;
        } else {
            angle = (float) (-Math.PI / 2.0);
        }
        angle -= (float) (Math.PI / 4.0);
        if (angle != mLightAngle) {
            mLightAngle = angle;
            updateGlassOverlay();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---------- 液态玻璃的光学高光（直接画，不新增子 View） ----------

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (mCurrentStyle == NavigationStyle.LIQUID_GLASS) {
            // 先画自己抓的背景快照（AGSL 模糊+折射/色散），再画图标，
            // 最后用渐变高光补一层镜片高光
            if (mGlassBackdrop != null) {
                mGlassBackdrop.capture(this, getWidth(), getHeight());
                mGlassBackdrop.draw(canvas, getWidth(), getHeight(), mLiquidState.radius,
                    mLightAngle, mTouchX, mTouchY, mTouchAlphaSpring.get(),
                    mPressSpring.get(), mDispersionSpring.get(), isNight());
            }
        }
        super.dispatchDraw(canvas);
        if (mCurrentStyle == NavigationStyle.LIQUID_GLASS) {
            drawGlassHighlights(canvas);
        }
    }

    /** 底栏背后要采样的那一层（通常是 ViewPager）。 */
    public void setBackdropSource(View source) {
        if (mGlassBackdrop != null) {
            mGlassBackdrop.setSource(source);
        }
    }

    private final ViewTreeObserver.OnScrollChangedListener mScrollListener = () -> {
        if (mGlassBackdrop != null && mCurrentStyle == NavigationStyle.LIQUID_GLASS) {
            mGlassBackdrop.invalidateBackdrop();
            invalidate();
        }
    };

    /**
     * 玻璃边缘的镜片高光 / 触摸高光 / 按下提亮 / 拖动色散。
     *
     * 对应 KernelSU FloatingBottomBar 里的 Highlight(BloomStroke)、
     * InteractiveHighlight 与 lens(chromaticAberration)；用渐变近似，
     * 换来的是 HyperOS 原生背景模糊能正常工作。
     */
    private void drawGlassHighlights(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        float radius = mLiquidState.radius;
        mGlassPaint.setShader(null);
        mGlassPaint.setStyle(Paint.Style.FILL);
        mGlassPaint.setBlendMode(BlendMode.PLUS);

        // 双峰镜片高光：主光源一侧 + 对侧弱一些
        drawSpecularArc(canvas, width, height, radius, mLightAngle, 0.9f);
        drawSpecularArc(canvas, width, height, radius, mLightAngle + (float) Math.PI, 0.4f);

        float press = mPressSpring.get();
        if (press > 0.001f) {
            mGlassPaint.setColor(Color.WHITE);
            mGlassPaint.setAlpha((int) (255 * 0.06f * press));
            canvas.drawRoundRect(0f, 0f, width, height, radius, radius, mGlassPaint);
        }

        float touchAlpha = mTouchAlphaSpring.get();
        if (touchAlpha > 0.001f) {
            float glowRadius = Math.max(width, height) * 0.8f;
            mGlassPaint.setShader(new RadialGradient(
                clamp(mTouchX, 0f, width), clamp(mTouchY, 0f, height), glowRadius,
                new int[]{0x59FFFFFF, 0x00FFFFFF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            mGlassPaint.setAlpha((int) (255 * touchAlpha));
            canvas.drawRoundRect(0f, 0f, width, height, radius, radius, mGlassPaint);
            mGlassPaint.setShader(null);
        }

        float dispersion = mDispersionSpring.get();
        if (dispersion > 0.01f) {
            mGlassPaint.setShader(new SweepGradient(width / 2f, height / 2f,
                new int[]{0x40FF3B30, 0x40FFCC00, 0x4034C759, 0x400A84FF, 0x40AF52DE, 0x40FF3B30},
                null));
            mGlassPaint.setStyle(Paint.Style.STROKE);
            mGlassPaint.setStrokeWidth(dpToPx(2));
            mGlassPaint.setAlpha((int) (255 * 0.45f * clamp(dispersion, 0f, 1f)));
            canvas.drawRoundRect(1f, 1f, width - 1f, height - 1f, radius, radius, mGlassPaint);
            mGlassPaint.setShader(null);
            mGlassPaint.setStyle(Paint.Style.FILL);
        }

        mGlassPaint.setBlendMode(BlendMode.SRC_OVER);
    }

    private void drawSpecularArc(Canvas canvas, float width, float height, float radius,
                                 float angle, float strength) {
        float centerX = width / 2f + (float) Math.cos(angle) * width * 0.5f;
        float centerY = height / 2f + (float) Math.sin(angle) * height * 0.5f;
        mGlassPaint.setShader(new RadialGradient(centerX, centerY,
            Math.max(width, height) * 0.8f,
            new int[]{0x38FFFFFF, 0x00FFFFFF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        mGlassPaint.setAlpha((int) (255 * strength));
        canvas.drawRoundRect(0f, 0f, width, height, radius, radius, mGlassPaint);
        mGlassPaint.setShader(null);
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
        /** true 时叠加 AGSL 玻璃层（液态玻璃样式）。 */
        boolean glassOverlay;
        int containerWidth, containerHeight, containerGravity, containerPaddingH;

        int itemWidth, itemHeight, itemPaddingH;
        float itemWeight;
        boolean showText;
    }
}
