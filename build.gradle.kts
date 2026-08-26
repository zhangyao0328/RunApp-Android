// 根构建脚本：只声明插件版本，不应用。版本统一在 gradle/libs.versions.toml 管理。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}
