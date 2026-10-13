# android —— Android 端约束

## 技术栈

Kotlin + Room + WorkManager + Retrofit + Coroutines，UI 为传统 View 体系（Activity/Layout，非 Compose），地图用 Mapbox/Canvas。包名 `com.runpulse.app`。

## 工程规则

1. **依赖版本统一维护在 `gradle/libs.versions.toml`（Version Catalog）**，不要散写在 `build.gradle.kts`。
2. BuildConfig / API Key 等从 `local.properties` 注入，禁止硬编码。
3. 采集逻辑核心在 `tracking/`（前台 Service + FusedLocation，点即落盘），改动必须保持：
   - 前台 Service 保活与通知渠道不回归；
   - Room 写入与上传状态机幂等（`upload/UploadWorker` 失败不标记已上传，靠 retry 补传）。
4. **API 契约**：`api/ApiModels.kt` 的字段名与后端 `backend/runpulse-server` 的 `/api/v1/*` 严格一致，改任何一端需同步另一端。
5. 运动类型只支持 run / trail / bike（游泳不做）。

## 常用命令

```bash
./gradlew assembleDebug          # 构建 debug APK
./gradlew test                   # 单元测试
```

- 无 gradle-wrapper.jar，导入 Android Studio 会自动下载 wrapper；命令行需本机已有对应 Gradle。
