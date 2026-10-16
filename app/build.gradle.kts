import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

// 构建日期：CI 容器默认是 UTC，这里固定用东八区；也可通过 -PBUILD_DATE=20261016 覆盖
val buildDate: String = (project.findProperty("BUILD_DATE") as String?)
    ?: LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.BASIC_ISO_DATE)

// 渠道列表：新增渠道只需在这里加一项
val channels = listOf("official", "xiaomi", "huawei", "oppo", "vivo", "yyb")

android {
    namespace = "com.runpulse.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.runpulse.app"
        minSdk = 26
        targetSdk = 34
        // 版本号支持 CI 通过 -PVERSION_CODE 注入（与 .woodpecker.yml update-version
        // 写入数据库的 CI_PIPELINE_NUMBER 保持一致）；本地构建缺省回退 1
        val versionCodeOverride = (project.findProperty("VERSION_CODE") as String?)?.toIntOrNull() ?: 1
        versionCode = versionCodeOverride
        versionName = "1.0.$versionCodeOverride"

        // 注入到 BuildConfig，代码中通过 BuildConfig.BASE_URL / BuildConfig.API_KEY 访问
        buildConfigField("String", "BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "API_KEY", "\"$apiKey\"")
        buildConfigField("String", "MAPBOX_TOKEN", "\"$mapboxToken\"")
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // ---------- 多渠道 ----------
    flavorDimensions += "channel"
    productFlavors {
        channels.forEach { ch ->
            create(ch) {
                dimension = "channel"
                // 代码中通过 BuildConfig.CHANNEL 获取（统计、更新检查可带上渠道）
                buildConfigField("String", "CHANNEL", "\"$ch\"")
                // AndroidManifest 中可用 ${CHANNEL} 引用，如友盟：
                // <meta-data android:name="UMENG_CHANNEL" android:value="${CHANNEL}" />
                manifestPlaceholders["CHANNEL"] = ch
            }
        }
    }

    // 签名配置 - 支持 Gradle -P 参数、环境变量、local.properties
    signingConfigs {
        create("release") {
            val keystoreFile = (project.findProperty("KEYSTORE_FILE") as String?)
                ?: System.getenv("KEYSTORE_FILE")
                ?: localProps.getProperty("keystore.file", "")
            val keystorePassword = (project.findProperty("KEYSTORE_PASSWORD") as String?)
                ?: System.getenv("KEYSTORE_PASSWORD")
                ?: localProps.getProperty("keystore.password", "")
            val keyAlias = (project.findProperty("KEY_ALIAS") as String?)
                ?: System.getenv("KEY_ALIAS")
                ?: localProps.getProperty("key.alias", "runpulse")
            val keyPassword = (project.findProperty("KEY_PASSWORD") as String?)
                ?: System.getenv("KEY_PASSWORD")
                ?: localProps.getProperty("key.password", "")

            if (keystoreFile.isNotEmpty() && keystorePassword.isNotEmpty()) {
                // 路径相对于 Android 工程根目录解析，避免 :app 子项目把
                // `app/keystore.jks` 错误解析成 `app/app/keystore.jks`。
                storeFile = rootProject.file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            // 如需与 release 共存，可开启后缀；注意微信/Mapbox 等按包名校验的服务需单独登记
            // applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 有正式 keystore 用正式签名；本地没有时回退 debug 签名，保证 Build Variants 切到 release 也能直接运行
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // ---------- APK 命名：runpulse-{渠道}-{buildType}-v{版本名}-{版本号}-{日期}.apk ----------
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            (this as BaseVariantOutputImpl).outputFileName =
                "runpulse-${variant.flavorName}-${variant.buildType.name}" +
                        "-v${variant.versionName}-${variant.versionCode}-$buildDate.apk"
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