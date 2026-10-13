import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// 从根目录 local.properties 读取服务端配置（该文件不入版本库），缺省时使用占位默认值。
// 对应键名：runpulse.baseUrl / runpulse.apiKey
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val apiBaseUrl: String = localProps.getProperty("runpulse.baseUrl", "https://api.example.com")
val apiKey: String = localProps.getProperty("runpulse.apiKey", "REPLACE_ME")
// Mapbox 公共 token（pk. 开头），不配则轨迹图回退 Canvas 自绘实现
val mapboxToken: String = localProps.getProperty("mapbox.token", "MAPBOX_TOKEN_PLACEHOLDER")

android {
    namespace = "com.runpulse.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.runpulse.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // 注入到 BuildConfig，代码中通过 BuildConfig.BASE_URL / BuildConfig.API_KEY 访问
        buildConfigField("String", "BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "API_KEY", "\"$apiKey\"")
        buildConfigField("String", "MAPBOX_TOKEN", "\"$mapboxToken\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)

    // Lifecycle / ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Room 本地落盘
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager 攒批上传
    implementation(libs.androidx.work.runtime.ktx)

    // 网络层：Retrofit + OkHttp + Gson
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // 定位
    implementation(libs.play.services.location)

    // 地图轨迹（可选增强，CanvasTrackView 为零依赖兜底）
    implementation(libs.mapbox)

    implementation(libs.kotlinx.coroutines.android)

    // 沉浸式系统栏 + 弹窗组件
    implementation(libs.immersionbar)
    implementation(libs.xpopup)

    // PhotoEditor 海报图片编辑（画笔/文字/贴纸/滤镜/撤销重做）
    implementation(libs.photoeditor)

    // 微信开放平台 SDK（登录）
    implementation(libs.wechat.sdk)

    // AppUpdater 应用内更新（下载 APK + 安装）
    implementation(libs.appupdater)
    implementation(libs.appupdater.dialog)

    // Garmin 官方 FIT Java SDK（轨迹导出 FIT 格式；Maven Central 实际坐标为 com.garmin:fit）
    implementation("com.garmin:fit:21.214.0")

    // 阿里云号码认证 SDK（本地 AAR，需从阿里云控制台下载放入 libs/）
    // implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    // 阿里云 SDK 需要的 fastjson
    // implementation("com.alibaba:fastjson:1.2.83")
}
