# ADB WiFi 调试连接指南

## 问题诊断

你遇到的问题是 **Android 11+ 无线调试需要配对**，不能直接连接。

## 解决方案

### 方案 1：使用 USB 线重新启用无线调试（推荐）

1. **用 USB 线连接手机**
2. **启用 TCP/IP 模式**：
   ```bash
   adb tcpip 5555
   ```
3. **拔掉 USB 线**
4. **连接 WiFi**：
   ```bash
   adb connect 192.168.124.77:5555
   ```

### 方案 2：Android 11+ 无线调试配对（无需 USB 线）

#### 步骤 1：在手机上启用无线调试

1. 打开 **设置** → **开发者选项**
2. 启用 **无线调试**
3. 点击 **无线调试** 进入详情页
4. 点击 **使用配对码配对设备**
5. 记下 **配对码**（6位数字）和 **端口号**（例如：42173）

#### 步骤 2：在电脑上配对

```bash
# 配对（只需一次）
adb pair 192.168.124.77:42173
# 输入手机上显示的 6 位配对码

# 连接（配对成功后）
adb connect 192.168.124.77:38827
```

### 方案 3：使用 Android Studio 的无线调试

1. 打开 **Android Studio**
2. 点击 **Run** 菜单 → **Pair Devices Using Wi-Fi**
3. 选择 **Pair using QR code** 或 **Pair using pairing code**
4. 按照提示完成配对

## 常见问题排查

### 1. ADB 服务器崩溃（你遇到的问题）

**症状：**
```
adb: failed to check server version: protocol fault (couldn't read status): Connection reset by peer
```

**原因：** Android Studio 占用了 ADB 端口

**解决：**
```bash
# 完全杀死 ADB
killall -9 adb

# 等待 2 秒
sleep 2

# 重启 ADB
adb start-server

# 查看设备
adb devices
```

### 2. 设备显示 offline

**解决：**
```bash
# 断开所有连接
adb disconnect

# 重新连接
adb connect 192.168.124.77:38827
```

### 3. 无法发现设备

**检查 mDNS 服务：**
```bash
adb mdns services
```

**如果没有输出，检查：**
- 手机和电脑是否在同一 WiFi 网络
- 手机的无线调试是否已启用
- 防火墙是否阻止了 mDNS（端口 5353）

### 4. 配对失败

**可能原因：**
- 配对码输入错误
- 配对码已过期（有效期约 1 分钟）
- 网络不稳定

**解决：**
- 重新生成配对码
- 确保手机和电脑在同一网络
- 关闭 VPN 或代理

## 你的设备信息

根据 mDNS 发现：
- **设备 ID**: adb-bf66185c-PrkB0K
- **IP 地址**: 192.168.124.77
- **连接端口**: 38827

## 快速连接脚本

创建一个脚本 `connect_wifi.sh`：

```bash
#!/bin/bash

# 杀死现有 ADB 进程
killall -9 adb 2>/dev/null
sleep 2

# 启动 ADB 服务器
adb start-server

# 发现设备
echo "发现设备："
adb mdns services

# 提示用户输入配对信息
read -p "请输入配对端口（例如 42173）: " PAIR_PORT
read -p "请输入配对码（6位数字）: " PAIR_CODE

# 配对
echo "$PAIR_CODE" | adb pair 192.168.124.77:$PAIR_PORT

# 等待配对完成
sleep 2

# 连接
adb connect 192.168.124.77:38827

# 查看设备
adb devices
```

使用方法：
```bash
chmod +x connect_wifi.sh
./connect_wifi.sh
```

## 预防措施

1. **避免端口冲突**：
   - 不要同时运行多个 ADB 实例
   - 关闭 Android Studio 的设备管理器（如果不用）

2. **保持连接稳定**：
   - 使用 5GHz WiFi（更稳定）
   - 避免网络切换
   - 保持手机屏幕常亮（防止休眠断开）

3. **自动重连**：
   在 `~/.zshrc` 或 `~/.bashrc` 中添加：
   ```bash
   alias adb-reconnect='adb disconnect && adb connect 192.168.124.77:38827'
   ```

## 推荐工作流

1. **首次连接**：使用 USB 线 + `adb tcpip 5555`
2. **日常使用**：使用 Android 11+ 无线调试配对
3. **遇到问题**：运行 `killall -9 adb && adb start-server`

## 参考链接

- [Android 11 无线调试官方文档](https://developer.android.com/studio/command-line/adb#wireless-android11-command-line)
- [ADB 故障排查](https://developer.android.com/studio/command-line/adb#troubleshooting)
