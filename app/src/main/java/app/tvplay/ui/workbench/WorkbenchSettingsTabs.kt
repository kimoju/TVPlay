package app.tvplay.ui.workbench

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import app.tvplay.config.TvSettingsRepository
import app.tvplay.config.VideoSurfaceType
import app.tvplay.config.WaitingScreenColorPreset
import app.tvplay.engine.av.CarPlayPhysicalSizePreset
import app.tvplay.engine.av.MediaAudioBuffer
import app.tvplay.engine.av.UiDensityScale
import app.tvplay.engine.network.WifiP2pChannels
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.remote.CarPlayNightMode
import app.tvplay.ui.theme.AppAppearance
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.ui.theme.TvPlayButton
import app.tvplay.ui.theme.TvPlayButtonKind

enum class SettingsCategory(val title: String) {
    CONNECTION("连接与配对"),
    DISPLAY("显示与画质"),
    AUDIO("音频与麦克风"),
    ABOUT("系统与诊断"),
}

typealias WorkbenchSettingsPane = WorkbenchSettingsTabs

/**
 * Right-pane tabbed settings panel for the 10-foot Android TV workbench.
 *
 * Designed specifically for D-pad navigation with clear focus rings, high-contrast cards,
 * and structured categories ([SettingsCategory]).
 */
