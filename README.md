# TVPlay

**专为 Android TV、电视盒子、智能投影仪与大屏显示设备打造的无线 / 有线 Apple CarPlay 接收端。**

- **独立包名**：`app.tvplay`
- **系统要求**：Android 7.0+ (API 24+)，纯 Kotlin/Java 实现（零 JNI/NDK 依赖），无需越狱、无需外接 CarPlay 采集盒

---

## 核心特性

- **10-Foot 双栏大屏工作台 (`TvWorkbenchActivity`)**
  - 采用 Apple TV 风格深色影院主题与高对比度遥控器焦点环设计（`TvColorScheme`）。
  - **左栏（主舞台 `WorkbenchHeroStage`）**：实时显示投屏连接状态、内置离线 MFi 证书状态、一键进入全屏投屏、无线通道快速切换，以及电视 / 投影仪三步无线连接指南。
  - **右栏（设置中心 `WorkbenchSettingsTabs`）**：专为遥控器五向键优化的四大页签（`连接设置`、`显示与画质`、`音频与语音`、`系统与诊断`）。
- **全屏硬件加速投屏舞台 (`TvStageActivity`)**
  - 支持 **H.264** 与 **HEVC (H.265)** 超清低码率视频流硬件解码（`HardwareVideoDecoder` / `TvVideoSurfaceHost`）。
  - 支持 **30 FPS / 60 FPS** 帧率切换、**Smooth Video** 视频帧步进防抖输出、**Low-Latency** 超低延迟硬件解码器。
  - 支持画面缩放、UI 密度缩放、深浅色外观、安全区边距及实时色彩调节（亮度、对比度、饱和度、色温，`StagePictureTuningOverlay`）。
- **专为家庭大屏优化的连接通道**
  - **同一局域网 Wi-Fi / 网线直连（`LanNetworkLink`，推荐）**：电视通过网线或 Wi-Fi 连接家庭路由器，iPhone 连接同一路由器 Wi-Fi 即可完成无线握手与投屏。
  - **Wi-Fi Direct 5GHz 直连（`WifiDirectGroupLink`）**：无需路由器，电视与 iPhone 点对点建立 5GHz 高速直连通道。
  - **USB 有线投屏（`UsbGadgetTransport` / `UsbTunnelVpnService`）**：支持通过 USB 数据线直连 iPhone。
- **原生电视遥控器与 Siri 语音操控**
  - 完整支持 Android TV 遥控器方向键旋钮焦点漫游、确认键点击、返回键导航、媒体播放键，以及遥控器语音键一键唤醒 Siri（`TvRemoteKeyMap` / `TvRemoteInputController`）。
  - 全屏投屏期间按遥控器 **菜单键** 或 **长按返回键** 即可呼出快捷控制中心浮层（`StageQuickControlOverlay`）。

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

## 工程架构与目录结构

项目采用全新设计的 `:core` (`app.tvplay.engine.*`) + `:app` (`app.tvplay.*`) 双模块分层架构，共 45 个职责清晰的源码文件，无任何历史车载遗留代码与原生 C/JNI 依赖：

