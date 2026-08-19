# RunPulse Android —— 跑步轨迹采集 App（1.0 MVP 骨架）

运动全程前台 Service 保活采集、轨迹点实时落盘 Room、WorkManager 攒批上传云端（Go 服务）。

## 工程结构

```
android/
├── settings.gradle.kts               # 根工程设置，仓库声明
├── build.gradle.kts                  # 根构建脚本（仅声明插件版本）
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml            # Version Catalog，全部依赖版本在此维护
│   └── wrapper/gradle-wrapper.properties   # Gradle 8.9（无 wrapper jar，导入 AS 会自动下载）
├── app/
│   ├── build.gradle.kts              # 模块构建脚本，从 local.properties 注入 BuildConfig
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml       # 前台 Service（location 类型）+ 权限声明
│       ├── res/                      # layout / values（极简 UI）
│       └── java/com/runpulse/app/
│           ├── RunPulseApp.kt        # Application：建通知渠道、注册周期上传任务
│           ├── MainActivity.kt       # 极简首页 + 运行时权限 + 防杀引导入口
│           ├── MainViewModel.kt      # Room Flow 观察活跃会话/轨迹点，内存中算距离
│           ├── tracking/
│           │   ├── SportType.kt      # run / trail / bike（游泳不做）
│           │   └── TrackingService.kt# ★ 前台采集 Service：START_STICKY + 通知 + FusedLocation + 点即落盘
│           ├── data/
│           │   ├── AppDatabase.kt    # Room 单例
│           │   ├── TrackingDao.kt    # 插入点 / 查未上传 / 标记已上传 / 会话管理
│           │   ├── TrackPointEntity.kt
│           │   └── SessionEntity.kt
│           ├── upload/
│           │   ├── UploadScheduler.kt# 15min 周期兜底 + Service 内 50 点/3 分钟触发一次性任务
│           │   └── UploadWorker.kt   # ★ CoroutineWorker：幂等上传，失败不标记，retry 补传
│           ├── api/
│           │   ├── RunPulseApi.kt    # Retrofit 接口（契约定稿）
│           │   ├── ApiModels.kt      # 请求/响应模型，字段名与云端严格一致
│           │   └── ApiClient.kt      # Retrofit 单例，X-API-Key 拦截器
│           └── coach/
│               └── CoachSpeaker.kt   # TTS 播报教练点评（主线程懒初始化 + 队列）
```

## 配置 baseUrl / API Key

在工程根目录（`android/`）创建 `local.properties`（已被 .gitignore 排除）：

```properties
runpulse.baseUrl=https://your-go-server.com
runpulse.apiKey=your-api-key
```

`app/build.gradle.kts` 会读取并注入 `BuildConfig.BASE_URL` / `BuildConfig.API_KEY`；缺省时使用占位默认值（`https://api.example.com` / `REPLACE_ME`）。

## 导入与构建

本机未生成 wrapper jar（按要求不含二进制）。直接用 Android Studio（Hedgehog+）Open 该目录，IDE 会按 `gradle-wrapper.properties` 自动下载 Gradle 8.9 与依赖。AGP 8.5.2 / Kotlin 2.0.20 / compileSdk 34 / minSdk 26。

## 可靠性设计要点

1. **保活**：`TrackingService` 为 `foregroundServiceType="location"` 的前台服务，`START_STICKY`；进程被杀后系统重启时（intent == null）从 Room 恢复未结束会话继续采集。
2. **不丢点**：每个定位回调点立即 INSERT 进 Room（`uploaded=false`），内存零缓存，进程死亡最多丢最后一个回调点。
3. **不丢传**：上传成功才标记 `uploaded`；失败返回 `Result.retry()` 由 WorkManager 退避重试；断网点会一直留在库里直到网络恢复补传。周期性任务（15min）+ 攒批触发（50 点 / 3 分钟）双保险。
4. **省电**：`setMaxUpdateDelayMillis(10s)` 允许定位芯片批量回传。
5. **刻意不申请** `ACCESS_BACKGROUND_LOCATION`：定位是“运动中亮屏/前台”场景，降低审核与权限成本。

