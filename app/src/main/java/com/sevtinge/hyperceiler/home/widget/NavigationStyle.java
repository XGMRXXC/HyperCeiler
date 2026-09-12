package com.sevtinge.hyperceiler.home.widget;

public enum NavigationStyle {
    CAPSULE_ICON, // 悬浮胶囊图标样式
    BOTTOM_LABEL, // 传统底部标签样式
    LIQUID_GLASS; // 液态玻璃悬浮底栏（新增，参考 KernelSU manager 的 FloatingBottomBar）

    /** 设置里「底栏样式」的取值：0 贴地、1 悬浮胶囊、2 液态玻璃。 */
    public static NavigationStyle fromIndex(int index) {
        if (index == 1) return CAPSULE_ICON;
        if (index == 2) return LIQUID_GLASS;
        return BOTTOM_LABEL;
    }

    public int toIndex() {
        if (this == CAPSULE_ICON) return 1;
        if (this == LIQUID_GLASS) return 2;
        return 0;
    }
}
