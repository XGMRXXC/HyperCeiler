package com.sevtinge.hyperceiler.home;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.sevtinge.hyperceiler.R;
import com.sevtinge.hyperceiler.common.log.AndroidLog;
import com.sevtinge.hyperceiler.common.log.LogStatusManager;
import com.sevtinge.hyperceiler.home.adapter.HeaderAdapter;
import com.sevtinge.hyperceiler.home.adapter.ProxyHeaderViewAdapter;
import com.sevtinge.hyperceiler.home.banner.BannerCallback;
import com.sevtinge.hyperceiler.home.banner.HomePageBannerManager;
import com.sevtinge.hyperceiler.home.base.BasePreferenceFragment;
import com.sevtinge.hyperceiler.home.order.OnCompleteCallBack;
import com.sevtinge.hyperceiler.home.tips.HomePageTipHelper;
import com.sevtinge.hyperceiler.home.utils.HeaderManager;
import com.sevtinge.hyperceiler.home.utils.IntentUtils;
import com.sevtinge.hyperceiler.home.utils.SearchHistorySPUtils;
import com.sevtinge.hyperceiler.search.SearchHelper;
import com.sevtinge.hyperceiler.search.SearchResultAdapter;
import com.sevtinge.hyperceiler.search.data.ModEntity;
import com.sevtinge.hyperceiler.search.widget.FlowLayout;
import com.sevtinge.hyperceiler.ui.HomePageActivity;
import com.sevtinge.hyperceiler.utils.DialogHelper;
import com.sevtinge.hyperceiler.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import fan.core.utils.MiuiBlurUtils;
import fan.internal.utils.ViewUtils;
import fan.nestedheader.widget.NestedHeaderLayout;
import fan.recyclerview.card.CardItemDecoration;
import fan.recyclerview.widget.RecyclerView;
import fan.theme.token.ContainerToken;
import fan.view.ActionModeAnimationListener;
import fan.view.SearchActionMode;
import fan.viewpager.widget.ViewPager;

public class HomePageFragment extends BasePreferenceFragment implements OnCompleteCallBack {

    private static final String TAG = "HomePageFragment";
    private static final long TIP_AUTO_REFRESH_INTERVAL_MS = 30_000L;

    public static int getHomeHeadersResourceId() {
        return R.xml.settings_header;
    }

    private volatile boolean isClicking = false;
    private volatile boolean mIsInActionMode;
    private volatile boolean mIsScrollEnableForListView = true;

    private BannerCallback mBannerCallback;

    private View mAnchorView;

    private NestedHeaderLayout mNestedHeaderLayout;

    private RecyclerView mListView;

    private String mSearchText;
    private String mSearchHistoryText;
    private List<String> mSearchHistoryLists = new ArrayList<>();
    private SearchResultAdapter mSearchAdapter;

    private SearchHandler mSearchHandler;

    private EditText mSearchInput;

    private View mSearchLoadingView;

    private FlowLayout mSearchHistoryFl;
    private RecyclerView mSearchResultListView;
    private NestedScrollView mSearchListLayout;

    private LinearLayout mSearchResultLinearLayout;

    private HeaderAdapter mHeaderAdapter;
    private ProxyHeaderViewAdapter mProxyAdapter;
    private String mLastHomeStateSignature;

    private SearchHistorySPUtils mSearchHistorySPUtils;
    private HandlerThread mSearchThread;
    private boolean mIsTipsAutoRefreshActive;

    private final Handler mTipsHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTipsAutoTask = new Runnable() {
        @Override
        public void run() {
            if (!mIsTipsAutoRefreshActive) {
                return;
            }
            try {
                Context context = getSafeContext();
                if (context != null) {
                    HomePageTipHelper.refreshCurrentTip(context);
                }
            } finally {
                scheduleNextTipRefresh();
            }
        }
    };

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefreshHeaderTask = this::refreshHeader;
    private final TextWatcher mTextWatcher = new TextWatcher() {

        @Override
        public void afterTextChanged(Editable s) {
            String query = s.toString().trim();
            updateSearch(query, false);
            if (!TextUtils.isEmpty(query)) {
                mSearchHistoryText = query;
            }
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            String query = (s == null) ? "" : s.toString().trim();
            mSearchText = query;
            if (TextUtils.isEmpty(query)) {
                if (mSearchResultListView != null) {
                    mSearchResultListView.setVisibility(View.GONE);
                }
                setSearchHistoryVisiable(mSearchHistoryLists != null && !mSearchHistoryLists.isEmpty());
            } else {
                if (mSearchResultListView != null) {
                    mSearchResultListView.setVisibility(View.VISIBLE);
                }
                if (mListView != null) {
                    mListView.setVisibility(View.GONE);
                }
            }
           refreshSearchResult();
        }
    };

