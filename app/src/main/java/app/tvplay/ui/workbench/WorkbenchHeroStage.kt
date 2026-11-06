package app.tvplay.ui.workbench

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.tvplay.R
import app.tvplay.config.TvPlayBluetooth
import app.tvplay.config.TvSettingsRepository
import app.tvplay.engine.session.TvProjectionProtocol
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.runtime.CarPlayBackgroundSession
import app.tvplay.runtime.LastConnectionProblem
import app.tvplay.runtime.TvLanOtaManager
import app.tvplay.ui.theme.HeroBentoCardDrawable
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.ui.theme.TvIconGlyph
import app.tvplay.ui.theme.TvPlayButton
import app.tvplay.ui.theme.TvPlayButtonKind
import app.tvplay.ui.theme.TvVectorIcons

typealias WorkbenchStagePane = WorkbenchHeroStage

/**
 * Left Bento cockpit of the 10-foot Apple TV-style workbench (`TvWorkbenchActivity`).
 *
 * Built following `ui-ux-pro-max` Glassmorphism + Dark Mode (OLED) guidelines:
 * - Zero emojis; all icons rendered via [TvVectorIcons] geometric vector paths.
 * - Multi-layered Bento cards with radial spotlights and radar signal rings ([HeroBentoCardDrawable]).
 * - Non-jittering focus states and scannable 10-foot telemetry tiles.
 */
