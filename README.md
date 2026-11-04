# TVPlay

**专为 Android TV、电视盒子、智能投影仪与大屏显示设备打造的无线 / 有线 Apple CarPlay 接收端。**

- **独立包名**：`app.tvplay`
- **系统要求**：Android 7.0+ (API 24+)，无需越狱、无需外接 CarPlay 采集盒

---

## 核心特性

- **10-Foot 双栏大屏工作台 (`TvPlayWorkbenchActivity`)**
  - 采用 Apple TV 风格深色影院主题与高对比度遥控器焦点环设计。
  - **左栏（主舞台）**：实时显示投屏连接状态、内置离线 MFi 证书状态、一键进入全屏投屏、无线通道快速切换，以及电视 / 投影仪三步无线连接指南。
  - **右栏（设置中心）**：专为遥控器五向键优化的四大页签（`连接设置`、`显示与画质`、`音频与语音`、`关于与诊断`）。
- **全屏硬件加速投屏 (`TvPlayProjectionActivity`)**
  - 支持 **H.264** 与 **HEVC (H.265)** 超清低码率视频流硬件解码。
  - 支持 **30 FPS / 60 FPS** 帧率切换、**Smooth Video** 视频帧步进防抖输出、**Low-Latency** 超低延迟硬件解码器。
  - 支持画面缩放（85% / 100% / 125%）、CarPlay 图标与文字缩放、深浅色外观、四角圆角遮罩及实时色彩调节（亮度、对比度、饱和度、色温）。
- **专为家庭大屏优化的连接通道**
  - **同一局域网 Wi-Fi / 网线直连（推荐）**：电视通过网线或 Wi-Fi 连接家庭路由器，iPhone 连接同一路由器 Wi-Fi 即可完成无线握手与投屏。
  - **Wi-Fi Direct 5GHz 直连**：无需路由器，电视与 iPhone 点对点建立 5GHz 高速直连通道。
  - **USB 有线投屏**：支持通过 USB 数据线直连 iPhone。
- **原生电视遥控器与 Siri 语音操控**
  - 完整支持 Android TV 遥控器方向键焦点漫游、确认键点击、返回键导航、媒体播放键，以及遥控器语音键一键唤醒 Siri。
  - 全屏投屏期间按遥控器 **菜单键** 或 **长按返回键** 即可呼出快捷控制中心浮层。

---

## 电视遥控器按键映射

| 遥控器按键 | 全屏投屏功能 |
| :--- | :--- |
| **方向键 (`▲` `▼` `◀` `▶`)** | 在 CarPlay 图标、地图按钮、音乐控制项之间移动高亮焦点 |
| **确认键 (`OK` / `ENTER`)** | 点击当前选中的 CarPlay 图标或按钮 |
| **短按返回键 (`BACK`)** | 向 CarPlay 发送返回指令 |
| **菜单键 (`MENU`) / 长按返回键** | 呼出 / 关闭全屏快捷控制中心（一键切换帧率、编码、画质或返回工作台） |
| **语音键 (`VOICE` / `SEARCH`)** | 唤醒 CarPlay Siri 语音助手 |
| **媒体键 (`PLAY/PAUSE` / `NEXT` / `PREV`)** | 控制 CarPlay 音乐播放、暂停与切歌 |

---

## 三步无线连接指南

1. **确认无线网络通道**
   - 推荐使用 **同一局域网 Wi-Fi** 模式：确保电视（支持插网线或连 Wi-Fi）与 iPhone 连接在同一个家庭路由器网络下；也可切换为 **Wi-Fi Direct 5GHz 直连**。
2. **iPhone 蓝牙配对**
   - 在 iPhone 上打开「**设置 → 通用 → CarPlay 车载**」，在可用设备列表中选择电视的蓝牙名称完成配对。
3. **自动进入全屏投屏**
   - 握手成功后自动进入全屏 CarPlay 画面，即可使用电视遥控器操控。

---

## 工程架构

项目采用 `:core` + `:app` 双模块 Clean Architecture 架构：

```text
TVPlay/
├── core/                           # 协议与底层传输引擎 (:core, namespace: app.tvplay.core)
│   └── src/main/
│       ├── java/app/tvplay/
│       │   ├── airplay/            # AirPlay 会话、RTSP、配对与控制协议
│       │   ├── crypto/             # Ed25519 / Curve25519 / ChaCha20-Poly1305 加密组件
│       │   ├── iap2/               # iAP2 控制会话与消息编解码
│       │   ├── media/              # H.264/HEVC 视频解码器、PCM/AAC/Opus 音频管线
│       │   ├── mfi/                # 离线 MFi 证书签名与认证引擎
│       │   ├── network/            # Bonjour/mDNS、Wi-Fi Direct P2P、局域网网络发现
│       │   ├── orchestration/      # CarPlay 会话编排与状态机
│       │   └── transport/          # 蓝牙 RFCOMM、USB Mux/NCM 底层传输
│       └── jni/                    # LZO 与 Opus 原生 JNI 库
└── app/                            # Android TV 大屏应用层 (:app, applicationId: app.tvplay)
    └── src/main/java/app/tvplay/
        ├── TvPlayWorkbenchActivity.kt      # 10-Foot 双栏电视工作台主入口
        ├── TvPlayProjectionActivity.kt     # 全屏 CarPlay 投屏宿主 Activity
        ├── TvPlaySessionService.kt         # 投屏前台会话保活服务
        ├── settings/
        │   └── TvPlaySettingsRepository.kt # 分域设置仓库 (connection / display / audio / identity)
        ├── projection/
        │   ├── ProjectionSessionManager.kt # 投屏会话生命周期与重连控制器
        │   ├── VideoSurfaceController.kt   # 视频渲染表面、分辨率计算与 FPS 监控
        │   └── RemoteInputDispatcher.kt    # 电视遥控器按键与语音输入分发器
        └── ui/
            ├── workbench/
            │   ├── WorkbenchStagePane.kt    # 工作台左栏状态舞台与三步连接指南
            │   └── WorkbenchSettingsPane.kt # 工作台右栏四分类设置面板
            └── projection/
                ├── ProjectionWaitingView.kt     # 全屏等待连接状态视图
                └── QuickControlOverlayView.kt   # 全屏遥控器快捷控制中心浮层
```

---

## 构建与验证

运行全套单元测试、Lint 检查与 Debug 构建：

```sh
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

若本地已配置离线 MFi 认证资源（位于 `.private/auth-assets/`），可直接构建独立可安装 APK：

```sh
./scripts/build-tvplay.sh
```

构建产物输出路径：`dist/TVPlay-standalone-debug.apk`。

---

## 开源致谢与许可证

本项目基于 [xcertplay](https://github.com/shilapi/xcertplay) (GPL-3.0) 与 [DiPlay](https://github.com/shihabal3amri/DiPlay) / [DiAuto](https://github.com/shihabal3amri/DiAuto) (AGPL-3.0) 深度重构改造，相关许可证文本保留于 [`docs/licenses`](docs/licenses)。CarPlay 为 Apple Inc. 注册商标。