```text
TVPlay/
├── core/                                           # 纯 Kotlin/Java 投屏协议与音视频引擎 (:core)
│   └── src/main/java/app/tvplay/engine/
│       ├── auth/                                   # 认证与加密子系统
│       │   ├── AccessoryIdentity.kt                # Ed25519 长期配件身份与配对注册表
│       │   ├── HomeKitPairingCipher.kt             # SRP-6a / Ed25519 / X25519 / ChaCha20-Poly1305 / TLV8
│       │   ├── MfiAuthProvider.kt                  # MFi 认证提供者接口与离线凭证读取
│       │   ├── MfiSapHandshake.kt                  # FairPlay SAP 握手与流密钥派生
│       │   └── UsbLockdownClient.kt                # iOS usbmuxd / lockdown 有线配对客户端
│       ├── protocol/
│       │   ├── iap2/                               # Apple iAP2 协议栈
│       │   │   ├── Iap2FrameCodec.kt               # iAP2 数据包与控制会话消息 (CSM) 编解码
│       │   │   ├── Iap2LinkLayer.kt                # iAP2 链路层状态机、SYN/ACK 重传与流控
│       │   │   └── Iap2ControlHandler.kt           # iAP2 身份认证、CarPlay 无线握手与文件传输处理
│       │   └── rtsp/                               # AirPlay / CarPlay RTSP 控制与流通道
│       │       ├── RtspBinaryCodec.kt              # RTSP 报文解析、Binary Plist 与 NTP 时钟同步
│       │       ├── RtspHidDispatcher.kt            # 旋钮、触控、媒体键与通话 HID 报告封装
│       │       ├── RtspStreamChannels.kt           # 视频流 (:7100) 与双向音频流套接字管理
│       │       └── RtspControlSession.kt           # TCP :7000 控制会话与状态协同
│       ├── network/                                # 网络发现与传输链路
│       │   ├── BonjourServiceAdvertiser.kt         # NSD / JmDNS 双栈 Bonjour 广播与探测
│       │   ├── LanNetworkLink.kt                   # 家庭局域网 Wi-Fi / 以太网直连链路
│       │   ├── WifiDirectGroupLink.kt              # Wi-Fi Direct 5GHz P2P 群组管理
│       │   ├── WirelessLinkManager.kt              # 无线链路统一生命周期编排
│       │   ├── BluetoothRfcommAcceptor.kt          # 蓝牙 RFCOMM 服务端与客户端双向字节流
│       │   ├── UsbGadgetTransport.kt               # USB AOAv2 / CDC-NCM / usbmuxd 传输层
│       │   └── UsbTunnelVpnService.kt              # 有线 USB IPv6 虚拟网卡隧道服务
│       ├── av/                                     # 硬件音视频解码与视口数学计算
│       │   ├── ViewportLayoutMath.kt               # 显示比例、安全区边距与物理尺寸换算
│       │   ├── HardwareVideoDecoder.kt             # H.264 / HEVC MediaCodec 低延迟硬件解码器
│       │   ├── PcmAudioRenderer.kt                 # 多声道 PCM / AAC-ELD / Opus 音频渲染管线
│       │   ├── VoiceMicRecorder.kt                 # Siri 语音麦克风采集与回声抑制
│       │   └── TvAvRenderPipeline.kt               # 音视频管线编排与实时帧率统计
│       └── session/                                # 会话状态机与统一引擎入口
│           ├── TvSessionConfig.kt                  # 投屏会话不可变配置模型
│           ├── TvSessionState.kt                   # 会话阶段、状态快照与故障诊断分类
│           └── TvSessionEngine.kt                  # 有线 / 无线投屏会话核心状态机
└── app/                                            # Android TV 10-Foot 交互应用层 (:app)
    └── src/main/java/app/tvplay/
        ├── config/                                 # 设备能力检测与持久化配置
        │   ├── MfiAssetInstaller.kt                # 离线 MFi 凭证安装器
        │   ├── TvDeviceCapabilities.kt             # Android TV 特性、HEVC 硬件解码能力与权限检查
        │   ├── TvPreferencesStore.kt               # SharedPreferences 键值存储层
        │   └── TvSettingsRepository.kt             # 分域设置仓库 (connection / display / audio / identity)
        ├── remote/                                 # 电视遥控器输入与画质滤镜
        │   ├── TvRemoteKeyMap.kt                   # Android TV 遥控器按键到 CarPlay 旋钮/媒体/Siri 映射
        │   ├── TvRemoteInputController.kt          # 遥控器按键、菜单唤醒与触摸事件分发控制器
        │   └── TvVideoColorFilter.kt               # TextureView 色彩矩阵与画质参数调节
        ├── runtime/                                # 运行时服务与系统集成
        │   ├── TvDiagnosticsLogger.kt              # 脱敏环形诊断日志与诊断报告导出
        │   ├── TvLaunchReceivers.kt                # 开机自启、蓝牙重连与 USB 接入广播接收器
        │   ├── TvMediaKeyRouter.kt                 # 系统 MediaSession 焦点与媒体键路由
        │   ├── TvProjectionSessionHost.kt          # Activity 与 TvSessionEngine 会话桥接宿主
        │   └── TvSessionForegroundService.kt       # 投屏前台常驻保活服务
        └── ui/                                     # 10-Foot 大屏 UI 视图层
            ├── theme/
            │   ├── TvColorScheme.kt                # 深浅色影院主题调色板与遥控器焦点环配色
            │   └── TvUiComponents.kt               # 10-Foot 遥控器按钮、弹窗与图标缩放组件
            ├── workbench/                          # 双栏电视工作台
            │   ├── TvWorkbenchActivity.kt          # 工作台主入口 Activity
            │   ├── WorkbenchHeroStage.kt           # 左栏连接状态主舞台与三步连接指南
            │   └── WorkbenchSettingsTabs.kt        # 右栏四分类设置面板
            └── stage/                              # 全屏 CarPlay 投屏舞台
                ├── TvStageActivity.kt              # 全屏投屏主舞台 Activity
                ├── TvVideoSurfaceHost.kt           # SurfaceView / TextureView 渲染宿主与生命周期控制
                ├── StageWaitingOverlay.kt          # 全屏等待连接与故障引导浮层
                ├── StageQuickControlOverlay.kt     # 遥控器菜单键呼出的快捷控制面板
                ├── StagePictureTuningOverlay.kt    # 实时画质色彩调节浮层
                └── StageFpsHudOverlay.kt           # 实时帧率与解码延迟 HUD 浮层
```

---

## 构建与验证

项目已直接内置离线 MFi 认证资源（`app/src/main/assets/offline-mfi/`），克隆仓库后即可直接编译出完整可用的 `Release` 与 `Debug` 安装包。

运行全套单元测试、Lint 检查与 Debug / Release 构建：

```sh
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

一键打包输出至 `dist/` 目录：

```sh
./scripts/build-tvplay.sh
```

构建产物输出路径：
- **Release 正式版**：`dist/TVPlay-release.apk`（包名 `app.tvplay`）
- **Debug 调试版**：`dist/TVPlay-debug.apk`（包名 `app.tvplay.debug`）

---

## 开源致谢与许可证

本项目基于 [xcertplay](https://github.com/shilapi/xcertplay) (GPL-3.0) 与 [DiPlay](https://github.com/shihabal3amri/DiPlay) / [DiAuto](https://github.com/shihabal3amri/DiAuto) (AGPL-3.0) 深度重构改造，相关许可证文本保留于 [`docs/licenses`](docs/licenses)。CarPlay 为 Apple Inc. 注册商标。