class WorkbenchHeroStage(
    private val context: Context,
    private val settings: TvSettingsRepository = TvSettingsRepository(context),
) {
    val root: FrameLayout = FrameLayout(context)
    var primaryLaunchButton: View = View(context)
        private set
    var onLaunchProjection: ((Boolean) -> Unit)? = null
    var onSelectTransportMode: ((Boolean) -> Unit)? = null
    var onSelectWirelessMode: ((TvWirelessMode) -> Unit)? = null
    var onSelectProjectionProtocol: ((TvProjectionProtocol) -> Unit)? = null
    var onOpenBluetoothSettings: (() -> Unit)? = null

    fun updateStatus(
        hasActiveSession: Boolean,
        pendingReconnect: Boolean = false,
        onDisconnectSession: () -> Unit = {},
    ) {
        val wirelessEnabled = settings.connection.wirelessEnabled
        val wirelessMode = settings.connection.wirelessHotspotMode
        val projectionProtocol = settings.connection.projectionProtocol
        val carLinkPinCode = settings.connection.carLinkPinCode
        val modeLabel = when {
            !wirelessEnabled -> "USB 有线低延迟直连"
            wirelessMode == TvWirelessMode.LAN_WIFI -> "无线 · 家庭局域网互联"
            else -> "无线 · Wi-Fi Direct 5GHz 直连"
        }
        val btName = TvPlayBluetooth.localName(context) ?: "本机电视蓝牙"
        val pairedAddress = settings.connection.pairedPhoneAddress
        val pairedSummary = when {
            pairedAddress != null -> settings.connection.pairedPhoneName
            projectionProtocol == TvProjectionProtocol.ICCOA_CARLINK -> "小米 CarWith / vivo / OPPO"
            projectionProtocol == TvProjectionProtocol.ANDROID_AUTO -> "Android Auto 手机"
            projectionProtocol == TvProjectionProtocol.HUAWEI_HICAR -> "华为 HarmonyOS 手机"
            projectionProtocol == TvProjectionProtocol.AUTO -> "iPhone / 小米 / vivo / OPPO"
            else -> "自动识别已配对 iPhone"
        }
        val hotspotSummary = when {
            !wirelessEnabled -> "即插即用 · 免蓝牙配对 · 硬件解码直通"
            wirelessMode == TvWirelessMode.LAN_WIFI -> {
                val ssid = settings.connection.existingWifiSsid
                if (ssid.isNotBlank()) "当前路由器：$ssid" else "自动桥接电视当前 Wi-Fi 或千兆有线网卡"
            }
            else -> {
                val ch = settings.connection.wifiP2pPreferredChannel
                "5GHz 专用点对点通道 · 信道：${if (ch == 0) "自动优选" else ch.toString()}"
            }
        }
        val lastProblem = if (pendingReconnect) {
            context.getString(R.string.reconnect_for_new_settings)
        } else {
            LastConnectionProblem.homeLine(context)
        }
        val installedVersion = runCatching { TvLanOtaManager.currentAppVersion(context).versionName }.getOrDefault("1.1.1")
        val videoSpecSummary = "${settings.display.fps} FPS · ${if (settings.display.hevcEnabled) "HEVC" else "H.264"}"

        val view = build(
            model = StageModel(
                wirelessEnabled = wirelessEnabled,
                wirelessMode = wirelessMode,
                wirelessModeLabel = modeLabel,
                bluetoothDeviceName = btName,
                pairedPhoneSummary = pairedSummary,
                sessionRunning = hasActiveSession,
                sessionActive = CarPlayBackgroundSession.active,
                hotspotSummary = hotspotSummary,
                lastProblemSummary = lastProblem,
                versionName = installedVersion,
                videoSpecSummary = videoSpecSummary,
                projectionProtocol = projectionProtocol,
                carLinkPinCode = carLinkPinCode,
            ),
            palette = TvColorScheme.resolve(context),
            callbacks = object : Callbacks {
                override fun onStartOrEnterProjection() {
                    onLaunchProjection?.invoke(settings.connection.wirelessEnabled)
                }

                override fun onChooseTransportMode(wireless: Boolean) {
                    if (settings.connection.wirelessEnabled != wireless) {
                        onSelectTransportMode?.invoke(wireless)
                    }
                }

                override fun onChooseWirelessMode(mode: TvWirelessMode) {
                    if (!settings.connection.wirelessEnabled || settings.connection.wirelessHotspotMode != mode) {
                        onSelectWirelessMode?.invoke(mode)
                    }
                }

                override fun onChooseProjectionProtocol(protocol: TvProjectionProtocol) {
                    if (settings.connection.projectionProtocol != protocol) {
                        settings.connection.projectionProtocol = protocol
                        onSelectProjectionProtocol?.invoke(protocol)
                        updateStatus(hasActiveSession, pendingReconnect, onDisconnectSession)
                    }
                }

                override fun onStopSession() {
                    onDisconnectSession()
                }

                override fun onOpenBluetoothSettings() {
                    onOpenBluetoothSettings?.invoke()
                }
            },
        )
        val hadFocus = root.hasFocus()
        val focusedTag = if (hadFocus) root.findFocus()?.tag as? String else null
        val previousFocusability = root.descendantFocusability
        root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        root.removeAllViews()
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.descendantFocusability = previousFocusability
        if (hadFocus) {
            val restored = focusedTag?.let { view.findViewWithTag<View>(it) }?.requestFocus() == true
            if (!restored) {
                primaryLaunchButton.requestFocus()
            }
        }
    }

    data class StageModel(
        val wirelessEnabled: Boolean,
        val wirelessMode: TvWirelessMode,
        val wirelessModeLabel: String,
        val bluetoothDeviceName: String,
        val pairedPhoneSummary: String,
        val sessionRunning: Boolean,
        val sessionActive: Boolean,
        val hotspotSummary: String?,
        val lastProblemSummary: String?,
        val versionName: String = "1.1.1",
        val videoSpecSummary: String = "60 FPS · H.264",
        val projectionProtocol: TvProjectionProtocol = TvProjectionProtocol.AUTO,
        val carLinkPinCode: String = "864209",
    )

    interface Callbacks {
        fun onStartOrEnterProjection()
        fun onChooseTransportMode(wireless: Boolean)
        fun onChooseWirelessMode(mode: TvWirelessMode)
        fun onChooseProjectionProtocol(protocol: TvProjectionProtocol) {}
        fun onStopSession()
        fun onOpenBluetoothSettings()
    }

    fun build(
        model: StageModel,
        palette: TvColorScheme,
        callbacks: Callbacks,
    ): View {
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val column = column().apply {
            setPadding(dp(2), dp(2), dp(6), dp(12))
        }

        // =========================================================================
        // 1. HERO STAGE COCKPIT CARD
        // =========================================================================
        val heroCard = column().apply {
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = HeroBentoCardDrawable(
                palette = palette,
                highlighted = model.sessionActive,
                cornerRadiusPx = dp(24).toFloat(),
                strokeWidthPx = dp(if (model.sessionActive) 2 else 1).toFloat(),
            )
        }

        // Top Telemetry Status Bar (Status Capsule + MFi/Version Capsule)
        val topStatusRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val statusDotColor = when {
            model.sessionActive -> palette.success
            model.sessionRunning -> palette.cyanAccent
            else -> palette.accent
        }
        val statusText = when {
            model.sessionActive -> "已建立手机互联 · 正在投屏"
            model.sessionRunning -> if (model.wirelessEnabled) "后台待命 · 等待无线握手" else "后台待命 · 等待 USB 接入"
            model.projectionProtocol == TvProjectionProtocol.ICCOA_CARLINK ->
                "CarLink 就绪 · PIN ${model.carLinkPinCode}"
            model.projectionProtocol == TvProjectionProtocol.AUTO ->
                "双模就绪 · PIN ${model.carLinkPinCode}"
            else -> "就绪 · 离线 MFi 硬件级认证"
        }
        val statusPill = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(ColorUtils.setAlphaComponent(statusDotColor, if (palette.night) 0x24 else 0x18))
                setStroke(dp(1), ColorUtils.setAlphaComponent(statusDotColor, 0x58))
            }
        }
        val statusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(statusDotColor)
            }
        }
        statusPill.addView(statusDot)
        statusPill.addView(
            label(
                statusText,
                12f,
                if (model.sessionActive) palette.success else palette.primaryText,
                bold = true,
            ),
        )
        topStatusRow.addView(statusPill)
        topStatusRow.addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))

        val versionPill = TextView(context).apply {
            text = "v${model.versionName}"
            setTextColor(palette.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            compoundDrawablePadding = dp(5)
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                TvVectorIcons.drawable(context, TvIconGlyph.SHIELD_MFI, palette.accent, sizeDp = 13),
                null,
                null,
                null,
            )
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(palette.badgeSurface)
                setStroke(dp(1), palette.badgeOutline)
            }
        }
        topStatusRow.addView(versionPill)
        heroCard.addView(topStatusRow)
        heroCard.addView(space(dp(16)))

        // Emblem + Headline Title
        val titleRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val iconBadge = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                marginEnd = dp(16)
            }
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    ColorUtils.setAlphaComponent(palette.accent, if (palette.night) 0x42 else 0x24),
                    ColorUtils.setAlphaComponent(palette.accent, if (palette.night) 0x16 else 0x0C),
                ),
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), ColorUtils.setAlphaComponent(palette.accent, 0x77))
            }
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_carplay)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER),
            )
        }
        val titleCol = column().apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val heroTitleText = when (model.projectionProtocol) {
            TvProjectionProtocol.AUTO -> "CarPlay & CarLink 双模接收端"
            TvProjectionProtocol.APPLE_CARPLAY -> "Apple CarPlay 接收端"
            TvProjectionProtocol.ICCOA_CARLINK -> "ICCOA CarLink 安卓车联"
            TvProjectionProtocol.ANDROID_AUTO -> "Google Android Auto 接收端"
            TvProjectionProtocol.HUAWEI_HICAR -> "华为 HiCar 智行接收端"
        }
        titleCol.addView(
            label(
                heroTitleText,
                22f,
                palette.primaryText,
                bold = true,
            ),
        )
        titleCol.addView(space(dp(3)))
        val subtitleLine = if (model.wirelessEnabled) {
            "${model.wirelessModeLabel} · 蓝牙：${model.bluetoothDeviceName}"
        } else {
            "${model.wirelessModeLabel} · 插入数据线自动唤醒"
        }
        titleCol.addView(
            label(subtitleLine, 13f, palette.secondaryText).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        titleRow.addView(iconBadge)
        titleRow.addView(titleCol)
        heroCard.addView(titleRow)

        // 3-Column Bento Telemetry Grid
        heroCard.addView(space(dp(14)))
        val telemetryGrid = row()
        val transportTileGlyph = when {
            !model.wirelessEnabled -> TvIconGlyph.USB
            model.wirelessMode == TvWirelessMode.LAN_WIFI -> TvIconGlyph.LAN
            else -> TvIconGlyph.WIFI
        }
        val transportTileValue = when {
            !model.wirelessEnabled -> "USB 直连"
            model.wirelessMode == TvWirelessMode.LAN_WIFI -> "家庭局域网"
            else -> "5GHz 直连"
        }
        telemetryGrid.addView(
            bentoMetricTile(
                glyph = transportTileGlyph,
                caption = "传输通道",
                value = transportTileValue,
                palette = palette,
                accented = true,
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            },
        )
        telemetryGrid.addView(
            bentoMetricTile(
                glyph = TvIconGlyph.FPS_GAUGE,
                caption = "画质规格",
                value = model.videoSpecSummary,
                palette = palette,
                accented = false,
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            },
        )
        telemetryGrid.addView(
            bentoMetricTile(
                glyph = TvIconGlyph.PHONE,
                caption = "目标设备",
                value = model.pairedPhoneSummary,
                palette = palette,
                accented = false,
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        heroCard.addView(telemetryGrid)

        model.lastProblemSummary?.takeIf { it.isNotBlank() }?.let { problem ->
            heroCard.addView(space(dp(12)))
            val warnBox = column().apply {
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(palette.warningSurface)
                    setStroke(dp(1), palette.warningOutline)
                }
            }
            warnBox.addView(label("状态提示：$problem", 12.5f, palette.warningText, bold = true))
            heroCard.addView(warnBox)
        }

        heroCard.addView(space(dp(14)))

        val heroProtoRow = row()
        val heroProtoItems = listOf(
            TvProjectionProtocol.AUTO to "双模并发",
            TvProjectionProtocol.APPLE_CARPLAY to "CarPlay",
            TvProjectionProtocol.ICCOA_CARLINK to "安卓车联",
        )
        heroProtoItems.forEachIndexed { idx, (proto, titleText) ->
            val selected = model.projectionProtocol == proto
            val btn = TvPlayButton(context).apply {
                tag = "hero_proto_${proto.key}"
                text = titleText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f)
                minHeight = dp(40)
                minimumHeight = dp(40)
                style(
                    if (selected) TvPlayButtonKind.PRIMARY else TvPlayButtonKind.SECONDARY,
                    palette,
                    overlay = false,
                    glyph = if (proto == TvProjectionProtocol.APPLE_CARPLAY) TvIconGlyph.SHIELD_MFI else TvIconGlyph.PHONE,
                )
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { callbacks.onChooseProjectionProtocol(proto) }
            }
            heroProtoRow.addView(
                btn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx < heroProtoItems.lastIndex) marginEnd = dp(8)
                },
            )
        }
        heroCard.addView(heroProtoRow)
        heroCard.addView(space(dp(10)))

        val primaryButton = TvPlayButton(context, TvPlayButtonKind.PRIMARY, TvIconGlyph.PLAY).apply {
            tag = "primary_launch"
            text = when {
                model.sessionActive -> "进入全屏投屏画面"
                model.sessionRunning -> "打开全屏投屏等待画面"
                model.projectionProtocol == TvProjectionProtocol.ICCOA_CARLINK ->
                    if (model.wirelessEnabled) {
                        "启动无线安卓 CarLink (PIN: ${model.carLinkPinCode})"
                    } else {
                        "启动 USB 有线安卓 CarLink 投屏"
                    }
                model.projectionProtocol == TvProjectionProtocol.AUTO ->
                    if (model.wirelessEnabled) {
                        "启动无线双模投屏 (CarPlay + CarLink)"
                    } else {
                        "启动 USB 有线双模投屏 (CarPlay + CarLink)"
                    }
                model.wirelessEnabled -> "启动无线 Apple CarPlay 投屏"
                else -> "启动 USB 有线 Apple CarPlay 投屏"
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            minHeight = dp(50)
            minimumHeight = dp(50)
            style(TvPlayButtonKind.PRIMARY, palette, overlay = false, glyph = TvIconGlyph.PLAY)
            setOnClickListener { callbacks.onStartOrEnterProjection() }
        }
        primaryLaunchButton = primaryButton
        heroCard.addView(
            primaryButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        if (model.sessionRunning || model.sessionActive) {
            heroCard.addView(space(dp(8)))
            val stopButton = TvPlayButton(context, TvPlayButtonKind.TONAL_DANGER, TvIconGlyph.POWER).apply {
                tag = "stop_session"
                text = "断开当前连接并停止后台服务"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                minHeight = dp(44)
                minimumHeight = dp(44)
                style(TvPlayButtonKind.TONAL_DANGER, palette, overlay = false, glyph = TvIconGlyph.POWER)
                setOnClickListener { callbacks.onStopSession() }
            }
            heroCard.addView(
                stopButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        column.addView(heroCard)
        column.addView(space(dp(12)))

        // =========================================================================
        // 2. BENTO TRANSPORT MATRIX CARD
        // =========================================================================
        val modeCard = bentoCard(palette)
        modeCard.addView(
            bentoSectionHeader(
                glyph = TvIconGlyph.WIFI,
                title = "连接方式",
                badgeText = model.hotspotSummary ?: "即时生效",
                palette = palette,
            ),
        )
        modeCard.addView(space(dp(10)))

        val transportRow = row()
        val wirelessBtn = TvPlayButton(context).apply {
            tag = "mode_wireless"
            text = "无线投屏连接"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minHeight = dp(40)
            minimumHeight = dp(40)
            style(
                if (model.wirelessEnabled) TvPlayButtonKind.PRIMARY else TvPlayButtonKind.SECONDARY,
                palette,
                overlay = false,
                glyph = TvIconGlyph.WIFI,
            )
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { callbacks.onChooseTransportMode(true) }
        }
        val usbBtn = TvPlayButton(context).apply {
            tag = "mode_usb"
            text = "USB 有线直连"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minHeight = dp(40)
            minimumHeight = dp(40)
            style(
                if (!model.wirelessEnabled) TvPlayButtonKind.PRIMARY else TvPlayButtonKind.SECONDARY,
                palette,
                overlay = false,
                glyph = TvIconGlyph.USB,
            )
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { callbacks.onChooseTransportMode(false) }
        }
        transportRow.addView(
            wirelessBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
        )
        transportRow.addView(
            usbBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        modeCard.addView(transportRow)

        if (model.wirelessEnabled) {
            modeCard.addView(space(dp(8)))
            val switchRow = row()
            val isDirect = model.wirelessMode == TvWirelessMode.WIFI_DIRECT
            val directBtn = TvPlayButton(context).apply {
                tag = "mode_direct"
                text = "Wi-Fi Direct 直连"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                minHeight = dp(40)
                minimumHeight = dp(40)
                style(
                    if (isDirect) TvPlayButtonKind.PRIMARY else TvPlayButtonKind.SECONDARY,
                    palette,
                    overlay = false,
                    glyph = TvIconGlyph.RADAR,
                )
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { callbacks.onChooseWirelessMode(TvWirelessMode.WIFI_DIRECT) }
            }
            val lanBtn = TvPlayButton(context).apply {
                tag = "mode_lan"
                text = "家庭局域网 / 网线"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                minHeight = dp(40)
                minimumHeight = dp(40)
                style(
                    if (!isDirect) TvPlayButtonKind.PRIMARY else TvPlayButtonKind.SECONDARY,
                    palette,
                    overlay = false,
                    glyph = TvIconGlyph.LAN,
                )
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { callbacks.onChooseWirelessMode(TvWirelessMode.LAN_WIFI) }
            }
            switchRow.addView(
                directBtn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
            )
            switchRow.addView(
                lanBtn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            modeCard.addView(switchRow)
        }
        column.addView(modeCard)
        column.addView(space(dp(12)))

        // =========================================================================
        // 3. COMPACT 3-STEP BENTO CONNECTION TIMELINE
        // =========================================================================
        val guideCard = bentoCard(palette)
        if (!model.wirelessEnabled) {
            guideCard.addView(
                bentoSectionHeader(
                    glyph = TvIconGlyph.USB,
                    title = "USB 有线连接流程",
                    badgeText = "零配置直连",
                    palette = palette,
                ),
            )
            guideCard.addView(space(dp(10)))
            guideCard.addView(
                stepTimelineRow(
                    "01",
                    "连接数据线",
                    "使用支持数据传输的 Lightning / USB-C 线连接电视与 iPhone",
                    palette,
                ),
            )
            guideCard.addView(space(dp(6)))
            guideCard.addView(
                stepTimelineRow(
                    "02",
                    "授权信任设备",
                    "解锁 iPhone 点击「信任此电脑」，并在电视弹窗允许 USB 访问",
                    palette,
                ),
            )
            guideCard.addView(space(dp(6)))
            guideCard.addView(
                stepTimelineRow(
                    "03",
                    "开启全屏投屏",
                    "点击上方启动按钮，无需开启 VPN 即可直接进入 60FPS 画面",
                    palette,
                ),
            )
        } else {
            val isDirect = model.wirelessMode == TvWirelessMode.WIFI_DIRECT
            val isAndroidOnly = model.projectionProtocol == TvProjectionProtocol.ICCOA_CARLINK ||
                model.projectionProtocol == TvProjectionProtocol.ANDROID_AUTO ||
                model.projectionProtocol == TvProjectionProtocol.HUAWEI_HICAR
            guideCard.addView(
                bentoSectionHeader(
                    glyph = TvIconGlyph.BLUETOOTH,
                    title = if (isAndroidOnly) "无线安卓车联 (CarLink) 连接流程" else "无线 CarPlay / CarLink 连接流程",
                    badgeText = if (model.projectionProtocol.enablesCarLink) {
                        "PIN: ${model.carLinkPinCode}"
                    } else if (isDirect) {
                        "5GHz 点对点"
                    } else {
                        "同路由器局域网"
                    },
                    palette = palette,
                ),
            )
            guideCard.addView(space(dp(10)))
            guideCard.addView(
                stepTimelineRow(
                    "01",
                    if (isDirect) "开启手机蓝牙与 Wi-Fi" else "接入同一家庭路由器",
                    if (isDirect) "保持电视与手机蓝牙、Wi-Fi 开启，BLE 0xFCFB 自动广播发现"
                    else "电视通过 Wi-Fi 或千兆网线与 iPhone / 安卓手机处于同一局域网",
                    palette,
                ),
            )
            guideCard.addView(space(dp(6)))
            guideCard.addView(
                stepTimelineRow(
                    "02",
                    if (isAndroidOnly) "打开小米 CarWith / vivo / OPPO 车联" else "蓝牙配对「${model.bluetoothDeviceName}」或开启安卓车联",
                    if (isAndroidOnly) "手机设置 → 更多连接 → CarWith / 智能车载，自动弹窗发现「TVPlay」"
                    else "iPhone 连接蓝牙「${model.bluetoothDeviceName}」；小米 / vivo / OPPO 自动发现连接",
                    palette,
                ),
            )
            guideCard.addView(space(dp(6)))
            guideCard.addView(
                stepTimelineRow(
                    "03",
                    if (model.projectionProtocol.enablesCarLink) "输入 6 位连接码 ${model.carLinkPinCode}" else "确认使用 CarPlay 车载",
                    if (model.projectionProtocol.enablesCarLink) "安卓首次连接输入 PIN 码「${model.carLinkPinCode}」；iPhone 确认启用 CarPlay"
                    else "点击上方启动按钮，首次连接在 iPhone 弹窗确认启用 CarPlay",
                    palette,
                ),
            )
        }

        column.addView(guideCard)
        scroll.addView(column)
        return scroll
    }

    private fun bentoMetricTile(
        glyph: TvIconGlyph,
        caption: String,
        value: String,
        palette: TvColorScheme,
        accented: Boolean,
    ): View = column().apply {
        setPadding(dp(11), dp(9), dp(11), dp(9))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            if (accented) {
                setColor(ColorUtils.setAlphaComponent(palette.accent, if (palette.night) 0x20 else 0x14))
                setStroke(dp(1), ColorUtils.setAlphaComponent(palette.accent, 0x50))
            } else {
                setColor(palette.subtleSurface)
                setStroke(dp(1), palette.outline)
            }
        }
        val topRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(
            ImageView(context).apply {
                setImageDrawable(
                    TvVectorIcons.drawable(
                        context,
                        glyph,
                        if (accented) palette.accent else palette.secondaryText,
                        sizeDp = 13,
                    ),
                )
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(13), dp(13)).apply { marginEnd = dp(5) },
        )
        topRow.addView(
            label(caption, 11f, palette.secondaryText, bold = false).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        addView(topRow)
        addView(space(dp(3)))
        addView(
            label(value, 12.5f, if (accented) palette.primaryText else palette.primaryText, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
    }

    private fun bentoSectionHeader(
        glyph: TvIconGlyph,
        title: String,
        badgeText: String,
        palette: TvColorScheme,
    ): View {
        val headerRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(
            TvVectorIcons.badgeWell(context, glyph, palette, accented = false, sizeDp = 30),
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) },
        )
        headerRow.addView(
            label(title, 15.5f, palette.primaryText, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (badgeText.isNotBlank()) {
            headerRow.addView(
                TextView(context).apply {
                    text = badgeText
                    setTextColor(palette.secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(9), dp(3), dp(9), dp(3))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(999).toFloat()
                        setColor(palette.subtleSurface)
                        setStroke(dp(1), palette.outline)
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        return headerRow
    }

    private fun stepTimelineRow(
        number: String,
        title: String,
        subtitle: String,
        palette: TvColorScheme,
    ): View {
        val row = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(13).toFloat()
                setColor(palette.subtleSurface)
                setStroke(dp(1), palette.outline)
            }
        }
        val badge = TextView(context).apply {
            text = number
            gravity = Gravity.CENTER
            setTextColor(palette.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                marginEnd = dp(11)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(ColorUtils.setAlphaComponent(palette.accent, if (palette.night) 0x22 else 0x16))
                setStroke(dp(1), ColorUtils.setAlphaComponent(palette.accent, 0x55))
            }
        }
        val textCol = column().apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(
            label(title, 13.5f, palette.primaryText, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        textCol.addView(
            label(subtitle, 11.8f, palette.secondaryText).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        row.addView(badge)
        row.addView(textCol)
        return row
    }

    private fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private fun bentoCard(palette: TvColorScheme): LinearLayout = column().apply {
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.blendARGB(palette.surface, palette.card, 0.45f),
                palette.surface,
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), palette.outline)
        }
    }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
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