    private final SearchActionMode.Callback mSearchCallback = new SearchActionMode.Callback() {
        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return false;
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            getSwitchManager().hide();
            SearchActionMode searchActionMode = getSearchActionMode((SearchActionMode) mode);
            searchActionMode.setSearchMaskAlwaysHidden(true);
            searchActionMode.setAnchorView(mAnchorView);
            searchActionMode.setAnimateView(mListView);
            searchActionMode.setResultView(mSearchResultLinearLayout);
            searchActionMode.setAnchorApplyExtraPaddingByUser(true);

            mSearchInput = searchActionMode.getSearchInput();
            mSearchInput.setImeOptions(3);
            mSearchInput.addTextChangedListener(mTextWatcher);
            mSearchInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_NEXT) {
                    return true;
                }
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    processSearchHistory(mSearchHistoryText);
                    return true;
                }
                return false;
            });
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            // 退出搜索页时必须重建底栏：液态玻璃的采样纹理、尺寸都还停在搜索界面，
            // 只把它显示回来就是重影。但**不能在这里立刻重建**：
            //   1) 此刻列表还没恢复可见、键盘也还在收起，容器几何仍是搜索态的，
            //      重建出来的底栏会按那个几何固定下来（表现就是底栏整体偏移、变高）；
            //   2) 搜索期间的布局抖动会让 SwitchMediator 把选中项改到别的页，
            //      立刻重建就会用这个错的选中项，把玻璃的折射镜片画到中间那颗图标上
            //      （实测就是这样：镜片从"主页"跑到了"齿轮"）。
            // 所以这里只恢复页面状态，等布局落定后再对齐选中项并重建，
            // 见 rebuildGlassBarWhenSettled()。
            mIsInActionMode = false;
            if (mSearchInput != null) {
                mSearchInput.removeTextChangedListener(mTextWatcher);
            }
            mSearchInput = null;
            if (mSearchResultListView != null) {
                mSearchResultListView.stopScroll();
                mSearchResultListView.setVisibility(View.GONE);
            }
            if (mSearchLoadingView != null) {
                mSearchLoadingView.setVisibility(View.GONE);
            }
            setSearchHistoryVisiable(false);
            if (mListView != null) {
                mListView.setVisibility(View.VISIBLE);
            }
            mSearchText = null;
            Context context = getSafeContext();
            mSearchAdapter.refresh(null, "", context != null && isChina(context));
            if (mSearchHandler != null) {
                mSearchHandler.removeMessages(1);
            }
            // 页面状态已经恢复，交给下一帧去重建底栏。
            rebuildGlassBarWhenSettled(0);
        }

        /**
         * 退出搜索后，等布局真正落定再重建底栏。
         *
         * 逐帧轮询而不是直接 post 一次：键盘收起是带动画的，只有等 IME 真正不可见，
         * 容器高度才回到常态，此时重建出来的底栏尺寸才是对的。超过 20 帧就不再等，
         * 避免某些机型 insets 一直报"键盘可见"时底栏永远不回来。
         */
        private void rebuildGlassBarWhenSettled(int attempt) {
            View anchor = mListView != null ? mListView : mAnchorView;
            if (anchor == null) {
                finishGlassBarRebuild();
                return;
            }
            anchor.post(() -> {
                if (isImeVisible(anchor) && attempt < 20) {
                    rebuildGlassBarWhenSettled(attempt + 1);
                    return;
                }
                finishGlassBarRebuild();
            });
        }

        private boolean isImeVisible(View view) {
            try {
                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(view);
                return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
            } catch (Throwable t) {
                return false;
            }
        }

        private void finishGlassBarRebuild() {
            // 选中项按 ViewPager 的真实页重新对齐，再重建 —— 顺序不能反，
            // 重建时会把当前选中态直接画进玻璃里。
            if (getActivity() instanceof HomePageActivity) {
                ViewPager pager = ((HomePageActivity) getActivity()).mViewPager;
                if (pager != null) {
                    getSwitchManager().setSelectedPosition(pager.getCurrentItem(), false);
                }
            }
            android.util.Log.w("GlassReload", "finishGlassBarRebuild -> recreateGlassBar");

            getSwitchManager().recreateGlassBar();
            getSwitchManager().show();
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            mIsInActionMode = true;
            return false;
        }
    };

    @NonNull
    private SearchActionMode getSearchActionMode(SearchActionMode mode) {
        mode.addAnimationListener(new ActionModeAnimationListener() {

            @Override
            public void onStart(boolean z) {
                mIsScrollEnableForListView = false;
            }

            @Override
            public void onUpdate(boolean z, float f) {
                if (mSearchListLayout != null) {
                    mSearchListLayout.setAlpha(0.0f);
                }
            }

            @Override
            public void onStop(boolean z) {
                mIsScrollEnableForListView = !z;
                if (mSearchListLayout != null) {
                    mSearchListLayout.setAlpha(1.0f);
                }
                setSearchHistoryVisiable(z && mSearchHistoryLists != null && !mSearchHistoryLists.isEmpty());
                isClicking = false;
            }
        });
        return mode;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setThemeRes(R.style.HomeNavigatorContentTheme);

        mSearchHistorySPUtils = new SearchHistorySPUtils(requireContext(), "search_history");
        mSearchAdapter = new SearchResultAdapter();
        mSearchAdapter.setOnItemClickListener((view, ad) -> {
            processSearchHistory(mSearchHistoryText);
        });

        if (mBannerCallback == null) {
            mBannerCallback = new BannerCallback();
        }

        LogStatusManager.onHealthCheckDone(this::scheduleHeaderRefresh);
    }

    @Override
    protected int getHeadersResourceId() {
        return getHomeHeadersResourceId();
    }

    @Override
    public void onResume() {
        super.onResume();
        String currentSignature = HeaderManager.computeHomeStateSignature(getContext());
        if (!Objects.equals(mLastHomeStateSignature, currentSignature)) {
            buildAdapter();
        }
        if (HomePageBannerManager.needsRefresh()) {
            scheduleHeaderRefresh();
        }
        startTipAutoRefresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopTipAutoRefresh();
    }

    @Override
    public View onInflateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.home_settings, container, false);
        mSearchResultLinearLayout = v.findViewById(R.id.search_result_ll);
        mListView = v.findViewById(R.id.scroll_headers);
        mListView.setFocusable(true);
        mListView.setFocusableInTouchMode(true);
        mListView.setItemAnimator(null);
        mListView.setHasFixedSize(true);

        mSearchResultListView = v.findViewById(R.id.search_result);
        mSearchResultListView.setFocusable(true);
        mSearchResultListView.setFocusableInTouchMode(true);
        mSearchResultListView.setItemAnimator(null);
        mSearchLoadingView = v.findViewById(R.id.search_loading);
        mSearchListLayout = v.findViewById(R.id.search_history);
        mSearchHistoryFl = v.findViewById(R.id.search_history_fl);

        TextView searchHistoryClearTv = v.findViewById(R.id.search_history_clear_tv);
        searchHistoryClearTv.setOnClickListener(view -> {
            mSearchHistorySPUtils.removeDateList("tagSearchHistory");
            mSearchHistoryLists.clear();
            mSearchHistoryFl.removeAllViews();
            setSearchHistoryVisiable(false);
        });
        initSearchHistoryView();
        return v;
    }


    public void updateSearch(String query, boolean z) {
        if (!Objects.equals(mSearchText, query) || z) {
            ensureSearchHandler();
            mSearchHandler.removeMessages(1);
            mSearchHandler.obtainMessage(1, query).sendToTarget();
            if (TextUtils.isEmpty(mSearchText)) {
                mSearchLoadingView.setVisibility(View.VISIBLE);
            }
            mSearchText = query;
        }
    }

    public void setSearchHistoryVisiable(boolean visiable) {
        if (mSearchListLayout != null) {
            mSearchListLayout.setVisibility(visiable ? View.VISIBLE : View.GONE);
            if (visiable && mListView != null) {
                mListView.setVisibility(View.GONE);
            }
        }
    }

    public void processSearchHistory(String query) {
        if (!TextUtils.isEmpty(query)) {
            ThreadUtils.postOnBackgroundThread(() -> {
                List<String> searchHistory = mSearchHistorySPUtils.loadDataList("tagSearchHistory");
                if (!searchHistory.isEmpty()) {
                    mSearchHistoryLists.clear();
                    mSearchHistoryLists.addAll(searchHistory);
                }
                if (!mSearchHistoryLists.contains(query)) {
                    if (mSearchHistoryLists.size() >= 15) {
                        mSearchHistoryLists.remove(0);
                        mSearchHistoryLists.add(mSearchHistoryLists.size(), query);
                    } else {
                        mSearchHistoryLists.add(query);
                    }
                } else {
                    int i = -1;
                    for (int i2 = 0; i2 < mSearchHistoryLists.size(); i2++) {
                        if (query.equals(mSearchHistoryLists.get(i2))) {
                            i = i2;
                        }
                    }
                    mSearchHistoryLists.remove(i);
                    mSearchHistoryLists.add(mSearchHistoryLists.size(), query);
                }
                mSearchHistorySPUtils.saveDataList("tagSearchHistory", mSearchHistoryLists);
                mMainHandler.post(() -> {
                    if (isFragmentUiAvailable()) {
                        initSearchHistoryView();
                    }
                });
            });
        }
    }

    private void initSearchHistoryView() {
        try {
            Context context = getSafeContext();
            if (context == null || mSearchHistoryFl == null) {
                return;
            }
            mSearchHistoryFl.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(context);
            mSearchHistoryLists = mSearchHistorySPUtils.loadDataList("tagSearchHistory");
            if (!mSearchHistoryLists.isEmpty()) {
                for (int size = mSearchHistoryLists.size() - 1; size >= 0; size--) {
                    TextView textView = (TextView) inflater.inflate(R.layout.search_history_tv, mSearchHistoryFl, false);
                    String str = mSearchHistoryLists.get(size);
                    if (textView != null) {
                        textView.setText(str);
                        textView.setOnClickListener(view -> {
                            if (mSearchInput != null) {
                                mSearchInput.setText(textView.getText());
                                mSearchInput.setSelection(textView.length());
                                mSearchListLayout.setVisibility(View.GONE);
                            }
                        });
                        mSearchHistoryFl.addView(textView);
                    }
                }
            }
        } catch (Exception e) {
            AndroidLog.e(TAG, "initSearchHistoryView failed", e);
        }
    }

    @Override
    public void onViewInflated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewInflated(view, savedInstanceState);
        getActionBar().setTitle(com.sevtinge.hyperceiler.core.R.string.app_name);
        setExtraHorizontalPaddingEnable(true);
        mNestedHeaderLayout = view.findViewById(R.id.nestedheaderlayout);
        registerCoordinateScrollView(mNestedHeaderLayout);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mAnchorView = view.findViewById(R.id.header_view);
        HomePageBannerManager.setRefreshCallback(this::scheduleHeaderRefresh);
        mAnchorView.setOnClickListener(v -> {
            if (isClicking || mIsInActionMode) return;
            isClicking = true;
            ensureSearchHandler();
            if (mListView != null) {
                mListView.setVisibility(View.GONE);
            }
            startActionMode(mSearchCallback);
        });
        TextView textView = mAnchorView.findViewById(android.R.id.input);
        textView.setHint(com.sevtinge.hyperceiler.core.R.string.search);


        mNestedHeaderLayout = view.findViewById(R.id.nestedheaderlayout);
        Context context = getSafeContext();
        boolean effectEnabled = context != null && MiuiBlurUtils.isEffectEnable(context);
        mNestedHeaderLayout.setOverlayMode(effectEnabled);
        mNestedHeaderLayout.setEnableBlur(effectEnabled);
    }

    @Override
    public void updateHeaderList(List<Header> headers) {
        Context context = getSafeContext();
        if (context != null) {
            HeaderManager.updateHeaderDisplayStates(context, headers);
        }
    }

    private void refreshHeader() {
        Context context = getSafeContext();
        if (context == null || mProxyAdapter == null || mListView == null) {
            return;
        }

        mListView.suppressLayout(true);
        try {
            HomePageHeaderHelper.refreshAll(context, mProxyAdapter, mBannerCallback);
        } finally {
            mListView.suppressLayout(false);
        }
    }

    private void scheduleHeaderRefresh() {
        mMainHandler.removeCallbacks(mRefreshHeaderTask);
        if (mListView != null) {
            mListView.removeCallbacks(mRefreshHeaderTask);
            mListView.post(mRefreshHeaderTask);
        } else {
            mMainHandler.post(mRefreshHeaderTask);
        }
    }

    private void startTipAutoRefresh() {
        mIsTipsAutoRefreshActive = true;
        scheduleNextTipRefresh();
    }

    private void stopTipAutoRefresh() {
        mIsTipsAutoRefreshActive = false;
        mTipsHandler.removeCallbacks(mTipsAutoTask);
    }

    private void scheduleNextTipRefresh() {
        mTipsHandler.removeCallbacks(mTipsAutoTask);
        if (mIsTipsAutoRefreshActive) {
            mTipsHandler.postDelayed(mTipsAutoTask, TIP_AUTO_REFRESH_INTERVAL_MS);
        }
    }

    @Override
    public void buildAdapter() {
        super.buildAdapter();
        Context context = getSafeContext();
        if (context == null || mListView == null || mSearchResultListView == null) {
            return;
        }

        List<Header> displayHeaders = HeaderManager.getDisplayHeaders(context, mHeaders);
        mLastHomeStateSignature = HeaderManager.computeHomeStateSignature(context);

        mHeaderAdapter = new HeaderAdapter(this, displayHeaders);
        mHeaderAdapter.setHasStableIds(true);
        mProxyAdapter = new ProxyHeaderViewAdapter(mHeaderAdapter);

        LinearLayoutManager manager = new LinearLayoutManager(context) {
            @Override
            public boolean canScrollVertically() {
                return mIsScrollEnableForListView && super.canScrollVertically();
            }
        };
        manager.setOrientation(LinearLayoutManager.VERTICAL);

        mListView.setLayoutManager(manager);
        mListView.setAdapter(mProxyAdapter);
        if (mListView.getItemDecorationCount() == 0) {
            CardItemDecoration decoration = new CardItemDecoration(getActivity());
            decoration.setCardMarginTop(context.getResources().getDimensionPixelSize(R.dimen.settings_banner_ly_padding_top_and_bottom));
            mListView.addItemDecoration(decoration);
        }
        mProxyAdapter.updateGroupInfo();

        // 预加载所有需要图标的 header，全部完成后再刷新列表
        List<String> packageNames = new ArrayList<>();
        for (Header h : displayHeaders) {
            if (h.fragment != null && !TextUtils.isEmpty(h.summary) && h.id != R.id.various) {
                packageNames.add(h.summary.toString());
            }
        }
        if (!packageNames.isEmpty()) {
            int headerIconSize = context.getResources().getDimensionPixelSize(R.dimen.header_icon_size);
            IconTitleLoader.preloadAll(context.getApplicationContext(), packageNames, headerIconSize, () ->
                runOnUiThreadIfAlive(() -> {
                    if (mHeaderAdapter != null) {
                    mHeaderAdapter.notifyDataSetChanged();
                    }
                })
            );
        }

        if (!mIsInActionMode) {
            mSearchResultListView.setVisibility(View.GONE);
            LinearLayoutManager layoutManager = new LinearLayoutManager(getActivity());
            layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
            mSearchResultListView.setLayoutManager(layoutManager);
            mSearchResultListView.setAdapter(mSearchAdapter);
            if (mSearchResultListView.getItemDecorationCount() == 0) {
                mSearchResultListView.addItemDecoration(new CardItemDecoration(getActivity()));
            }
            mSearchAdapter.updateGroupInfo();
        } else if (!TextUtils.isEmpty(mSearchText)) {
            refreshSearchResult();
        }
        refreshHeader();
    }

    public void refreshSearchResult() {
        if (!TextUtils.isEmpty(mSearchText)) {
            ensureSearchHandler();
            mSearchHandler.removeMessages(1);
            mSearchHandler.obtainMessage(1, mSearchText).sendToTarget();
        }
    }

    public void ensureSearchHandler() {
        if (mSearchThread == null) {
            mSearchThread = new HandlerThread("SettingsFragment-Search");
            mSearchThread.start();
            mSearchHandler = new SearchHandler(mSearchThread.getLooper());
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.home_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.function_setting) {
            IntentUtils.goToCustomOrderDialog(requireActivity(), this);
        } else if (itemId == R.id.quick_restart) {
            DialogHelper.showRestartDialog(requireContext());
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void refresh() {
        buildAdapter();
    }

    @Override
    public void onDismiss() {

    }

    private class SearchHandler extends Handler {
        public SearchHandler(@NonNull Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            try {
                if (msg.what == 1) {
                    String query = (String) msg.obj;
                    Context context = getSafeContext();
                    if (mIsInActionMode && context != null) {
                        mMainHandler.post(() -> {
                            if (mIsInActionMode && isFragmentUiAvailable() && mSearchLoadingView != null) {
                                mSearchLoadingView.setVisibility(View.VISIBLE);
                            }
                        });
                        List<ModEntity> results = SearchHelper.search(context, query);

                        boolean isChinaLocale = isChina(context);

                        mMainHandler.post(() -> {
                            if (mIsInActionMode && isFragmentUiAvailable() && mSearchAdapter != null
                                && mSearchLoadingView != null && mListView != null) {
                                mSearchAdapter.refresh(results, query, isChinaLocale);
                                mSearchLoadingView.setVisibility(View.GONE);
                                mListView.setVisibility(View.GONE);
                                setSearchHistoryVisiable(TextUtils.isEmpty(query) && mSearchHistoryLists != null && mSearchHistoryLists.size() > 0);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                AndroidLog.e(TAG, "SearchHandler handleMessage failed", e);
            }

        }
    }

    public boolean isChina(Context context) {
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        return locale.getLanguage().contains(new Locale("zh").getLanguage());
    }

    @Override
    public void onContentInsetChanged(Rect rect) {
        super.onContentInsetChanged(rect);
        if (mListView != null) {
            ViewUtils.RelativePadding relativePadding = new ViewUtils.RelativePadding(mListView);
            boolean isLayoutRtl = ViewUtils.isLayoutRtl(mListView);
            relativePadding.start += isLayoutRtl ? rect.right : rect.left;
            relativePadding.end += isLayoutRtl ? rect.left : rect.right;
            relativePadding.bottom = rect.top;
            relativePadding.applyToView(mListView);
        }
        mSearchResultLinearLayout.setPadding(
            mSearchResultLinearLayout.getPaddingLeft(),
            rect.top,
            mSearchResultLinearLayout.getPaddingRight(),
            mSearchResultLinearLayout.getPaddingBottom()
        );
    }

    @Override
    public void onExtraPaddingChanged(int extraHorizontalPadding) {
        super.onExtraPaddingChanged(extraHorizontalPadding);
        int margin = (int) (extraHorizontalPadding + (ContainerToken.PADDING_BASE_DP * 3 * getResources().getDisplayMetrics().density));
        setExtraPadding(mListView, margin);
        setExtraPadding(mSearchResultListView, margin);
        if (mAnchorView != null) {
            FrameLayout frameLayout = mAnchorView.findViewById(fan.appcompat.R.id.search_mode_stub);
            if (frameLayout != null) {
                frameLayout.setPaddingRelative(margin, frameLayout.getPaddingTop(), margin, frameLayout.getPaddingBottom());
            }
        }
        if (mSearchListLayout != null) {
            mSearchListLayout.setPaddingRelative(margin, mSearchListLayout.getPaddingTop(), margin, mSearchListLayout.getPaddingBottom());
        }
    }

    private void setExtraPadding(RecyclerView recyclerView, int margin) {
        if (recyclerView == null || recyclerView.getItemDecorationCount() == 0) {
            return;
        }
        RecyclerView.ItemDecoration itemDecoration = recyclerView.getItemDecorationAt(0);
        if (itemDecoration instanceof CardItemDecoration cardItemDecoration) {
            cardItemDecoration.setCardMarginStart(margin);
            cardItemDecoration.setCardMarginEnd(margin);
            if (recyclerView.getAdapter() != null) {
                recyclerView.getAdapter().notifyDataSetChanged();
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        HomePageBannerManager.setRefreshCallback(null);
        stopTipAutoRefresh();
        mTipsHandler.removeCallbacksAndMessages(null);
        mMainHandler.removeCallbacks(mRefreshHeaderTask);
        if (mListView != null) {
            mListView.removeCallbacks(mRefreshHeaderTask);
        }
        if (mSearchHandler != null) {
            mSearchHandler.removeCallbacksAndMessages(null);
        }
        if (mSearchThread != null) {
            mSearchThread.quitSafely();
            mSearchThread = null;
            mSearchHandler = null;
        }
    }

}
