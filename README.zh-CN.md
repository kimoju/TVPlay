# TVPlay

[English](README.md) · [简体中文](README.zh-CN.md)

**专为 Android TV、电视盒子、智能投影仪与大屏显示设备打造的无线 / 有线 Apple CarPlay 接收端。**
独立包名：`app.tvplay`。

## 核心特性

- **10-Foot 双栏电视工作台 (`TvPlayWorkbenchActivity`)**：Apple TV 风格深色影院主题，左栏提供实时投屏会话舞台与电视三步无线连接指南，右栏提供专为遥控器焦点优化的四分类设置面板（`连接设置`、`显示与画质`、`音频与语音`、`关于与诊断`）。
- **全屏硬件加速投屏 (`TvPlayProjectionActivity`)**：支持 H.264 与 HEVC (H.265) 解码、30/60 FPS 高刷、Smooth Video 帧步进防抖、超低延迟硬件解码，以及全屏投屏下的遥控器快捷控制中心（按遥控器 `菜单键` 或 `长按返回键` 呼出）。
- **专为家庭电视优化的连接通道**：
  - **同一局域网 Wi-Fi / 网线直连**（推荐电视插网线或连接家庭路由器 Wi-Fi，iPhone 连接同一路由器 Wi-Fi）
  - **Wi-Fi Direct 5GHz 点对点直连**
  - **USB 有线 CarPlay**
- **完整电视遥控器与语音操控**：支持遥控器五向键（方向键焦点移动 + 确认键点击）、媒体播放控制键，以及遥控器语音键一键唤醒 Siri。

## 工程架构

项目采用 `:core` + `:app` 双模块 Clean Architecture 架构：

- **`:core` (`app.tvplay.core`)**：CarPlay / AirPlay / iAP2 / MFi / 音视频解码 / 网络发现 / 底层传输协议引擎与 JNI 原生库（`lzo_jni`、`opus_jni`）。
- **`:app` (`app.tvplay`)**：
  - `app.tvplay.settings`：分域配置仓库（`TvPlaySettingsRepository` + `AirPlayPersistence`）。
  - `app.tvplay.projection`：模块化投屏控制器（`ProjectionSessionManager`、`VideoSurfaceController`、`RemoteInputDispatcher`）。
  - `app.tvplay.ui`：10-Foot 电视界面组件（`WorkbenchStagePane`、`WorkbenchSettingsPane`、`ProjectionWaitingView`、`QuickControlOverlayView`）。

## 构建与验证

运行完整的单元测试、Lint 静态检查与 Debug 编译套件：

```sh
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

使用本地离线 MFi 认证资源（存放于 `.private/auth-assets/`）构建可独立安装的 TVPlay APK：

```sh
./scripts/build-tvplay.sh
```

## 开源致谢与许可证

基于 [xcertplay](https://github.com/shilapi/xcertplay) (GPL-3.0) 与 [DiPlay](https://github.com/shihabal3amri/DiPlay) / [DiAuto](https://github.com/shihabal3amri/DiAuto) (AGPL-3.0) 深度重构改造，相关许可证文件保留于 `docs/licenses`。CarPlay 为 Apple Inc. 商标。