## 防杀 checklist（真机验证用）

- [ ] 电池优化白名单：App 内「防杀设置」按钮 → 弹说明对话框 → 跳系统授权页（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）
- [ ] 厂商自启动/后台运行权限（小米：自启动管理 + 省电策略无限制；华为：应用启动管理手动允许后台；OPPO/vivo 类似）——**TODO：做厂商引导页**
- [ ] 锁屏/熄屏运动 30 分钟，验证通知存活、轨迹无大段缺口
- [ ] 飞行模式运动后再联网，验证断网补传完整
- [ ] 强杀进程后验证 START_STICKY 恢复（部分厂商 ROM 会拦截，需配合自启动权限）

## 后续 TODO

- **动态采样率**：按速度/活动状态切换 LocationRequest 档位（代码内已留 TODO）
- **BLE 心率**：接心率带填充 `heartRate` 字段（契约已预留 `heart_rate`）
- **卡尔曼滤波**：轨迹点平滑 + 距离计算优化（当前为 haversine 逐段累加 + 异常跳点剔除）
- 通知实时刷新距离/配速（当前为占位文本）
- 会话 start/end 上报失败后的补偿重试（当前 best-effort）
- Release 混淆规则、日志降级

---

# 1.1 更新：地图轨迹 + 历史记录 UI

## UI 结构（三个页面）

- **MainActivity（首页）**：原有运动控制基础上，新增实时轨迹预览（`CanvasTrackView`，运动中随 Room Flow 刷新）、「历史记录」入口、「导入测试数据」调试按钮（仅 DEBUG 可见）。
- **HistoryActivity（历史记录）**：已结束会话列表（emoji 类型图标 + 日期 + 距离/时长/配速或速度）。统计由 `HistoryViewModel` 预取轨迹点用 `TrackStats` 算好后一次性 submitList。
- **SessionDetailActivity（详情页）**：轨迹地图（300dp）+ 统计行（距离/时长/配速或速度/爬升）+ AI 教练点评列表。统计算法与服务端一致（haversine + 30m/s 漂移过滤，爬升只计上升段，见 `stats/TrackStats.kt`）。

## 地图双实现

`ui/map/TrackMapFactory.create()` 按配置二选一：

- **CanvasTrackView（默认兜底）**：纯 Canvas 自绘，零第三方依赖，等比缩放居中，线段颜色按瞬时速度红(慢)→绿(快) 渐变，起终点标记「起/终」。
- **MapboxTrackView（可选增强）**：Mapbox Maps SDK v11，PolylineAnnotation 画轨迹、CircleAnnotation 标起终点。启用方法：`local.properties` 加 `mapbox.token=pk.xxx`（公共 token），同时 Mapbox SDK 下载需要在 `gradle.properties` 配置 `MAPBOX_DOWNLOADS_TOKEN`（带 Downloads:Read scope 的私有 token）。未配置 token 时自动回退 Canvas 实现。

## 坐标系说明

GPS 采集的轨迹点是 WGS84 坐标；国内地图底图（Mapbox 中国/高德/腾讯）使用 GCJ02（火星坐标系），直接上图会偏移数百米。`ui/map/CoordinateTransform.kt` 提供标准 eviltransform WGS84→GCJ02 转换（含 outOfChina 判断，境外不转换）。Canvas 自绘无底图，不做转换。

## 调试导入（仅 DEBUG）

首页「导入测试数据」按钮调用 `debug/GpxImporter`，解析 `app/src/main/assets/` 下两个 Suunto GPX（跑步/骑行），生成固定 sessionId（`debug-run-gpx` / `debug-bike-gpx`）的已结束会话，`uploaded=1` 不参与上传。幂等：重复点击检测到已存在即跳过。导入后可在历史记录页查看轨迹与统计。

## 数据层变更（Room v2）

新增 `coach_messages` 表（UploadWorker 轮询到 AI 点评后先落库再 TTS，`serverId` 唯一索引去重）。当前 `fallbackToDestructiveMigration()` 允许清库升级，**正式版发布前必须改为显式 Migration**。
# RunApp-Android
