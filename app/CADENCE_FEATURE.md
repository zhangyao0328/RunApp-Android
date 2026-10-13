# 步频检测功能实现总结

## 已实现功能

### 1. 实时步频检测（方案A：基于加速度计）

**核心文件：** `CadenceDetector.kt`

**算法原理：**
- 使用加速度计采集三轴数据
- 计算合加速度（包含重力）
- 低通滤波平滑数据（系数 0.15）
- 峰值检测：识别步伐产生的加速度峰值
- 动态阈值：根据历史峰值自动调整灵敏度（范围 10-15 m/s²）
- 滑动窗口：实时计算最近 10 秒的步频（SPM）

**关键参数：**
- 最小步间隔：250ms（最大支持 240 SPM）
- 采样率：SENSOR_DELAY_GAME（约 20ms）
- 滑动窗口：10 秒

### 2. 数据模型扩展

**TrackPointEntity.kt** 新增字段：
```kotlin
val cadence: Int? = null  // 步频（SPM），仅跑步时有效
```

**数据库版本升级：** v2 → v3

### 3. TrackingService 集成

**启动时机：**
- 仅跑步模式（SportType.RUN）启动步频检测
- 会话开始时启动，结束时停止

**数据采集：**
- 每个 GPS 定位点都会记录当前实时步频
- 步频数据与轨迹点一起存储到 Room

### 4. 通知栏实时更新

**更新频率：** 每 3 秒

**显示内容：**
- 距离（km）
- 配速（'/km）
- 步频（SPM，仅跑步时显示）

**实现方式：**
- Handler + Runnable 定时更新
- 从 Room 查询最新轨迹点计算统计数据
- 使用 NotificationManager.notify() 更新通知

### 5. UI 实时显示

**MainActivity 新增：**
- 顶部状态卡片添加步频显示
- 仅跑步时显示，其他运动类型隐藏
- 从最新轨迹点读取步频数据

**布局文件：** `activity_main.xml` 新增 `tvCadence` TextView

## 权限声明

**AndroidManifest.xml** 新增：
```xml
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
```

## 使用说明

### 用户视角
1. 点击"开始跑步"
2. 顶部状态卡片实时显示：
   - 距离：X.XX km
   - 时长：HH:MM:SS
   - 步频：XXX SPM
3. 通知栏每 3 秒更新一次，显示相同信息

### 开发者视角

**获取实时步频：**
```kotlin
// 在 TrackingService 中
val currentCadence = cadenceDetector?.currentCadence?.value
```

**获取平均步频：**
```kotlin
val avgCadence = cadenceDetector?.getAverageCadence(sessionDurationMs)
```

**从轨迹点读取步频：**
```kotlin
val latestCadence = points.lastOrNull()?.cadence
```

## 技术亮点

1. **动态阈值调整**：根据用户跑步强度自动调整检测灵敏度，避免误检/漏检
2. **低通滤波**：有效去除传感器噪声，提高检测准确性
3. **滑动窗口**：实时计算最近 10 秒的步频，响应速度快
4. **最小步间隔**：避免高频误检，最大支持 240 SPM
5. **通知栏实时更新**：每 3 秒刷新，用户无需打开 App 即可查看数据

## 后续优化建议

1. **步幅估算**：结合步频和速度，估算步幅（距离/步数）
2. **步频区间分析**：统计不同步频区间的时长占比
3. **步频告警**：步频过低/过高时提醒用户调整
4. ~~历史趋势图：在详情页展示步频随时间的变化曲线~~ ✅ 已实现（2026-10-12）：管理后台会话详情页新增"平均步频"统计项，心率/速度曲线图同步叠加步频（SPM）曲线
   - 类型区分：仅跑步（run）显示步频/步幅，骑行等不显示，与 App 端一致
   - 平均步幅 = 总距离 / 总步数（总步数 = 平均步频 × 净时长），与 App 端 `TrackStats.avgStrideLengthM` 同算法，管理后台详情页同步展示
5. **个性化阈值**：根据用户历史数据训练个性化检测模型

## 测试建议

1. **不同配速测试**：慢跑（180 SPM）、正常跑（160 SPM）、快跑（140 SPM）
2. **不同地形测试**：平地、上坡、下坡
3. **设备兼容性**：不同品牌手机的加速度计精度差异
4. **功耗测试**：长时间跑步时的电量消耗
5. **准确性对比**：与专业跑步手表（如 Garmin）对比

## 已知限制

1. 仅支持跑步模式，骑行/越野不启用
2. 需要设备支持加速度计
3. 步频检测精度受手机放置位置影响（建议手持或臂包）
4. 数据库版本升级会清空历史数据（fallbackToDestructiveMigration）
