pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Mapbox 私有仓库；下载需在 gradle.properties 配置 MAPBOX_DOWNLOADS_TOKEN（Downloads:Read scope）
        maven("https://api.mapbox.com/downloads/v2/releases/maven")
        // XPopup 发布在 JitPack
        maven("https://jitpack.io")
        // 阿里云 Maven 仓库（号码认证 SDK 部分组件）
        maven("https://maven.aliyun.com/nexus/content/repositories/releases/")
    }
}
rootProject.name = "RunPulse"
include(":app")