class WorkbenchSettingsTabs(
    private val context: Context,
    private val settings: TvSettingsRepository,
) {
    private val density = context.resources.displayMetrics.density
    private val palette = TvColorScheme.resolve(context)

    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(palette.surfaceContainer)
            setStroke((1f * density).toInt().coerceAtLeast(1), palette.outline)
        }
        setPadding(dp(22), dp(20), dp(22), dp(20))
    }

    private val tabStripRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val tabButtons = LinkedHashMap<SettingsCategory, TextView>()
    private val contentContainer = FrameLayout(context)
    private var selectedCategory: SettingsCategory = SettingsCategory.CONNECTION

    var onChooseIphoneClicked: (() -> Unit)? = null
    var onPairBluetoothClicked: (() -> Unit)? = null
    var onResetWirelessHotspotClicked: (() -> Unit)? = null
    var onSaveDiagnosticLogClicked: (() -> Unit)? = null
    var onOpenPictureAdjustmentClicked: (() -> Unit)? = null
    var onSettingChanged: (() -> Unit)? = null
    var onAppearanceChanged: (() -> Unit)? = null

    init {
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerTitle = TextView(context).apply {
            text = "TVPlay 接收端参数设置"
            setTextColor(palette.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val headerHint = TextView(context).apply {
            text = "使用遥控器方向键 ▲▼◀▶ 与确认键调节"
            setTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.END
        }
        headerRow.addView(headerTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        headerRow.addView(headerHint, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(headerRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        SettingsCategory.entries.forEachIndexed { index, category ->
            val tab = createTabChip(category)
            tabButtons[category] = tab
            tabStripRow.addView(
                tab,
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    if (index > 0) marginStart = dp(8)
                },
            )
        }
        tabScroll.addView(tabStripRow, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(
            tabScroll,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(14)
                bottomMargin = dp(14)
            },
        )

        root.addView(
            contentContainer,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )

        selectCategory(SettingsCategory.CONNECTION)
    }

    fun selectCategory(category: SettingsCategory) {
        val sameCategory = selectedCategory == category
        val focusedTag = if (sameCategory) contentContainer.findFocus()?.tag as? String else null
        val previousScrollY = if (sameCategory) {
            (contentContainer.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        } else {
            0
        }
        selectedCategory = category
        tabButtons.forEach { (cat, chip) ->
            styleTabChip(chip, selected = cat == category, focused = chip.isFocused)
        }
        contentContainer.removeAllViews()
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = true
            clipToPadding = false
        }
        val page = when (category) {
            SettingsCategory.CONNECTION -> buildConnectionPage()
            SettingsCategory.DISPLAY -> buildDisplayPage()
            SettingsCategory.AUDIO -> buildAudioPage()
            SettingsCategory.ABOUT -> buildAboutPage()
        }
        scroll.addView(page, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        contentContainer.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (previousScrollY > 0) {
            scroll.scrollTo(0, previousScrollY)
        }
        if (focusedTag != null) {
            page.findViewWithTag<View>(focusedTag)?.requestFocus()
        }
    }

    fun refresh() {
        selectCategory(selectedCategory)
    }

    private fun createTabChip(category: SettingsCategory): TextView =
        TextView(context).apply {
            text = category.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            setOnClickListener { selectCategory(category) }
            setOnFocusChangeListener { _, hasFocus ->
                styleTabChip(this, selected = selectedCategory == category, focused = hasFocus)
                if (hasFocus && selectedCategory != category) {
                    selectCategory(category)
                }
            }
            styleTabChip(this, selected = selectedCategory == category, focused = false)
        }

    private fun styleTabChip(view: TextView, selected: Boolean, focused: Boolean) {
        val fillColor = when {
            selected -> palette.accent
            focused -> palette.surfaceCardElevated
            else -> palette.surfaceCard
        }
        val textColor = if (selected) 0xFFFFFFFF.toInt() else palette.textPrimary
        val strokeColor = if (focused) 0xFFFFFFFF.toInt() else if (selected) palette.accent else palette.outline
        val strokeWidth = if (focused) dp(2) else dp(1)
        view.setTextColor(textColor)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f * density
            setColor(fillColor)
            setStroke(strokeWidth, strokeColor)
        }
        view.animate()
            .scaleX(if (focused) 1.04f else 1.0f)
            .scaleY(if (focused) 1.04f else 1.0f)
            .setDuration(110L)
            .start()
    }

    private fun buildConnectionPage(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(4), dp(4), dp(16))

        addView(sectionHeader("主连接方式（无线 / USB 有线）"))
        val currentTransportKey = when {
            !settings.connection.wirelessEnabled -> "USB"
            settings.connection.wirelessHotspotMode == TvWirelessMode.LAN_WIFI -> "LAN"
            else -> "DIRECT"
        }
        val transportOptions = listOf(
            "DIRECT" to "无线 · Wi-Fi Direct 直连",
            "LAN" to "无线 · 家庭局域网",
            "USB" to "USB 有线数据线直连",
        )
        fun applyTransportKey(key: String) {
            when (key) {
                "DIRECT" -> {
                    settings.connection.wirelessEnabled = true
                    settings.connection.wirelessHotspotMode = TvWirelessMode.WIFI_DIRECT
                }
                "LAN" -> {
                    settings.connection.wirelessEnabled = true
                    settings.connection.wirelessHotspotMode = TvWirelessMode.LAN_WIFI
                }
                "USB" -> {
                    settings.connection.wirelessEnabled = false
                }
            }
            onSettingChanged?.invoke()
            refresh()
        }
        addView(
            cycleCard(
                title = "CarPlay 连接模式",
                subtitle = "按确认键弹出模式选择，或按左右方向键 ◀▶ 切换无线 Wi-Fi Direct、家庭局域网与 USB 有线模式",
                options = transportOptions,
                current = currentTransportKey,
                onClickOverride = {
                    val dialogItems = arrayOf(
                        "无线 · Wi-Fi Direct 5GHz 直连（推荐：免路由器密码，点对点直连）",
                        "无线 · 家庭局域网模式（电视与 iPhone 连接同一 Wi-Fi 或网线）",
                        "USB 有线数据线直连（使用 Lightning / USB-C 数据线直连电视 USB 接口）",
                    )
                    val checkedIndex = transportOptions.indexOfFirst { it.first == currentTransportKey }.coerceAtLeast(0)
                    android.app.AlertDialog.Builder(context)
                        .setTitle("选择 CarPlay 连接模式")
                        .setSingleChoiceItems(dialogItems, checkedIndex) { dialog, which ->
                            dialog.dismiss()
                            val chosenKey = transportOptions.getOrNull(which)?.first ?: return@setSingleChoiceItems
                            applyTransportKey(chosenKey)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                },
            ) { key ->
                applyTransportKey(key)
            },
            cardLayoutParams(),
        )

        addView(sectionHeader("蓝牙配对与目标 iPhone"))
        val phoneAddress = settings.connection.pairedPhoneAddress
        val phoneSubtitle = if (phoneAddress != null) {
            "已指定：${settings.connection.pairedPhoneName} ($phoneAddress)"
        } else {
            "自动识别已配对的 iPhone（可点击手动指定）"
        }
        addView(
            actionCard(
                title = "目标 iPhone 设备",
                subtitle = phoneSubtitle,
                buttonLabel = "选择 iPhone",
                onClick = { onChooseIphoneClicked?.invoke() },
            ),
            cardLayoutParams(),
        )

        addView(
            actionCard(
                title = "电视系统蓝牙配对",
                subtitle = "打开 Android TV 系统蓝牙设置，与新的 iPhone 或遥控器配对",
                buttonLabel = "打开蓝牙设置",
                onClick = { onPairBluetoothClicked?.invoke() },
            ),
            cardLayoutParams(),
        )

        addView(sectionHeader("无线传输通道（电视端专属优化）"))
        val wirelessModes = listOf(
            TvWirelessMode.WIFI_DIRECT to "Wi-Fi Direct 5GHz 直连（推荐 · 免配置）",
            TvWirelessMode.LAN_WIFI to "家庭局域网 Wi-Fi / 网线直连",
        )
        addView(
            cycleCard(
                title = "无线网络传输方式",
                subtitle = "Wi-Fi Direct 自动建立点对点连接；家庭局域网通过同一路由器或网线互联",
                options = wirelessModes,
                current = settings.connection.wirelessHotspotMode,
            ) { mode ->
                settings.connection.wirelessEnabled = true
                settings.connection.wirelessHotspotMode = mode
                onSettingChanged?.invoke()
                refresh()
            },
            cardLayoutParams(),
        )

        when (settings.connection.wirelessHotspotMode) {
            TvWirelessMode.LAN_WIFI -> {
                addView(
                    textFieldCard(
                        title = "家庭路由器 Wi-Fi 名称 (SSID)",
                        subtitle = "填写 iPhone 与电视共同连接的路由器 Wi-Fi 名称（无线握手时需告知 iPhone）",
                        initialValue = settings.connection.existingWifiSsid,
                        hint = "填写家庭路由器 Wi-Fi 名称",
                        secret = false,
                    ) { value ->
                        settings.connection.existingWifiSsid = value.trim()
                        onSettingChanged?.invoke()
                    },
                    cardLayoutParams(),
                )
                addView(
                    textFieldCard(
                        title = "家庭路由器 Wi-Fi 密码",
                        subtitle = "填写该家庭路由器的 WPA2 密码（若为开放无密码网络可留空）",
                        initialValue = settings.connection.existingWifiPassphrase,
                        hint = "填写家庭路由器 Wi-Fi 密码",
                        secret = true,
                    ) { value ->
                        settings.connection.existingWifiPassphrase = value
                        onSettingChanged?.invoke()
                    },
                    cardLayoutParams(),
                )
                addView(
                    textFieldCard(
                        title = "指定网卡接口名称（高级选项）",
                        subtitle = "默认留空即可自动识别 wlan0 或 eth0 有线网卡",
                        initialValue = settings.connection.existingWifiInterface,
                        hint = "自动识别（wlan0 / eth0）",
                        secret = false,
                    ) { value ->
                        settings.connection.existingWifiInterface = value.trim()
                        onSettingChanged?.invoke()
                    },
                    cardLayoutParams(),
                )
            }

            TvWirelessMode.WIFI_DIRECT -> {
                val selectableChannels = listOf(WifiP2pChannels.AUTO) +
                    WifiP2pChannels.bandChoices +
                    WifiP2pChannels.channels
                val channelOptions = selectableChannels.map { channel ->
                    channel to when (channel) {
                        WifiP2pChannels.AUTO -> "自动优选（优先 5 GHz）"
                        WifiP2pChannels.AUTO_5_GHZ -> "自动 · 仅限 5 GHz 频段"
                        WifiP2pChannels.AUTO_2_4_GHZ -> "自动 · 仅限 2.4 GHz 频段"
                        else -> "信道 $channel (${WifiP2pChannels.frequencyMhz(channel)} MHz)"
                    }
                }
                addView(
                    cycleCard(
                        title = "Wi-Fi Direct 首选无线信道",
                        subtitle = "建议保持「自动优选」，或选择干扰较少的 5 GHz 高速信道",
                        options = channelOptions,
                        current = settings.connection.wifiP2pPreferredChannel,
                    ) { channel ->
                        settings.connection.wifiP2pPreferredChannel = channel
                        onSettingChanged?.invoke()
                    },
                    cardLayoutParams(),
                )
                addView(
                    actionCard(
                        title = "重置 Wi-Fi Direct 投屏通道",
                        subtitle = "当无线握手后画面未弹出或热点状态异常时，清理电视端残留的 P2P 组网状态",
                        buttonLabel = "重置通道",
                        onClick = { onResetWirelessHotspotClicked?.invoke() },
                    ),
                    cardLayoutParams(),
                )
            }
        }

        addView(sectionHeader("开机自启与蓝牙自动唤醒"))
        addView(
            toggleCard(
                title = "电视开机时自动启动 TVPlay",
                subtitle = "Android TV 开机完成后自动拉起 TVPlay 服务",
                checked = settings.connection.autoConnectOnBoot,
            ) { enabled ->
                settings.connection.autoConnectOnBoot = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "iPhone 蓝牙连上时自动进入投屏",
                subtitle = "检测到已配对的 iPhone 蓝牙连接时自动开启 CarPlay 投屏舞台",
                checked = settings.connection.connectOnPhoneBluetooth,
            ) { enabled ->
                settings.connection.connectOnPhoneBluetooth = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
    }

    private fun buildDisplayPage(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(4), dp(4), dp(16))

        addView(sectionHeader("视频硬件解码与渲染"))
        addView(
            toggleCard(
                title = "HEVC (H.265) 高清视频编码",
                subtitle = "在 4K 电视硬件解码器上提供更高清晰度与更低无线码率开销",
                checked = settings.display.hevcEnabled,
            ) { enabled ->
                settings.display.hevcEnabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "允许软件解码 HEVC（兼容备用）",
                subtitle = "当电视芯片无硬件 HEVC 解码器时允许回退至 CPU 解码",
                checked = settings.display.hevcSoftwareDecoderEnabled,
            ) { enabled ->
                settings.display.hevcSoftwareDecoderEnabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            cycleCard(
                title = "视频渲染视图类型",
                subtitle = "SurfaceView 延迟最低；TextureView 支持实时色彩与对比度滤镜调节",
                options = listOf(
                    VideoSurfaceType.AUTO to "自动选择（推荐）",
                    VideoSurfaceType.SURFACE_VIEW to "SurfaceView（最低延迟直通）",
                    VideoSurfaceType.TEXTURE_VIEW to "TextureView（支持画面色彩调节）",
                ),
                current = settings.display.videoSurfaceType,
            ) { mode ->
                settings.display.videoSurfaceType = mode
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "平滑帧率同步（防抖缓冲）",
                subtitle = "增加 1 帧平滑队列，提升 60Hz 电视大屏的动画顺滑度",
                checked = settings.display.smoothVideoPlayback,
            ) { enabled ->
                settings.display.smoothVideoPlayback = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "60 FPS 高帧率投屏",
                subtitle = "向 iPhone 请求 60 帧/秒流畅画面（关闭则为 30 FPS）",
                checked = settings.display.frameRate60Enabled,
            ) { enabled ->
                settings.display.frameRate60Enabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )

        addView(sectionHeader("电视画布分辨率与图标布局缩放"))
        addView(
            cycleCard(
                title = "渲染画布分辨率缩放",
                subtitle = "调整 CarPlay 相对于电视物理屏幕的渲染分辨率比例",
                options = listOf(75, 85, 100, 115, 130).map { it to "$it%" },
                current = settings.display.displayScalePercent,
            ) { percent ->
                settings.display.displayScalePercent = percent
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            cycleCard(
                title = "界面图标与字体密度 (DPI)",
                subtitle = "针对客厅 3 米观看距离放大或缩小 CarPlay 侧边栏与图标",
                options = UiDensityScale.presets.map { it to "$it%" },
                current = settings.display.uiDensityPercent,
            ) { percent ->
                settings.display.uiDensityPercent = percent
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            cycleCard(
                title = "CarPlay 屏幕物理尺寸预设",
                subtitle = "模拟不同物理尺寸以控制 CarPlay 分屏卡片与小组件布局",
                options = CarPlayPhysicalSizePreset.entries.map { preset ->
                    preset to when (preset) {
                        CarPlayPhysicalSizePreset.LARGE -> "大图标紧凑布局 (${preset.widthMillimeters} mm)"
                        CarPlayPhysicalSizePreset.MEDIUM -> "客厅电视标准分屏 (${preset.widthMillimeters} mm)"
                        CarPlayPhysicalSizePreset.SMALL -> "宽屏多列仪表盘 (${preset.widthMillimeters} mm)"
                    }
                },
                current = settings.display.physicalSizePreset,
            ) { preset ->
                settings.display.physicalSizePreset = preset
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )

        addView(sectionHeader("电视过扫描与安全边距"))
        addView(
            toggleCard(
                title = "禁用电视安全边距（100% 满屏像素）",
                subtitle = "使用 HDMI 点对点满屏输出，不预留四周安全内边距",
                checked = settings.display.mainDisplayDisableSafeArea,
            ) { disabled ->
                settings.display.mainDisplayDisableSafeArea = disabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "允许壁纸延伸至安全区外",
                subtitle = "保留控件安全边距的同时，让 CarPlay 背景壁纸铺满整块屏幕",
                checked = settings.display.safeAreaDrawOutside,
            ) { drawOutside ->
                settings.display.safeAreaDrawOutside = drawOutside
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )

        addView(sectionHeader("深浅色外观与实时帧率显示"))
        addView(
            cycleCard(
                title = "CarPlay 日间 / 夜间外观",
                subtitle = "控制 CarPlay 地图与界面采用深色或浅色主题",
                options = listOf(
                    CarPlayNightMode.NIGHT to "始终深色（电视端推荐）",
                    CarPlayNightMode.SYSTEM to "跟随系统时间",
                    CarPlayNightMode.DAY to "始终浅色",
                ),
                current = settings.display.carPlayNightMode,
            ) { mode ->
                settings.display.carPlayNightMode = mode
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            cycleCard(
                title = "TVPlay 工作台界面主题",
                subtitle = "设置主界面与弹窗的配色风格",
                options = listOf(
                    AppAppearance.DARK to "深邃暗色（推荐）",
                    AppAppearance.AUTO to "跟随 Android TV 系统",
                    AppAppearance.LIGHT to "明亮浅色",
                ),
                current = settings.display.appAppearance,
            ) { mode ->
                settings.display.appAppearance = mode
                onAppearanceChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            cycleCard(
                title = "等待连接画面背景色",
                subtitle = "等待 iPhone 投屏期间的全屏背景配色",
                options = listOf(
                    WaitingScreenColorPreset.DARK to "深灰石板（默认）",
                    WaitingScreenColorPreset.OLED_BLACK to "OLED 纯黑（防烧屏）",
                    WaitingScreenColorPreset.MIDNIGHT_BLUE to "午夜深蓝",
                    WaitingScreenColorPreset.LIGHT to "柔和浅灰",
                ),
                current = settings.display.waitingScreenColorPreset,
            ) { preset ->
                settings.display.waitingScreenColorPreset = preset
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "右上角实时帧率浮窗 (FPS HUD)",
                subtitle = "投屏时在画面右上角实时显示解码帧率与耗时",
                checked = settings.display.fpsCounterEnabled,
            ) { enabled ->
                settings.display.fpsCounterEnabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
    }

    private fun buildAudioPage(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(4), dp(4), dp(16))

        addView(sectionHeader("电视音频输出与缓冲"))
        val bufferOptions = MediaAudioBuffer.presets.map { ms ->
            ms to if (ms == MediaAudioBuffer.DEFAULT_MILLIS) {
                "$ms 毫秒（默认）"
            } else {
                "$ms 毫秒"
            }
        }
        addView(
            cycleCard(
                title = "无线音乐音频缓冲大小",
                subtitle = "若无线信号拥挤导致音乐断续可适当调大；追求更低延迟可调小",
                options = bufferOptions,
                current = settings.audio.mediaBufferMillis,
            ) { ms ->
                settings.audio.mediaBufferMillis = ms
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "自动管理 Android 音频焦点",
                subtitle = "CarPlay 播放音乐、Siri 或导航播报时自动向电视系统申请音频焦点",
                checked = settings.audio.audioFocusEnabled,
            ) { enabled ->
                settings.audio.audioFocusEnabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "静音空闲时自动释放音频焦点",
                subtitle = "CarPlay 暂停播放后释放音频焦点，方便电视后台其他应用恢复发声",
                checked = settings.audio.audioFocusAutoYield,
            ) { enabled ->
                settings.audio.audioFocusAutoYield = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "转发遥控器媒体键时不抢占焦点",
                subtitle = "适合一边看 CarPlay 画面、一边用电视本地应用听歌的场景",
                checked = settings.audio.disableMediaKeyAudioFocus,
            ) { enabled ->
                settings.audio.disableMediaKeyAudioFocus = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )

        addView(sectionHeader("Siri 语音助手与麦克风输入"))
        addView(
            toggleCard(
                title = "启用电视 / 遥控器语音麦克风",
                subtitle = "将蓝牙语音遥控器或 USB 麦克风音频路由至 CarPlay Siri 与通话",
                checked = settings.audio.microphoneEnabled,
            ) { enabled ->
                settings.audio.microphoneEnabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "启用高容量主音频缓冲通道",
                subtitle = "开启 AirPlay 主媒体流缓冲模式以提升抗抖动能力",
                checked = settings.audio.mainBufferedAudio,
            ) { enabled ->
                settings.audio.mainBufferedAudio = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "保持声音在 iPhone / 外部蓝牙音箱播放",
                subtitle = "禁用电视端音频解码输出，让声音直接从 iPhone 或独立蓝牙音响发出",
                checked = settings.audio.disableAudioOutput,
            ) { disabled ->
                settings.audio.disableAudioOutput = disabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
    }

    private fun buildAboutPage(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(4), dp(4), dp(16))

        addView(sectionHeader("接收端设备标识与离线 MFi"))
        addView(
            textFieldCard(
                title = "广播厂商名称 (Manufacturer)",
                subtitle = "iAP2 握手时向 iPhone 展示的接收端厂商标识",
                initialValue = settings.identity.manufacturer,
                hint = "TVPlay",
                secret = false,
            ) { value ->
                settings.identity.manufacturer = value.ifBlank { "TVPlay" }
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            textFieldCard(
                title = "广播设备型号 (Model)",
                subtitle = "iAP2 握手时向 iPhone 展示的接收端型号标识",
                initialValue = settings.identity.model,
                hint = "TVPlay",
                secret = false,
            ) { value ->
                settings.identity.model = value.ifBlank { "TVPlay" }
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )

        addView(sectionHeader("连接诊断与日志导出"))
        addView(
            toggleCard(
                title = "记录详细会话诊断日志",
                subtitle = "记录脱敏后的 RTSP / iAP2 / 解码器状态事件以便排查连接问题",
                checked = settings.identity.debugLogsEnabled,
            ) { enabled ->
                settings.identity.debugLogsEnabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            actionCard(
                title = "导出诊断日志报告",
                subtitle = "将脱敏后的连接诊断日志保存至电视存储空间",
                buttonLabel = "导出日志",
                onClick = { onSaveDiagnosticLogClicked?.invoke() },
            ),
            cardLayoutParams(),
        )
    }

    private fun sectionHeader(title: String): TextView =
        TextView(context).apply {
            text = title
            setTextColor(palette.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.04f
            setPadding(dp(4), dp(12), dp(4), dp(6))
        }

    private fun cardLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        }

    private fun toggleCard(
        title: String,
        subtitle: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
    ): LinearLayout {
        var current = checked
        val switchView = Switch(context).apply {
            isFocusable = false
            isClickable = false
            isChecked = current
        }
        return focusableCard(
            onClick = {
                current = !current
                switchView.isChecked = current
                onToggle(current)
            },
        ).apply {
            tag = "card:$title"
            addView(
                cardTitleColumn(title, subtitle),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(14) },
            )
            addView(switchView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    private fun <T> cycleCard(
        title: String,
        subtitle: String,
        options: List<Pair<T, String>>,
        current: T,
        onClickOverride: (() -> Unit)? = null,
        onSelected: (T) -> Unit,
    ): LinearLayout {
        var index = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
        val valueBadge = TextView(context).apply {
            text = "◀  ${options.getOrNull(index)?.second.orEmpty()}  ▶"
            setTextColor(palette.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * density
                setColor(palette.surfaceCardElevated)
                setStroke((1f * density).toInt().coerceAtLeast(1), palette.outline)
            }
        }

        fun step(delta: Int) {
            if (options.isEmpty()) return
            index = (index + delta + options.size) % options.size
            val chosen = options[index]
            valueBadge.text = "◀  ${chosen.second}  ▶"
            onSelected(chosen.first)
        }

        return focusableCard(
            onClick = {
                if (onClickOverride != null) {
                    onClickOverride()
                } else {
                    step(1)
                }
            },
            onHorizontalKey = { keyCode ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        step(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        step(1)
                        true
                    }
                    else -> false
                }
            },
        ).apply {
            tag = "card:$title"
            addView(
                cardTitleColumn(title, subtitle),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(14) },
            )
            addView(valueBadge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    private fun actionCard(
        title: String,
        subtitle: String,
        buttonLabel: String,
        onClick: () -> Unit,
    ): LinearLayout {
        val pill = TvPlayButton(context, TvPlayButtonKind.SECONDARY).apply {
            text = buttonLabel
            isFocusable = false
            isClickable = false
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        return focusableCard(onClick = onClick).apply {
            tag = "card:$title"
            addView(
                cardTitleColumn(title, subtitle),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(14) },
            )
            addView(pill, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    private fun textFieldCard(
        title: String,
        subtitle: String,
        initialValue: String,
        hint: String,
        secret: Boolean,
        onChanged: (String) -> Unit,
    ): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = cardBackground(focused = false)
        }
        card.addView(cardTitleColumn(title, subtitle), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val editText = EditText(context).apply {
            setText(initialValue)
            this.hint = hint
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine(true)
            inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(palette.surfaceCardElevated)
                setStroke((1f * density).toInt().coerceAtLeast(1), palette.outline)
            }
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setOnFocusChangeListener { _, hasFocus ->
                card.background = cardBackground(focused = hasFocus)
                if (!hasFocus) {
                    onChanged(text?.toString().orEmpty())
                }
            }
        }
        card.addView(
            editText,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) },
        )
        return card
    }

    private fun cardTitleColumn(title: String, subtitle: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(context).apply {
                    text = title
                    setTextColor(palette.textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            addView(
                TextView(context).apply {
                    text = subtitle
                    setTextColor(palette.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setLineSpacing(0f, 1.15f)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(3) },
            )
        }

    private fun focusableCard(
        onClick: () -> Unit,
        onHorizontalKey: ((Int) -> Boolean)? = null,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            background = cardBackground(focused = false)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, hasFocus ->
                background = cardBackground(focused = hasFocus)
                animate()
                    .scaleX(if (hasFocus) 1.015f else 1.0f)
                    .scaleY(if (hasFocus) 1.015f else 1.0f)
                    .setDuration(110L)
                    .start()
            }
            if (onHorizontalKey != null) {
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        onHorizontalKey(keyCode)
                    } else {
                        false
                    }
                }
            }
        }

    private fun cardBackground(focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(if (focused) palette.surfaceCardElevated else palette.surfaceCard)
            setStroke(
                if (focused) dp(2) else dp(1),
                if (focused) palette.accent else palette.outline,
            )
        }

    private fun dp(value: Int): Int = (value * density).toInt()
}
