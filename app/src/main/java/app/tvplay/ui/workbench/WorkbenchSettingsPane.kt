package app.tvplay.ui.workbench

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.tvplay.AppAppearance
import app.tvplay.CarPlayBlackCornerOverlay
import app.tvplay.CarPlayNightMode
import app.tvplay.DefaultConnectionMode
import app.tvplay.SettingsCategory
import app.tvplay.TvPlayBootstrap
import app.tvplay.TvPlayButton
import app.tvplay.TvPlayButtonKind
import app.tvplay.TvPlayPalette
import app.tvplay.WirelessPermissions
import app.tvplay.airplay.CarPlayUiScale
import app.tvplay.media.MediaAudioBuffer
import app.tvplay.network.WifiP2pChannels
import app.tvplay.orchestration.WirelessHotspotMode
import app.tvplay.settings.TvPlaySettingsRepository

/**
 * Right pane of the 10-foot TVPlay Workbench:
 * - D-pad friendly 4-tab bar (`CONNECTION`, `DISPLAY`, `AUDIO`, `ABOUT`)
 * - Structured setting cards backed by [TvPlaySettingsRepository]
 */
internal class WorkbenchSettingsPane(
    private val context: Context,
    private val settings: TvPlaySettingsRepository,
) {
    internal interface Callbacks {
        fun onCategorySelected(category: SettingsCategory)
        fun onRenderRequested()
        fun onMarkReconnectNeeded()
        fun onShowExistingWifiDialog()
        fun onShowPairedPhonePickerDialog()
        fun onRequestPermissions(permissions: Array<String>)
        fun onOpenPictureControls()
        fun onPlayTestTone(streamType: Int)
        fun onShowDiagnosticReport()
        fun onExportDiagnosticReport()
        fun onToast(message: String)
    }

    fun buildTabBar(
        selectedCategory: SettingsCategory,
        palette: TvPlayPalette,
        callbacks: Callbacks,
    ): View {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val categories = listOf(
            SettingsCategory.CONNECTION,
            SettingsCategory.DISPLAY,
            SettingsCategory.AUDIO,
            SettingsCategory.ABOUT,
        )
        categories.forEachIndexed { index, category ->
            val selected = category == selectedCategory
            val tabButton = TvPlayButton(context).apply {
                text = category.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                minHeight = dp(50)
                minimumHeight = dp(50)
                setPadding(dp(22), dp(10), dp(22), dp(10))
                isFocusable = true
                style(
                    if (selected) TvPlayButtonKind.PRIMARY else TvPlayButtonKind.SECONDARY,
                    palette,
                    overlay = false,
                )
                setOnClickListener {
                    callbacks.onCategorySelected(category)
                }
            }
            bar.addView(
                tabButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(50),
                ).apply {
                    if (index > 0) marginStart = dp(10)
                },
            )
        }
        scroll.addView(bar)
        return scroll
    }

    fun buildContent(
        category: SettingsCategory,
        palette: TvPlayPalette,
        callbacks: Callbacks,
    ): View {
        return when (category) {
            SettingsCategory.CONNECTION -> buildConnectionTab(palette, callbacks)
            SettingsCategory.DISPLAY -> buildDisplayTab(palette, callbacks)
            SettingsCategory.AUDIO -> buildAudioTab(palette, callbacks)
            SettingsCategory.ABOUT, SettingsCategory.ADVANCED -> buildAboutTab(palette, callbacks)
        }
    }

    private fun buildConnectionTab(palette: TvPlayPalette, callbacks: Callbacks): View {
        val column = column()

        // Card 1: Network & Transport
        val netCard = card(palette)
        netCard.addView(label("连接模式与无线通道", 19, palette.primaryText, bold = true))
        netCard.addView(space(dp(6)))
        netCard.addView(label("针对智能电视、机顶盒与投影仪优化的无线局域网与 USB 投屏通道设置。", 14, palette.secondaryText))
        netCard.addView(space(dp(14)))

        val defaultMode = settings.connection.defaultConnectionMode
        val defaultModeLabel = when (defaultMode) {
            DefaultConnectionMode.WIRELESS -> "无线 CarPlay（推荐）"
            DefaultConnectionMode.USB -> "USB 有线连接"
            DefaultConnectionMode.LAST_USED -> "跟随上次使用方式"
        }
        netCard.addView(
            settingButtonRow("默认连接方式", defaultModeLabel, palette) {
                val next = when (defaultMode) {
                    DefaultConnectionMode.LAST_USED -> DefaultConnectionMode.WIRELESS
                    DefaultConnectionMode.WIRELESS -> DefaultConnectionMode.USB
                    DefaultConnectionMode.USB -> DefaultConnectionMode.LAST_USED
                }
                settings.connection.defaultConnectionMode = next
                settings.connection.wirelessEnabled = next != DefaultConnectionMode.USB
                callbacks.onRenderRequested()
            },
        )
        netCard.addView(space(dp(10)))

        val hotspotMode = settings.connection.wirelessHotspotMode
        val hotspotModeLabel = when (hotspotMode) {
            WirelessHotspotMode.EXISTING_WIFI -> "同一局域网 Wi-Fi（推荐电视/盒子）"
            WirelessHotspotMode.WIFI_P2P -> "Wi-Fi Direct 5GHz 直连"
            else -> "手动无线热点"
        }
        netCard.addView(
            settingButtonRow("无线网络传输通道", hotspotModeLabel, palette) {
                val next = if (hotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
                    WirelessHotspotMode.WIFI_P2P
                } else {
                    WirelessHotspotMode.EXISTING_WIFI
                }
                settings.connection.wirelessHotspotMode = next
                callbacks.onMarkReconnectNeeded()
            },
        )

        if (hotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
            netCard.addView(space(dp(10)))
            val ssid = settings.connection.existingWifiSsid.ifBlank { "自动检测当前局域网" }
            netCard.addView(
                settingButtonRow("家庭局域网 Wi-Fi 名称 (SSID)", ssid, palette) {
                    callbacks.onShowExistingWifiDialog()
                },
            )
        } else if (hotspotMode == WirelessHotspotMode.WIFI_P2P) {
            netCard.addView(space(dp(10)))
            val p2pChannel = settings.connection.wifiP2pPreferredChannel
            val p2pLabel = when (p2pChannel) {
                WifiP2pChannels.AUTO -> "自动选择 5GHz 信道"
                36 -> "5GHz 信道 36 (5180 MHz)"
                149 -> "5GHz 信道 149 (5745 MHz)"
                else -> "信道 $p2pChannel"
            }
            netCard.addView(
                settingButtonRow("Wi-Fi Direct 5GHz 信道偏好", p2pLabel, palette) {
                    val next = when (p2pChannel) {
                        WifiP2pChannels.AUTO -> 36
                        36 -> 149
                        else -> WifiP2pChannels.AUTO
                    }
                    settings.connection.wifiP2pPreferredChannel = next
                    callbacks.onMarkReconnectNeeded()
                },
            )
        }

        netCard.addView(space(dp(10)))
        val missingPerms = WirelessPermissions.required(hotspotMode, Build.VERSION.SDK_INT).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        val permValue = if (missingPerms.isEmpty()) "已全部授权" else "待授权 (${missingPerms.size} 项)"
        netCard.addView(
            settingButtonRow("无线投屏系统权限", permValue, palette) {
                if (missingPerms.isEmpty()) {
                    callbacks.onToast("蓝牙与局域网无线投屏权限均已就绪")
                } else {
                    callbacks.onRequestPermissions(missingPerms.toTypedArray())
                }
            },
        )

        column.addView(netCard)
        column.addView(space(dp(16)))

        // Card 2: Automation & Paired Devices
        val autoCard = card(palette)
        autoCard.addView(label("自动连接与配对管理", 19, palette.primaryText, bold = true))
        autoCard.addView(space(dp(14)))

        val autoConnect = settings.connection.autoConnect
        autoCard.addView(
            settingToggleRow(
                title = "打开 TVPlay 时自动进入全屏投屏",
                subtitle = "启动应用后直接进入 CarPlay 全屏等待/投屏界面",
                checked = autoConnect,
                palette = palette,
            ) {
                settings.connection.autoConnect = !autoConnect
                callbacks.onRenderRequested()
            },
        )
        autoCard.addView(space(dp(10)))

        val autoBoot = settings.connection.autoStartOnBoot
        autoCard.addView(
            settingToggleRow(
                title = "电视 / 盒子开机时自动启动 TVPlay",
                subtitle = "收到系统开机广播后自动拉起 TVPlay",
                checked = autoBoot,
                palette = palette,
            ) {
                settings.connection.autoStartOnBoot = !autoBoot
                callbacks.onRenderRequested()
            },
        )
        autoCard.addView(space(dp(10)))

        val connectOnBt = settings.connection.connectOnPhoneBluetooth
        autoCard.addView(
            settingToggleRow(
                title = "蓝牙连上 iPhone 时自动启动投屏",
                subtitle = "当电视蓝牙检测到已配对的 iPhone 连接时自动打开 CarPlay",
                checked = connectOnBt,
                palette = palette,
            ) {
                settings.connection.connectOnPhoneBluetooth = !connectOnBt
                callbacks.onRenderRequested()
            },
        )
        autoCard.addView(space(dp(10)))

        val savedPhoneName = settings.connection.pairedPhoneName
        val savedPhoneAddr = settings.connection.pairedPhoneAddress
        val phoneSummary = if (savedPhoneAddr.isNullOrBlank()) {
            "自动接受任意配对 iPhone"
        } else {
            "$savedPhoneName ($savedPhoneAddr)"
        }
        autoCard.addView(
            settingButtonRow("目标配对 iPhone", phoneSummary, palette) {
                callbacks.onShowPairedPhonePickerDialog()
            },
        )
        autoCard.addView(space(dp(10)))

        val hasLockdown = settings.identity.lockdownRecord != null
        autoCard.addView(
            settingButtonRow(
                "重置 iPhone 配对与信任记录",
                if (hasLockdown) "已保存配对证书 · 点击清除" else "清空历史配对密钥",
                palette,
            ) {
                settings.identity.lockdownRecord = null
                callbacks.onToast("已清除历史 iPhone 配对信任记录，下次连接将重新配对")
                callbacks.onRenderRequested()
            },
        )

        column.addView(autoCard)
        return column
    }

    private fun buildDisplayTab(palette: TvPlayPalette, callbacks: Callbacks): View {
        val column = column()

        // Card 1: Resolution, FPS, Codec
        val videoCard = card(palette)
        videoCard.addView(label("分辨率、帧率与解码器", 19, palette.primaryText, bold = true))
        videoCard.addView(space(dp(6)))
        videoCard.addView(label("调整 CarPlay 视频流分辨率、高帧率与硬件解码策略（重新连接后生效）。", 14, palette.secondaryText))
        videoCard.addView(space(dp(14)))

        val scalePercent = settings.display.displayScalePercent
        val scaleLabel = when (scalePercent) {
            100 -> "100% 原生推荐"
            125 -> "125% 超清锐利"
            85 -> "85% 流畅优先"
            else -> "$scalePercent%"
        }
        videoCard.addView(
            settingButtonRow("画面分辨率缩放", scaleLabel, palette) {
                val next = when (scalePercent) {
                    100 -> 125
                    125 -> 85
                    else -> 100
                }
                settings.display.displayScalePercent = next
                callbacks.onMarkReconnectNeeded()
            },
        )
        videoCard.addView(space(dp(10)))

        val fps = settings.display.fps
        videoCard.addView(
            settingButtonRow("视频传输帧率", if (fps >= 60) "60 FPS 高刷流畅" else "30 FPS 稳定兼容", palette) {
                val next = if (fps >= 60) 30 else 60
                settings.display.fps = next
                callbacks.onMarkReconnectNeeded()
            },
        )
        videoCard.addView(space(dp(10)))

        val hevc = settings.display.hevcEnabled
        videoCard.addView(
            settingButtonRow("视频编码格式", if (hevc) "HEVC (H.265) 超清低码率" else "H.264 标准高兼容", palette) {
                settings.display.hevcEnabled = !hevc
                callbacks.onMarkReconnectNeeded()
            },
        )
        videoCard.addView(space(dp(10)))

        val smoothVideo = settings.display.smoothVideo
        videoCard.addView(
            settingToggleRow(
                title = "平滑视频帧步进输出 (Smooth Video)",
                subtitle = "消除无线网络抖动引起的画面微卡顿，适合大屏电视播放",
                checked = smoothVideo,
                palette = palette,
            ) {
                settings.display.smoothVideo = !smoothVideo
                callbacks.onMarkReconnectNeeded()
            },
        )
        videoCard.addView(space(dp(10)))

        val lowLatency = settings.display.lowLatencyDecoder
        videoCard.addView(
            settingToggleRow(
                title = "超低延迟硬件解码器 (Low Latency)",
                subtitle = "启用 Android 硬件解码器低延迟模式，降低遥控与触控响应延迟",
                checked = lowLatency,
                palette = palette,
            ) {
                settings.display.lowLatencyDecoder = !lowLatency
                callbacks.onMarkReconnectNeeded()
            },
        )

        column.addView(videoCard)
        column.addView(space(dp(16)))

        // Card 2: Layout, Appearance, Picture Tuning
        val uiCard = card(palette)
        uiCard.addView(label("大屏界面布局与色彩调节", 19, palette.primaryText, bold = true))
        uiCard.addView(space(dp(14)))

        val uiScale = settings.display.uiScalePercent
        val uiScaleLabel = when (uiScale) {
            100 -> "100% 标准大屏布局"
            115 -> "115% 图标与文字放大"
            85 -> "85% 紧凑显示更多内容"
            else -> "$uiScale%"
        }
        uiCard.addView(
            settingButtonRow("CarPlay 界面与图标缩放", uiScaleLabel, palette) {
                val next = when (uiScale) {
                    100 -> 115
                    115 -> 85
                    else -> CarPlayUiScale.DEFAULT
                }
                settings.display.uiScalePercent = next
                callbacks.onMarkReconnectNeeded()
            },
        )
        uiCard.addView(space(dp(10)))

        val nightMode = settings.display.carPlayNightMode
        val nightLabel = when (nightMode) {
            CarPlayNightMode.NIGHT -> "始终深色（推荐电视使用）"
            CarPlayNightMode.DAY -> "始终浅色"
            else -> "跟随系统深浅色"
        }
        uiCard.addView(
            settingButtonRow("CarPlay 深浅色外观", nightLabel, palette) {
                val next = when (nightMode) {
                    CarPlayNightMode.NIGHT -> CarPlayNightMode.SYSTEM
                    CarPlayNightMode.SYSTEM -> CarPlayNightMode.DAY
                    else -> CarPlayNightMode.NIGHT
                }
                settings.display.carPlayNightMode = next
                callbacks.onRenderRequested()
            },
        )
        uiCard.addView(space(dp(10)))

        val appAppearance = settings.display.appAppearance
        val appAppearanceLabel = when (appAppearance) {
            AppAppearance.DARK -> "深色影院主题"
            AppAppearance.LIGHT -> "浅色明亮主题"
            AppAppearance.AUTO -> "跟随系统"
        }
        uiCard.addView(
            settingButtonRow("TVPlay 工作台主题", appAppearanceLabel, palette) {
                val next = when (appAppearance) {
                    AppAppearance.DARK -> AppAppearance.LIGHT
                    AppAppearance.LIGHT -> AppAppearance.AUTO
                    AppAppearance.AUTO -> AppAppearance.DARK
                }
                settings.display.appAppearance = next
                callbacks.onRenderRequested()
            },
        )
        uiCard.addView(space(dp(10)))

        val rightDock = settings.display.rightHandDrive
        uiCard.addView(
            settingButtonRow("CarPlay 状态栏 Dock 位置", if (rightDock) "画面右侧" else "画面左侧", palette) {
                settings.display.rightHandDrive = !rightDock
                callbacks.onMarkReconnectNeeded()
            },
        )
        uiCard.addView(space(dp(10)))

        val cornerRadius = settings.display.blackCornerRadiusDp
        val cornerLabel = if (cornerRadius == 0) "直角全屏 (0 dp)" else "圆角遮罩 ($cornerRadius dp)"
        uiCard.addView(
            settingButtonRow("电视画面四角圆角", cornerLabel, palette) {
                val radii = CarPlayBlackCornerOverlay.RADII_DP
                val idx = radii.indexOf(cornerRadius)
                val next = radii[(idx + 1) % radii.size]
                settings.display.blackCornerRadiusDp = next
                callbacks.onRenderRequested()
            },
        )
        uiCard.addView(space(dp(10)))

        val fpsCounter = settings.display.fpsCounterEnabled
        uiCard.addView(
            settingToggleRow(
                title = "显示实时帧率与解码状态浮窗 (FPS Counter)",
                subtitle = "在全屏投屏画面右上角实时显示接收帧率与渲染帧率",
                checked = fpsCounter,
                palette = palette,
            ) {
                settings.display.fpsCounterEnabled = !fpsCounter
                callbacks.onRenderRequested()
            },
        )
        uiCard.addView(space(dp(10)))

        uiCard.addView(
            settingButtonRow("画面色彩调节面板", "亮度 · 对比度 · 饱和度 · 色温", palette) {
                callbacks.onOpenPictureControls()
            },
        )

        column.addView(uiCard)
        return column
    }

    private fun buildAudioTab(palette: TvPlayPalette, callbacks: Callbacks): View {
        val column = column()

        // Card 1: Microphone & Siri + Buffering
        val voiceCard = card(palette)
        voiceCard.addView(label("语音助手与无线音频缓冲", 19, palette.primaryText, bold = true))
        voiceCard.addView(space(dp(6)))
        voiceCard.addView(label("管理电视/遥控器麦克风 Siri 语音唤醒及无线音乐抗抖动缓冲。", 14, palette.secondaryText))
        voiceCard.addView(space(dp(14)))

        val micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        voiceCard.addView(
            settingButtonRow(
                "麦克风与 Siri 语音支持",
                if (micGranted) "已授权 · 支持语音键唤醒 Siri" else "未授权 · 点击授予麦克风权限",
                palette,
            ) {
                if (micGranted) {
                    callbacks.onToast("麦克风权限已开启，可在全屏投屏时按遥控器语音键或点击 Siri 图标唤醒")
                } else {
                    callbacks.onRequestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
            },
        )
        voiceCard.addView(space(dp(10)))

        val wirelessBufferMs = settings.audio.loadMediaBufferMillis(wireless = true)
        voiceCard.addView(
            settingButtonRow("无线音乐抗抖动缓冲", "${wirelessBufferMs} ms", palette) {
                val options = listOf(120, 200, 320, 500)
                val next = options[(options.indexOf(wirelessBufferMs) + 1) % options.size]
                settings.audio.saveMediaBufferMillis(
                    wireless = true,
                    millis = MediaAudioBuffer.sanitizeWireless(next),
                )
                callbacks.onMarkReconnectNeeded()
            },
        )
        voiceCard.addView(space(dp(10)))

        val bufferedMusic = settings.audio.mainBufferedAudio
        voiceCard.addView(
            settingToggleRow(
                title = "Apple Music 高品质 TCP 预缓冲 (Experimental)",
                subtitle = "允许 iPhone 提前通过 TCP 预缓冲音频流，减少家庭 Wi-Fi 波动断音",
                checked = bufferedMusic,
                palette = palette,
            ) {
                settings.audio.mainBufferedAudio = !bufferedMusic
                callbacks.onMarkReconnectNeeded()
            },
        )

        column.addView(voiceCard)
        column.addView(space(dp(16)))

        // Card 2: TV Speaker & Audio Focus
        val speakerCard = card(palette)
        speakerCard.addView(label("电视音响输出与音频焦点", 19, palette.primaryText, bold = true))
        speakerCard.addView(space(dp(14)))

        val audioFocus = settings.audio.audioFocusEnabled
        speakerCard.addView(
            settingToggleRow(
                title = "自动管理 Android 音频焦点",
                subtitle = "开启后 CarPlay 播放音乐时会自动暂停电视后台其他应用声音",
                checked = audioFocus,
                palette = palette,
            ) {
                settings.audio.audioFocusEnabled = !audioFocus
                callbacks.onRenderRequested()
            },
        )
        speakerCard.addView(space(dp(10)))

        val disableMediaKeyFocus = settings.audio.disableMediaKeyAudioFocus
        speakerCard.addView(
            settingToggleRow(
                title = "释放媒体按键独立音频焦点",
                subtitle = "避免部分电视盒固件将遥控器按键强行路由到系统自带播放器",
                checked = disableMediaKeyFocus,
                palette = palette,
            ) {
                settings.audio.disableMediaKeyAudioFocus = !disableMediaKeyFocus
                callbacks.onRenderRequested()
            },
        )
        speakerCard.addView(space(dp(10)))

        val btDirectAudio = settings.audio.carBluetoothAudio
        speakerCard.addView(
            settingToggleRow(
                title = "由 iPhone 蓝牙直连电视/音响输出声音",
                subtitle = "开启后 CarPlay 仅传输画面，声音保持在 iPhone 蓝牙连接的音响上",
                checked = btDirectAudio,
                palette = palette,
            ) {
                settings.audio.carBluetoothAudio = !btDirectAudio
                callbacks.onMarkReconnectNeeded()
            },
        )
        speakerCard.addView(space(dp(10)))

        speakerCard.addView(
            settingButtonRow("扬声器声道测试", "播放测试提示音", palette) {
                callbacks.onPlayTestTone(AudioManager.STREAM_MUSIC)
            },
        )

        column.addView(speakerCard)
        return column
    }

    private fun buildAboutTab(palette: TvPlayPalette, callbacks: Callbacks): View {
        val column = column()

        // Card 1: Remote Control Guide
        val remoteCard = card(palette)
        remoteCard.addView(label("电视遥控器操控指南", 19, palette.primaryText, bold = true))
        remoteCard.addView(space(dp(12)))
        remoteCard.addView(stepRow("▲▼◀▶", "方向键焦点漫游", "在 CarPlay 桌面图标、地图按钮、音乐控制项之间平滑移动高亮焦点。", palette))
        remoteCard.addView(space(dp(8)))
        remoteCard.addView(stepRow("OK", "确认键触发点击", "打开当前选中的 CarPlay 应用或触发按钮。", palette))
        remoteCard.addView(space(dp(8)))
        remoteCard.addView(stepRow("MENU", "菜单键 / 长按返回键", "在全屏投屏时随时呼出 Apple TV 风格快捷控制中心，一键调节画质、帧率或返回工作台。", palette))
        remoteCard.addView(space(dp(8)))
        remoteCard.addView(stepRow("MIC", "语音键唤醒 Siri", "按遥控器语音/搜索键直接唤醒 CarPlay Siri 语音助手。", palette))

        column.addView(remoteCard)
        column.addView(space(dp(16)))

        // Card 2: Version & Diagnostics
        val diagCard = card(palette)
        diagCard.addView(label("关于 TVPlay 与运行诊断", 19, palette.primaryText, bold = true))
        diagCard.addView(space(dp(10)))

        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
        val deviceId = runCatching {
            TvPlayBootstrap.deviceId(settings.identity.loadAirPlayIdentity())
        }.getOrNull() ?: "未知"

        diagCard.addView(label("版本：TVPlay $versionName (${context.packageName})", 14, palette.primaryText))
        diagCard.addView(space(dp(4)))
        diagCard.addView(label("认证引擎：内置离线 MFi 证书 (offline-mfi) · 设备标识 $deviceId", 13, palette.secondaryText))
        diagCard.addView(space(dp(14)))

        val debugLogs = settings.identity.debugLogsEnabled
        diagCard.addView(
            settingToggleRow(
                title = "记录详细运行诊断日志",
                subtitle = "在应用私有目录记录脱敏的无线握手与视频解码日志，便于排查连接问题",
                checked = debugLogs,
                palette = palette,
            ) {
                settings.identity.debugLogsEnabled = !debugLogs
                callbacks.onRenderRequested()
            },
        )
        diagCard.addView(space(dp(10)))

        diagCard.addView(
            settingButtonRow("查看运行诊断报告", "在弹窗中查看最新连接与解码状态", palette) {
                callbacks.onShowDiagnosticReport()
            },
        )
        diagCard.addView(space(dp(10)))

        diagCard.addView(
            settingButtonRow("导出诊断报告到存储", "保存至 Downloads/DiPlay 目录", palette) {
                callbacks.onExportDiagnosticReport()
            },
        )

        column.addView(diagCard)
        return column
    }

    private fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun card(palette: TvPlayPalette): LinearLayout = column().apply {
        setPadding(dp(22), dp(20), dp(22), dp(20))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setColor(palette.surface)
            setStroke(dp(1), palette.outline)
        }
    }

    private fun stepRow(badgeText: String, title: String, subtitle: String, palette: TvPlayPalette): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val pill = TextView(context).apply {
            text = badgeText
            gravity = Gravity.CENTER
            setTextColor(palette.onAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(palette.accent)
            }
        }
        val textCol = column().apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        textCol.addView(label(title, 15, palette.primaryText, bold = true))
        textCol.addView(space(dp(2)))
        textCol.addView(label(subtitle, 13, palette.secondaryText))

        container.addView(pill)
        container.addView(textCol)
        return container
    }

    private fun settingButtonRow(
        title: String,
        value: String,
        palette: TvPlayPalette,
        onClick: () -> Unit,
    ): View = TvPlayButton(context).apply {
        text = "$title · $value"
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        minHeight = dp(54)
        minimumHeight = dp(54)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        isFocusable = true
        style(TvPlayButtonKind.SECONDARY, palette, overlay = false)
        setOnClickListener { onClick() }
    }

    private fun settingToggleRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        palette: TvPlayPalette,
        onToggle: () -> Unit,
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(18), dp(14), dp(18), dp(14))
            val radius = dp(18).toFloat()
            background = RippleDrawable(
                ColorStateList.valueOf(palette.ripple),
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radius
                    setColor(palette.button)
                    setStroke(dp(1), if (checked) palette.accent else palette.outline)
                },
                null,
            )
            foreground = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = radius
                        setColor(ColorUtils.setAlphaComponent(palette.focusRing, 0x22))
                        setStroke(dp(4), palette.focusRing)
                    },
                )
            }
            setOnClickListener { onToggle() }
        }

        val textCol = column().apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(14)
            }
        }
        textCol.addView(label(title, 15, palette.primaryText, bold = true))
        textCol.addView(space(dp(3)))
        textCol.addView(label(subtitle, 13, palette.secondaryText))

        val pill = TextView(context).apply {
            text = if (checked) "已开启" else "已关闭"
            setTextColor(if (checked) palette.onAccent else palette.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(if (checked) palette.accent else palette.trackOff)
            }
        }

        container.addView(textCol)
        container.addView(pill)
        return container
    }

    private fun label(text: String, sizeSp: Int, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
            if (bold) {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
        }

    private fun space(heightPx: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
