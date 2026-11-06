package app.tvplay.ui.workbench

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.tvplay.config.TvSettingsRepository
import app.tvplay.config.VideoSurfaceType
import app.tvplay.config.WaitingScreenColorPreset
import app.tvplay.engine.av.CarPlayPhysicalSizePreset
import app.tvplay.engine.av.MediaAudioBuffer
import app.tvplay.engine.av.UiDensityScale
import app.tvplay.engine.network.LanNetworkLink
import app.tvplay.engine.network.WifiP2pChannels
import app.tvplay.engine.session.TvProjectionProtocol
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.remote.CarPlayNightMode
import app.tvplay.runtime.TvLanOtaManager
import app.tvplay.ui.theme.AppAppearance
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.ui.theme.TvIconGlyph
import app.tvplay.ui.theme.TvModalDialog
import app.tvplay.ui.theme.TvPlayButton
import app.tvplay.ui.theme.TvPlayButtonKind
import app.tvplay.ui.theme.TvVectorIconDrawable
import app.tvplay.ui.theme.TvVectorIcons

enum class SettingsCategory(val title: String) {
    CONNECTION("连接与配对"),
    DISPLAY("显示与画质"),
    AUDIO("音频与麦克风"),
    ABOUT("系统与诊断"),
}

typealias WorkbenchSettingsPane = WorkbenchSettingsTabs

/**
 * Right-pane Bento settings console for the 10-foot Android TV workbench (`ui-ux-pro-max`).
 *
 * Features:
 * - Segmented glassmorphic category bar with custom geometric vector icons ([TvVectorIcons]).
 * - Squircle vector icon wells on every setting row for rapid 10-foot visual scanning.
 * - Custom tvOS/iOS-style capsule switches ([TvVectorIcons.styleIosSwitch]) and non-jittering focus rings.
 */
