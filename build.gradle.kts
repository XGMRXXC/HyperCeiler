plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Keep Kotlin 2.4.10 on the built-in Kotlin classpath for EzHookTool 1.1.3.
    alias(libs.plugins.kotlin.android) apply false
    // 液态玻璃底栏用 Compose + Miuix Compose，编译器插件版本必须跟 Kotlin 一致
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.lsparanoid) apply false
}
