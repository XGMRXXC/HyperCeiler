package com.sevtinge.hyperceiler.ui;

import static android.os.Process.killProcess;
import static android.os.Process.myPid;
import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.SUPPORT_FULL;
import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.SUPPORT_PARTIAL;
import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.getAndroidVersion;
import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.getHyperOSVersion;
import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.getSmallVersion;
import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.getVersionListText;
import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isVersionListed;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.lifecycle.LiveData;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.sevtinge.hyperceiler.R;
import com.sevtinge.hyperceiler.about.AboutPageFragment;
import com.sevtinge.hyperceiler.about.AboutSettingsFragment;
import com.sevtinge.hyperceiler.common.log.AndroidLog;
import com.sevtinge.hyperceiler.common.utils.AppSettingsStore;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.common.utils.shell.IResult;
import com.sevtinge.hyperceiler.dashboard.base.ActivityCallback;
import com.sevtinge.hyperceiler.home.HomePageFragment;
import com.sevtinge.hyperceiler.home.IconTitleLoader;
import com.sevtinge.hyperceiler.home.adapter.HomeContentAdapter;
import com.sevtinge.hyperceiler.home.manager.PageDecorator;
import com.sevtinge.hyperceiler.home.task.AppInitializer;
import com.sevtinge.hyperceiler.home.widget.NavigationStyle;
import com.sevtinge.hyperceiler.home.widget.SwitchManager;
import com.sevtinge.hyperceiler.home.widget.SwitchMediator;
import com.sevtinge.hyperceiler.provision.utils.NoticeProvider;
import com.sevtinge.hyperceiler.provision.utils.ProvisionManager;
import com.sevtinge.hyperceiler.settings.SettingsFragment;
import com.sevtinge.hyperceiler.settings.SettingsPageFragment;
import com.sevtinge.hyperceiler.utils.NoticeProcessor;
import com.sevtinge.hyperceiler.utils.PersistConfig;

import java.util.ArrayList;
import java.util.List;

import fan.appcompat.app.AlertDialog;
import fan.appcompat.app.AppCompatActivity;
import java.util.ArrayDeque;

import fan.preference.PreferenceFragment;
import fan.provider.Settings;
import fan.provision.OobeUtils;
import fan.viewpager.widget.ViewPager;
import fan.viewpager2.widget.ViewPager2;