class WorkbenchSettingsTabs(
    private val context: Context,
    private val settings: TvSettingsRepository,
) {
    private val density = context.resources.displayMetrics.density
    private val palette = TvColorScheme.resolve(context)

    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.blendARGB(palette.surfaceContainer, palette.card, 0.35f),
                palette.surfaceContainer,
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 26f * density
            setStroke((1f * density).toInt().coerceAtLeast(1), palette.outline)
        }
        setPadding(dp(22), dp(18), dp(22), dp(18))
    }

    private val tabStripRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(5), dp(5), dp(5), dp(5))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f * density
            setColor(palette.subtleSurface)
            setStroke((1f * density).toInt().coerceAtLeast(1), palette.outline)
        }
    }

    private val tabButtons = LinkedHashMap<SettingsCategory, TextView>()
    private val contentContainer = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
    }
    private var selectedCategory: SettingsCategory = SettingsCategory.CONNECTION
    private var isRebuilding: Boolean = false

    var onChooseIphoneClicked: (() -> Unit)? = null
    var onPairBluetoothClicked: (() -> Unit)? = null
    var onResetWirelessHotspotClicked: (() -> Unit)? = null
    var onSaveDiagnosticLogClicked: (() -> Unit)? = null
    var onCheckOtaUpdateClicked: (() -> Unit)? = null
    var onDiscoverOtaServerClicked: (() -> Unit)? = null
    var onOpenPictureAdjustmentClicked: (() -> Unit)? = null
    var onSettingChanged: (() -> Unit)? = null
    var onAppearanceChanged: (() -> Unit)? = null

    init {
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerBadge = TvVectorIcons.badgeWell(
            context = context,
            glyph = TvIconGlyph.SLIDERS,
            palette = palette,
            active = true,
            sizeDp = 34,
            iconDp = 17,
        )
        val headerTitle = TextView(context).apply {
            text = "接收端参数控制台"
            setTextColor(palette.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val headerHint = TextView(context).apply {
            text = "方向键导航  ·  OK 切换"
            setTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * density
                setColor(palette.badgeSurface)
                setStroke((1f * density).toInt().coerceAtLeast(1), palette.badgeOutline)
            }
            gravity = Gravity.END
        }
        headerRow.addView(
            headerBadge,
            LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) },
        )
        headerRow.addView(headerTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        headerRow.addView(headerHint, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(headerRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
        }
        SettingsCategory.entries.forEachIndexed { index, category ->
            val tab = createTabChip(category)
            tabButtons[category] = tab
            tabStripRow.addView(
                tab,
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    if (index > 0) marginStart = dp(6)
                },
            )
        }
        tabScroll.addView(tabStripRow, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(
            tabScroll,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(14)
                bottomMargin = dp(12)
            },
        )

        root.addView(
            contentContainer,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )

        selectCategory(SettingsCategory.CONNECTION)
    }

    fun selectCategory(category: SettingsCategory) {
        if (isRebuilding) return
        isRebuilding = true
        try {
            val sameCategory = selectedCategory == category
            val focusedTag = if (sameCategory) contentContainer.findFocus()?.tag as? String else null
            val previousScrollY = if (sameCategory) {
                (contentContainer.getChildAt(0) as? ScrollView)?.scrollY ?: 0
            } else {
                0
            }
            selectedCategory = category
            tabButtons.forEach { (cat, chip) ->
                styleTabChip(chip, category = cat, selected = cat == category, focused = chip.isFocused)
            }
            contentContainer.removeAllViews()
            val scroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = true
                isFillViewport = true
                clipToPadding = false
                clipChildren = false
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
        } finally {
            isRebuilding = false
        }
    }

    fun refresh() {
        selectCategory(selectedCategory)
    }

    private fun categoryGlyph(category: SettingsCategory): TvIconGlyph = when (category) {
        SettingsCategory.CONNECTION -> TvIconGlyph.WIFI
        SettingsCategory.DISPLAY -> TvIconGlyph.DISPLAY
        SettingsCategory.AUDIO -> TvIconGlyph.AUDIO
        SettingsCategory.ABOUT -> TvIconGlyph.SETTINGS
    }

    private fun createTabChip(category: SettingsCategory): TextView =
        TextView(context).apply {
            text = category.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(8)
            setPadding(dp(16), dp(9), dp(18), dp(9))
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            setOnClickListener { selectCategory(category) }
            setOnFocusChangeListener { _, hasFocus ->
                styleTabChip(this, category = category, selected = selectedCategory == category, focused = hasFocus)
                if (hasFocus && selectedCategory != category) {
                    selectCategory(category)
                }
            }
            styleTabChip(this, category = category, selected = selectedCategory == category, focused = false)
        }

    private fun styleTabChip(view: TextView, category: SettingsCategory, selected: Boolean, focused: Boolean) {
        val fillColor = when {
            selected -> palette.accent
            focused -> palette.surfaceCardElevated
            else -> 0x00000000
        }
        val textColor = when {
            selected -> 0xFFFFFFFF.toInt()
            focused -> palette.textPrimary
            else -> palette.textSecondary
        }
        val iconTint = when {
            selected -> 0xFFFFFFFF.toInt()
            focused -> palette.focusRing
            else -> palette.textSecondary
        }
        val strokeColor = when {
            focused -> palette.focusRing
            selected -> ColorUtils.blendARGB(palette.accent, 0xFFFFFFFF.toInt(), 0.28f)
            else -> 0x00000000
        }
        val strokeWidth = if (focused) dp(2) else if (selected) dp(1) else 0
        view.setTextColor(textColor)
        val icon = TvVectorIcons.drawable(context, categoryGlyph(category), iconTint, sizeDp = 15, strokeDp = 2f)
        view.setCompoundDrawablesRelative(icon, null, null, null)
        view.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                if (fillColor != 0) ColorUtils.blendARGB(fillColor, 0xFFFFFFFF.toInt(), 0.12f) else 0,
                fillColor,
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f * density
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun buildConnectionPage(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(2), dp(4), dp(16))

        addView(sectionHeader("投屏协议引擎（Apple CarPlay / 安卓 CarLink）"))
        val protocolOptions = TvProjectionProtocol.entries.map { it to it.displayName }
        addView(
            cycleCard(
                title = "协议引擎模式",
                subtitle = "支持双模并发（同时监听 iPhone CarPlay 与小米/vivo/OPPO CarLink）或指定单一协议",
                options = protocolOptions,
                current = settings.connection.projectionProtocol,
                icon = TvIconGlyph.SPARKLES,
            ) { chosen ->
                settings.connection.projectionProtocol = chosen
                onSettingChanged?.invoke()
                refresh()
            },
            cardLayoutParams(),
        )

        if (settings.connection.projectionProtocol.enablesCarLink) {
            val currentPin = settings.connection.carLinkPinCode
            addView(
                actionCard(
                    title = "ICCOA CarLink 6 位连接码 (PIN)",
                    subtitle = "当前连接码：$currentPin（小米 CarWith / vivo Jovi InCar / OPPO Car+ 首次配对或 AOA2 有线鉴权使用）",
                    buttonLabel = "刷新 PIN 码",
                    icon = TvIconGlyph.SHIELD_MFI,
                    onClick = {
                        settings.connection.refreshCarLinkPinCode()
                        onSettingChanged?.invoke()
                        refresh()
                    },
                ),
                cardLayoutParams(),
            )
        }

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
                subtitle = "按 OK 弹出模式列表，或按左右键切换 Wi-Fi Direct、家庭局域网与 USB",
                options = transportOptions,
                current = currentTransportKey,
                icon = TvIconGlyph.RADAR,
                onClickOverride = {
                    val checkedIndex = transportOptions.indexOfFirst { it.first == currentTransportKey }.coerceAtLeast(0)
                    val optionItems = listOf(
                        TvModalDialog.OptionItem(
                            title = "无线 · Wi-Fi Direct 5GHz 直连（推荐）",
                            subtitle = "免填路由器密码，电视与 iPhone 自动建立 5GHz 高速点对点通道",
                            badgeText = if (checkedIndex == 0) "当前模式" else "点击切换",
                            selected = checkedIndex == 0,
                            icon = TvIconGlyph.WIFI,
                        ),
                        TvModalDialog.OptionItem(
                            title = "无线 · 家庭局域网模式",
                            subtitle = "电视与 iPhone 连接同一台家庭路由器（Wi-Fi 或千兆网线）",
                            badgeText = if (checkedIndex == 1) "当前模式" else "点击切换",
                            selected = checkedIndex == 1,
                            icon = TvIconGlyph.LAN,
                        ),
                        TvModalDialog.OptionItem(
                            title = "USB 有线数据线直连",
                            subtitle = "使用 Lightning / USB-C 数据线直连电视 USB 接口",
                            badgeText = if (checkedIndex == 2) "当前模式" else "点击切换",
                            selected = checkedIndex == 2,
                            icon = TvIconGlyph.USB,
                        ),
                    )
                    var dialogRef: android.app.Dialog? = null
                    val listView = TvModalDialog.createOptionListView(
                        context = context,
                        items = optionItems,
                        palette = palette,
                    ) { which ->
                        runCatching { dialogRef?.dismiss() }
                        val chosenKey = transportOptions.getOrNull(which)?.first ?: return@createOptionListView
                        applyTransportKey(chosenKey)
                    }
                    val handle = TvModalDialog.create(
                        context = context,
                        title = "选择 CarPlay 连接模式",
                        subtitle = "使用遥控器上下键选择目标链路模式并按确认键生效",
                        icon = TvIconGlyph.RADAR,
                        badgeText = "3 种链路",
                        bodyView = listView,
                        actions = listOf(
                            TvModalDialog.ActionSpec(
                                label = "取消",
                                kind = TvPlayButtonKind.SECONDARY,
                            ),
                        ),
                    )
                    dialogRef = handle.dialog
                    handle.dialog.show()
                },
            ) { key ->
                applyTransportKey(key)
            },
            cardLayoutParams(),
        )

        addView(sectionHeader("蓝牙配对与目标 iPhone"))
        val phoneAddress = settings.connection.pairedPhoneAddress
        val phoneSubtitle = if (phoneAddress != null) {
            "已绑定：${settings.connection.pairedPhoneName} ($phoneAddress)"
        } else {
            "自动识别已配对的 iPhone（可点击手动指定设备）"
        }
        addView(
            actionCard(
                title = "目标 iPhone 设备",
                subtitle = phoneSubtitle,
                buttonLabel = "选择 iPhone",
                icon = TvIconGlyph.PHONE,
                onClick = { onChooseIphoneClicked?.invoke() },
            ),
            cardLayoutParams(),
        )

        addView(
            actionCard(
                title = "电视系统蓝牙配对",
                subtitle = "开启本机蓝牙可见或进入系统设置与新 iPhone 配对",
                buttonLabel = "打开蓝牙设置",
                icon = TvIconGlyph.BLUETOOTH,
                onClick = { onPairBluetoothClicked?.invoke() },
            ),
            cardLayoutParams(),
        )

        addView(sectionHeader("无线传输通道（电视端专属优化）"))
        val wirelessModes = listOf(
            TvWirelessMode.WIFI_DIRECT to "Wi-Fi Direct 5GHz 直连",
            TvWirelessMode.LAN_WIFI to "家庭局域网 Wi-Fi / 网线",
        )
        addView(
            cycleCard(
                title = "无线网络传输方式",
                subtitle = "Wi-Fi Direct 免密建立 5GHz 点对点连接；家庭局域网通过同一路由器互联",
                options = wirelessModes,
                current = settings.connection.wirelessHotspotMode,
                icon = TvIconGlyph.WIFI,
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
                if (settings.connection.existingWifiSsid.isBlank()) {
                    LanNetworkLink.detectConnectedWifiSsid(context)?.let { detected ->
                        settings.connection.existingWifiSsid = detected
                    }
                }
                addView(
                    textFieldCard(
                        title = "家庭路由器 Wi-Fi 名称 (SSID)",
                        subtitle = "填写 iPhone 与电视共同连接的路由器 Wi-Fi 名称（无线握手时下发给 iPhone）",
                        initialValue = settings.connection.existingWifiSsid,
                        hint = "填写家庭路由器 Wi-Fi 名称",
                        secret = false,
                        icon = TvIconGlyph.LAN,
                    ) { value ->
                        settings.connection.existingWifiSsid = value.trim()
                        onSettingChanged?.invoke()
                    },
                    cardLayoutParams(),
                )
                addView(
                    textFieldCard(
                        title = "家庭路由器 Wi-Fi 密码",
                        subtitle = "填写该家庭路由器的 WPA2 密码（开放网络可留空）",
                        initialValue = settings.connection.existingWifiPassphrase,
                        hint = "填写家庭路由器 Wi-Fi 密码",
                        secret = true,
                        icon = TvIconGlyph.SHIELD_MFI,
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
                        icon = TvIconGlyph.SETTINGS,
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
                        subtitle = "建议保持「自动优选」，或固定为干扰较少的 5 GHz 高速信道",
                        options = channelOptions,
                        current = settings.connection.wifiP2pPreferredChannel,
                        icon = TvIconGlyph.RADAR,
                    ) { channel ->
                        settings.connection.wifiP2pPreferredChannel = channel
                        onSettingChanged?.invoke()
                    },
                    cardLayoutParams(),
                )
                addView(
                    actionCard(
                        title = "重置 Wi-Fi Direct 投屏通道",
                        subtitle = "当无线握手后画面未弹出或热点异常时，清理残留 P2P 组网状态",
                        buttonLabel = "重置通道",
                        icon = TvIconGlyph.REFRESH,
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
                subtitle = "Android TV 开机完成后自动拉起后台监听服务",
                checked = settings.connection.autoConnectOnBoot,
                icon = TvIconGlyph.POWER,
            ) { enabled ->
                settings.connection.autoConnectOnBoot = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "iPhone 蓝牙连上时自动进入投屏",
                subtitle = "检测到已配对 iPhone 蓝牙连接时自动开启 CarPlay 舞台",
                checked = settings.connection.connectOnPhoneBluetooth,
                icon = TvIconGlyph.BLUETOOTH,
            ) { enabled ->
                settings.connection.connectOnPhoneBluetooth = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
    }

    private fun buildDisplayPage(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(2), dp(4), dp(16))

        addView(sectionHeader("视频硬件解码与渲染"))
        addView(
            toggleCard(
                title = "HEVC (H.265) 高清视频编码",
                subtitle = "在 4K 电视硬件解码器上提供更高清晰度与更低无线码率开销",
                checked = settings.display.hevcEnabled,
                icon = TvIconGlyph.SPARKLES,
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
                icon = TvIconGlyph.SETTINGS,
            ) { enabled ->
                settings.display.hevcSoftwareDecoderEnabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            cycleCard(
                title = "视频渲染视图类型",
                subtitle = "SurfaceView 延迟最低；TextureView 支持实时色彩与对比度调节",
                options = listOf(
                    VideoSurfaceType.AUTO to "自动选择（推荐）",
                    VideoSurfaceType.SURFACE_VIEW to "SurfaceView（最低延迟）",
                    VideoSurfaceType.TEXTURE_VIEW to "TextureView（色彩调节）",
                ),
                current = settings.display.videoSurfaceType,
                icon = TvIconGlyph.DISPLAY,
            ) { mode ->
                settings.display.videoSurfaceType = mode
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "平滑帧率同步（防抖缓冲）",
                subtitle = "增加 1 帧平滑队列，提升 60Hz 电视大屏动画顺滑度",
                checked = settings.display.smoothVideoPlayback,
                icon = TvIconGlyph.FPS_GAUGE,
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
                icon = TvIconGlyph.FPS_GAUGE,
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
                icon = TvIconGlyph.DISPLAY,
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
                icon = TvIconGlyph.SLIDERS,
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
                icon = TvIconGlyph.DISPLAY,
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
                icon = TvIconGlyph.DISPLAY,
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
                icon = TvIconGlyph.SPARKLES,
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
                    CarPlayNightMode.NIGHT to "始终深色（推荐）",
                    CarPlayNightMode.SYSTEM to "跟随系统时间",
                    CarPlayNightMode.DAY to "始终浅色",
                ),
                current = settings.display.carPlayNightMode,
                icon = TvIconGlyph.MOON,
            ) { mode ->
                settings.display.carPlayNightMode = mode
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            cycleCard(
                title = "TVPlay 工作台界面主题",
                subtitle = "设置主界面与控制面板的视觉配色风格",
                options = listOf(
                    AppAppearance.DARK to "OLED 空间暗色（推荐）",
                    AppAppearance.AUTO to "跟随 Android TV 系统",
                    AppAppearance.LIGHT to "日间高对比浅色",
                ),
                current = settings.display.appAppearance,
                icon = TvIconGlyph.MOON,
            ) { mode ->
                settings.display.appAppearance = mode
                onAppearanceChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            cycleCard(
                title = "等待连接画面背景色",
                subtitle = "等待 iPhone 投屏期间的全屏空间背景配色",
                options = listOf(
                    WaitingScreenColorPreset.DARK to "深空微光（默认）",
                    WaitingScreenColorPreset.OLED_BLACK to "OLED 纯黑（防烧屏）",
                    WaitingScreenColorPreset.MIDNIGHT_BLUE to "午夜星蓝",
                    WaitingScreenColorPreset.LIGHT to "柔和浅灰",
                ),
                current = settings.display.waitingScreenColorPreset,
                icon = TvIconGlyph.SLIDERS,
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
                icon = TvIconGlyph.FPS_GAUGE,
            ) { enabled ->
                settings.display.fpsCounterEnabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
    }

    private fun buildAudioPage(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(2), dp(4), dp(16))

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
                subtitle = "无线信号拥挤导致音乐断续可适当调大；追求更低延迟可调小",
                options = bufferOptions,
                current = settings.audio.mediaBufferMillis,
                icon = TvIconGlyph.AUDIO,
            ) { ms ->
                settings.audio.mediaBufferMillis = ms
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "自动管理 Android 音频焦点",
                subtitle = "CarPlay 播放音乐、Siri 或导航播报时自动向电视系统申请焦点",
                checked = settings.audio.audioFocusEnabled,
                icon = TvIconGlyph.AUDIO,
            ) { enabled ->
                settings.audio.audioFocusEnabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "静音空闲时自动释放音频焦点",
                subtitle = "CarPlay 暂停播放后释放焦点，方便电视后台其他应用恢复发声",
                checked = settings.audio.audioFocusAutoYield,
                icon = TvIconGlyph.AUDIO,
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
                icon = TvIconGlyph.SLIDERS,
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
                icon = TvIconGlyph.MIC,
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
                icon = TvIconGlyph.AUDIO,
            ) { enabled ->
                settings.audio.mainBufferedAudio = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "保持声音在 iPhone / 外部蓝牙音箱播放",
                subtitle = "禁用电视端音频解码输出，让声音直接从 iPhone 或蓝牙音响发出",
                checked = settings.audio.disableAudioOutput,
                icon = TvIconGlyph.PHONE,
            ) { disabled ->
                settings.audio.disableAudioOutput = disabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
    }

    private fun buildAboutPage(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(2), dp(4), dp(16))

        val installed = TvLanOtaManager.currentAppVersion(context)
        addView(sectionHeader("内网 OTA 在线升级 (fnos-lan-ota)"))
        addView(
            actionCard(
                title = "检查并安装内网 OTA 更新",
                subtitle = "当前版本：v${installed.versionName} (Build ${installed.versionCode}) · 服务端：${settings.identity.otaServerUrl}",
                buttonLabel = "检查更新",
                icon = TvIconGlyph.CLOUD_OTA,
                onClick = { onCheckOtaUpdateClicked?.invoke() },
            ),
            cardLayoutParams(),
        )
        addView(
            actionCard(
                title = "局域网自动发现 OTA 服务 (UDP 9988)",
                subtitle = "通过 UDP 9988 广播 DISCOVER_OTA 自动探测飞牛 OS 局域网 OTA 升级中心",
                buttonLabel = "自动发现",
                icon = TvIconGlyph.RADAR,
                onClick = { onDiscoverOtaServerClicked?.invoke() },
            ),
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "启动时自动检查内网 OTA 更新",
                subtitle = "进入 TVPlay 工作台时在后台自动探测内网新版本并提示一键升级",
                checked = settings.identity.otaAutoCheck,
                icon = TvIconGlyph.CLOUD_OTA,
            ) { enabled ->
                settings.identity.otaAutoCheck = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        val otaChannelOptions = listOf(
            "stable" to "正式版 (stable)",
            "beta" to "测试版 (beta)",
        )
        addView(
            cycleCard(
                title = "OTA 更新通道 (Channel)",
                subtitle = "选择接收稳定版 (stable) 或测试版 (beta) 渠道更新包",
                options = otaChannelOptions,
                current = settings.identity.otaChannel,
                icon = TvIconGlyph.SLIDERS,
            ) { channel ->
                settings.identity.otaChannel = channel
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            textFieldCard(
                title = "内网 OTA 服务器地址",
                subtitle = "默认 http://10.0.0.30:8080，支持 UDP 9988 自动发现覆盖或手动指定",
                initialValue = settings.identity.otaServerUrl,
                hint = "http://10.0.0.30:8080",
                secret = false,
                icon = TvIconGlyph.LAN,
            ) { value ->
                settings.identity.otaServerUrl = value.ifBlank { "http://10.0.0.30:8080" }
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )

        addView(sectionHeader("接收端设备标识与离线 MFi"))
        addView(
            textFieldCard(
                title = "广播厂商名称 (Manufacturer)",
                subtitle = "iAP2 握手时向 iPhone 展示的接收端厂商标识",
                initialValue = settings.identity.manufacturer,
                hint = "TVPlay",
                secret = false,
                icon = TvIconGlyph.SHIELD_MFI,
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
                icon = TvIconGlyph.DISPLAY,
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
                icon = TvIconGlyph.LOG_FILE,
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
                icon = TvIconGlyph.LOG_FILE,
                onClick = { onSaveDiagnosticLogClicked?.invoke() },
            ),
            cardLayoutParams(),
        )
    }

    private fun sectionHeader(title: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
            setPadding(dp(4), dp(14), dp(4), dp(7))
            addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(3).toFloat()
                        setColor(palette.accent)
                    }
                },
                LinearLayout.LayoutParams(dp(4), dp(14)).apply { marginEnd = dp(9) },
            )
            addView(
                TextView(context).apply {
                    text = title
                    setTextColor(palette.accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    letterSpacing = 0.04f
                },
            )
        }

    private fun cardLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(9)
        }

    private fun createRowIconBadge(glyph: TvIconGlyph): Pair<FrameLayout, (Boolean) -> Unit> {
        val well = FrameLayout(context).apply {
            isClickable = false
            isFocusable = false
        }
        val image = ImageView(context).apply {
            isClickable = false
            isFocusable = false
        }
        well.addView(image, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        fun updateState(focusedOrActive: Boolean) {
            val tint = if (focusedOrActive) palette.focusRing else palette.accent
            well.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 11f * density
                setColor(
                    if (focusedOrActive) {
                        ColorUtils.setAlphaComponent(palette.accent, if (palette.night) 0x38 else 0x22)
                    } else {
                        palette.iconWellSurface
                    },
                )
                setStroke(
                    (1f * density).toInt().coerceAtLeast(1),
                    if (focusedOrActive) ColorUtils.setAlphaComponent(palette.focusRing, 0x88) else palette.iconWellOutline,
                )
            }
            image.setImageDrawable(TvVectorIconDrawable(glyph, tint, dp(18), 2f * density))
        }
        updateState(false)
        return well to ::updateState
    }

    private fun toggleCard(
        title: String,
        subtitle: String,
        checked: Boolean,
        icon: TvIconGlyph = TvIconGlyph.SETTINGS,
        onToggle: (Boolean) -> Unit,
    ): LinearLayout {
        var current = checked
        val (iconWell, updateIconState) = createRowIconBadge(icon)
        val stateBadge = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(9), dp(3), dp(9), dp(3))
        }
        fun updateStateBadge(on: Boolean) {
            stateBadge.text = if (on) "已开启" else "已关闭"
            stateBadge.setTextColor(if (on) palette.success else palette.textSecondary)
            stateBadge.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * density
                if (on) {
                    setColor(palette.successSurface)
                    setStroke((1f * density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(palette.success, 0x66))
                } else {
                    setColor(palette.badgeSurface)
                    setStroke((1f * density).toInt().coerceAtLeast(1), palette.badgeOutline)
                }
            }
        }
        updateStateBadge(current)

        val switchView = Switch(context).apply {
            isFocusable = false
            isClickable = false
            isChecked = current
            TvVectorIcons.styleIosSwitch(this, palette)
        }
        val rightCluster = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
            addView(
                stateBadge,
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(10) },
            )
            addView(switchView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        return focusableCard(
            onClick = {
                current = !current
                switchView.isChecked = current
                updateStateBadge(current)
                onToggle(current)
            },
            onFocusChanged = { hasFocus ->
                updateIconState(hasFocus)
            },
        ).apply {
            tag = "card:$title"
            addView(
                iconWell,
                LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(14) },
            )
            addView(
                cardTitleColumn(title, subtitle),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(14) },
            )
            addView(rightCluster, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    private fun <T> cycleCard(
        title: String,
        subtitle: String,
        options: List<Pair<T, String>>,
        current: T,
        icon: TvIconGlyph = TvIconGlyph.SLIDERS,
        onClickOverride: (() -> Unit)? = null,
        onSelected: (T) -> Unit,
    ): LinearLayout {
        var index = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
        val (iconWell, updateIconState) = createRowIconBadge(icon)
        val valueBadge = TextView(context).apply {
            text = options.getOrNull(index)?.second.orEmpty()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        fun styleValueBadge(focused: Boolean) {
            valueBadge.setTextColor(if (focused) palette.onAccent else palette.textPrimary)
            valueBadge.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    if (focused) ColorUtils.blendARGB(palette.accent, 0xFFFFFFFF.toInt(), 0.14f) else palette.surfaceCardElevated,
                    if (focused) palette.accent else palette.subtleSurface,
                ),
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * density
                setStroke(
                    (1f * density).toInt().coerceAtLeast(1),
                    if (focused) palette.focusRing else palette.outline,
                )
            }
        }
        styleValueBadge(false)

        fun step(delta: Int) {
            if (options.isEmpty()) return
            index = (index + delta + options.size) % options.size
            val chosen = options[index]
            valueBadge.text = chosen.second
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
            onFocusChanged = { hasFocus ->
                styleValueBadge(hasFocus)
                updateIconState(hasFocus)
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
                iconWell,
                LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(14) },
            )
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
        icon: TvIconGlyph = TvIconGlyph.SETTINGS,
        onClick: () -> Unit,
    ): LinearLayout {
        val (iconWell, updateIconState) = createRowIconBadge(icon)
        val pill = TvPlayButton(context, TvPlayButtonKind.SECONDARY).apply {
            text = buttonLabel
            isFocusable = false
            isClickable = false
            minHeight = dp(36)
            minimumHeight = dp(36)
            setPadding(dp(16), dp(7), dp(16), dp(7))
        }
        return focusableCard(
            onClick = onClick,
            onFocusChanged = { hasFocus ->
                updateIconState(hasFocus)
                pill.style(
                    if (hasFocus) TvPlayButtonKind.PRIMARY else TvPlayButtonKind.SECONDARY,
                    palette,
                    overlay = false,
                )
            },
        ).apply {
            tag = "card:$title"
            addView(
                iconWell,
                LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(14) },
            )
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
        icon: TvIconGlyph = TvIconGlyph.SETTINGS,
        onChanged: (String) -> Unit,
    ): LinearLayout {
        val (iconWell, updateIconState) = createRowIconBadge(icon)
        val card = LinearLayout(context).apply {
            tag = "card:$title"
            orientation = LinearLayout.VERTICAL
            isClickable = false
            isFocusable = false
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = cardBackground(focused = false)
        }
        val topHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
            addView(
                iconWell,
                LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(14) },
            )
            addView(
                cardTitleColumn(title, subtitle),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
            )
        }
        card.addView(topHeader, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        var lastCommittedValue = initialValue
        fun commitIfChanged(raw: String) {
            if (isRebuilding) return
            if (raw != lastCommittedValue) {
                lastCommittedValue = raw
                onChanged(raw)
            }
        }

        fun inputBoxBackground(focused: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(if (focused) palette.surfaceCardElevated else palette.subtleSurface)
            setStroke(
                if (focused) dp(2) else dp(1),
                if (focused) palette.focusRing else palette.outline,
            )
        }

        val editText = EditText(context).apply {
            tag = "input:$title"
            isFocusable = true
            isFocusableInTouchMode = false
            setText(initialValue)
            this.hint = hint
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            background = inputBoxBackground(focused = false)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                isFocusableInTouchMode = true
                requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(this, 0)
            }
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        commitIfChanged(s?.toString().orEmpty())
                    }
                },
            )
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                    commitIfChanged(text?.toString().orEmpty())
                    false
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (isRebuilding) return@setOnFocusChangeListener
                if (!hasFocus) {
                    isFocusableInTouchMode = false
                }
                updateIconState(hasFocus)
                background = inputBoxBackground(focused = hasFocus)
                card.background = cardBackground(focused = hasFocus)
                if (!hasFocus) {
                    commitIfChanged(text?.toString().orEmpty())
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
            isClickable = false
            isFocusable = false
            addView(
                TextView(context).apply {
                    text = title
                    setTextColor(palette.textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            addView(
                TextView(context).apply {
                    text = subtitle
                    setTextColor(palette.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setLineSpacing(0f, 1.18f)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(3) },
            )
        }

    private fun focusableCard(
        onClick: () -> Unit,
        onFocusChanged: ((Boolean) -> Unit)? = null,
        onHorizontalKey: ((Int) -> Boolean)? = null,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            background = cardBackground(focused = false)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, hasFocus ->
                background = cardBackground(focused = hasFocus)
                onFocusChanged?.invoke(hasFocus)
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
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (focused) {
                intArrayOf(
                    ColorUtils.blendARGB(palette.surfaceCardElevated, palette.accent, 0.16f),
                    palette.surfaceCardElevated,
                )
            } else {
                intArrayOf(
                    ColorUtils.blendARGB(palette.surfaceCard, palette.surfaceContainer, 0.25f),
                    palette.surfaceCard,
                )
            },
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * density
            setStroke(
                if (focused) dp(2) else dp(1),
                if (focused) palette.focusRing else palette.outline,
            )
        }

    private fun dp(value: Int): Int = (value * density).toInt()
}