public class HomePageActivity extends AppCompatActivity
    implements ActivityCallback, IResult,
    PreferenceFragment.OnPreferenceStartFragmentCallback {

    private static final String STATE_CURRENT_PAGE = "home_current_page";

    public ViewPager mViewPager;
    public HomeContentAdapter mContentAdapter;

    public SwitchManager mSwitchManager;
    private boolean mIsUnsupportedVersionExiting;

    /** 上一次同步底栏时的页面几何，用来判断"布局真的变了"。 */
    private int mLastPagerHeight = -1;
    private int mLastPagerTop = -1;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(com.sevtinge.hyperceiler.utils.LanguageHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (PersistConfig.isAprilFoolsThemeView) setTheme(R.style.HomePageAprilFoolsTheme);
        if (!OobeUtils.isProvisioned(this) && !OobeUtils.isDebugOobeMode(this)) {
            startActivity(new Intent(this, SplashActivity.class));
            finish();
            return;
        }
        if (!isVersionListed()) {
            showUnsupportedVersionDialog();
            return;
        }
        // Activity 启动阶段，绑定 UI 任务（如签名校验弹窗、公告展示）
        AppInitializer.initOnActivityCreate(this, this);
        setContentView(R.layout.activity_home);
        setupNavigation();
        restoreCurrentPage(savedInstanceState);

        ProvisionManager.setProvider(context -> {
            NoticeProcessor.NoticeResult result = NoticeProcessor.process(context);
            List<Integer> list = new ArrayList<>();
            if (result != null) {
                list.add(result.protocolVersion());
                list.add(result.privacyVersion());
            }
            return list;
        });
    }

    private void setupNavigation() {
        mSwitchManager = new SwitchManager(findViewById(R.id.container));

        NavigationStyle initialStyle = NavigationStyle.fromIndex(AppSettingsStore.getNavStyleIndex(this));
        mSwitchManager.addSwitchView(R.menu.bottom_nav_menu, initialStyle);

        // 底栏样式是唯一的事实来源，设置页改完会直接回调过来（见 SettingsFragment），
        // 这里不再监听旧的 settings_float_nav 布尔键当触发器：那个键在真机
        // Settings.Global 里往往不存在，LiveData 每次启动都会用默认值 false
        // 触发一次，把用户选的样式覆盖成贴地底栏。

        mViewPager = findViewById(R.id.vp_fragments);
        rebuildContentPages();
        // 液态玻璃底栏要采样"当前页"的内容，等布局完成后再解析一次
        mViewPager.post(this::updateBackdropSource);
        watchLayoutChanges();
        new SwitchMediator(mSwitchManager, mViewPager, true).attach();
    }

    /**
     * 键盘弹出/收起、分屏、旋转都会改变页面几何，而底栏只在少数时机（启动、换页、
     * onResume）同步过。少了这条，玻璃会继续显示上一次采样到的画面 —— 表现就是
     * 首页点搜索再退出来后底栏出现重影（玻璃里残留搜索页/键盘那层内容）。
     *
     * 用全局布局监听而不是 insets 监听：后者会覆盖系统的默认 insets 处理。
     * 只在页面几何真的变了时才重同步，避免自己触发自己。
     */
    private void watchLayoutChanges() {
        View root = findViewById(android.R.id.content);
        if (root == null) return;
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (mViewPager == null || mSwitchManager == null) return;
            int height = mViewPager.getHeight();
            int top = mViewPager.getTop();
            if (height == mLastPagerHeight && top == mLastPagerTop) return;
            mLastPagerHeight = height;
            mLastPagerTop = top;
            mSwitchManager.setSelectedPosition(mViewPager.getCurrentItem(), false);
            mViewPager.post(this::updateBackdropSource);
        });
    }

    /**
     * 把底栏的采样源指向 pager。
     *
     * 具体采哪一页由 NativeViewBackdrop 在绘制时解析：本项目用的是
     * fan.viewpager.widget.ViewPager（v1），页面就是它的直接子视图、左右并排，
     * 按 scrollX 即可定位当前页。放在绘制侧做是为了和绘制用同一个对象 ——
     * 之前在 Activity 侧解析再传下去（FragmentManager 拿不到页面、按 ViewPager2
     * 的 ViewHolder 也取不到），两边不同步，采到的始终是主页内容。
     */
    private void updateBackdropSource() {
        if (mSwitchManager == null || mViewPager == null) return;
        mSwitchManager.setBackdropView(mViewPager);
    }

    private void rebuildContentPages() {
        mContentAdapter = new HomeContentAdapter(this);
        mContentAdapter.addFragment(new HomePageFragment());
        mContentAdapter.addFragment(new SettingsPageFragment());
        mContentAdapter.addFragment(new AboutPageFragment());

        mViewPager.setAdapter(mContentAdapter);
        mViewPager.setOffscreenPageLimit(3);
    }

    public void reloadPagesForLanguageChange() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // 首页应用名和页面上下文都依赖当前 locale，直接重建能避免旧 Context 残留。
        IconTitleLoader.clearLabelCache();
        recreate();
    }

    private void restoreCurrentPage(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null || mViewPager == null) {
            return;
        }
        int currentItem = savedInstanceState.getInt(STATE_CURRENT_PAGE, 0);
        mViewPager.setCurrentItem(currentItem, false);
        if (mSwitchManager != null) {
            mSwitchManager.setSelectedPosition(currentItem, false);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
    }

    public SwitchManager getSwitchManager() {
        return mSwitchManager;
    }

    @Override
    public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller, @NonNull Preference pref) {
        if (caller instanceof SettingsFragment || caller instanceof AboutSettingsFragment) {
            onStartSubSettingsForArguments(this, pref, false);
            return true;
        }
        return false;
    }

    public class ViewPagerChangeListener extends ViewPager2.OnPageChangeCallback {
        @Override
        public void onPageSelected(int position) {
            super.onPageSelected(position);
            mSwitchManager.setSelectedPosition(position, true);
            // 换页后重新解析采样源，否则玻璃还是上一页的内容
            if (mViewPager != null) {
                mViewPager.post(HomePageActivity.this::updateBackdropSource);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PageDecorator.onResume();
        // 备份恢复等外部改动可能在别处写入样式，回到前台时对齐一次
        if (mSwitchManager != null) {
            mSwitchManager.setStyle(NavigationStyle.fromIndex(AppSettingsStore.getNavStyleIndex(this)));
            if (mViewPager != null) {
                // 从二级页面返回时，底栏的选中项要跟随当前页面。
                // setStyle() 用的是它自己记的 mSelectedPosition，返回过程中可能已经
                // 被重置成 0，于是底栏跳回主页而页面没变；这里以 pager 为准再同步一次。
                mSwitchManager.setSelectedPosition(mViewPager.getCurrentItem(), false);
                mViewPager.post(this::updateBackdropSource);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        PageDecorator.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (mViewPager != null) {
            outState.putInt(STATE_CURRENT_PAGE, mViewPager.getCurrentItem());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private void showUnsupportedVersionDialog() {
        String versionText = getString(
            R.string.homepage_unsupported_version_current,
            getAndroidVersion(),
            formatHyperOsVersion()
        );
        String supportedVersionText = getVersionListText(SUPPORT_FULL);
        String partialSupportedVersionText = getVersionListText(SUPPORT_PARTIAL);
        if (!partialSupportedVersionText.isEmpty()) {
            supportedVersionText = supportedVersionText.isEmpty()
                ? partialSupportedVersionText
                : supportedVersionText + "\n" + partialSupportedVersionText;
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setCancelable(false)
            .setTitle(R.string.warn)
            .setMessage(getString(R.string.homepage_unsupported_version_message, versionText, supportedVersionText))
            .setPositiveButton(R.string.exit, (d, which) -> exitForUnsupportedVersion())
            .create();

        dialog.show();

        final var button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button == null) {
            exitForUnsupportedVersion();
            return;
        }

        button.setText(getString(R.string.exit) + " (30)");

        new CountDownTimer(30_000L, 1_000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                button.setText(getString(R.string.exit) + " (" + (millisUntilFinished / 1000L) + ")");
            }

            @Override
            public void onFinish() {
                if (!isFinishing() && !isDestroyed()) {
                    dialog.dismiss();
                }
                exitForUnsupportedVersion();
            }
        }.start();
    }

    private String formatHyperOsVersion() {
        float smallVersion = getSmallVersion();
        if (smallVersion > 0f) {
            return String.valueOf(smallVersion);
        }
        return String.valueOf(getHyperOSVersion());
    }

    private void exitForUnsupportedVersion() {
        if (mIsUnsupportedVersionExiting) {
            return;
        }
        mIsUnsupportedVersionExiting = true;
        finishAffinity();
        killProcess(myPid());
    }
}
