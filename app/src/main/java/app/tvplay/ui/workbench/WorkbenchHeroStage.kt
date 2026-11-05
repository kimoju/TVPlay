package app.tvplay.ui.workbench

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.tvplay.R
import app.tvplay.config.TvPlayBluetooth
import app.tvplay.config.TvSettingsRepository
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.runtime.CarPlayBackgroundSession
import app.tvplay.runtime.LastConnectionProblem
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.ui.theme.TvPlayButton
import app.tvplay.ui.theme.TvPlayButtonKind

typealias WorkbenchStagePane = WorkbenchHeroStage

/**
 * Left pane of the 10-foot Apple TV-style workbench (`TvWorkbenchActivity`).
 *
 * Displays:
 * 1. Live wireless / USB CarPlay receiver stage card (active session badge, primary launch CTA).
 * 2. Quick transport mode switcher (`无线 CarPlay` vs `USB 有线 CarPlay`, plus `Wi-Fi Direct` vs `家庭局域网`).
 * 3. 3-step visual iPhone connection guide.
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
    var onOpenBluetoothSettings: (() -> Unit)? = null

    fun updateStatus(
        hasActiveSession: Boolean,
        pendingReconnect: Boolean = false,
        onDisconnectSession: () -> Unit = {},
    ) {
        val wirelessEnabled = settings.connection.wirelessEnabled
        val wirelessMode = settings.connection.wirelessHotspotMode
        val modeLabel = when {
            !wirelessEnabled -> "USB 有线数据线直连"
            wirelessMode == TvWirelessMode.LAN_WIFI -> "无线 · 家庭局域网 Wi-Fi / 网线直连"
            else -> "无线 · Wi-Fi Direct 5GHz 免配置直连"
        }
        val btName = TvPlayBluetooth.localName(context) ?: "本机电视蓝牙"
        val pairedAddress = settings.connection.pairedPhoneAddress
        val pairedSummary = if (pairedAddress != null) {
            "${settings.connection.pairedPhoneName} ($pairedAddress)"
        } else {
            "自动识别已配对的 iPhone"
        }
        val hotspotSummary = when {
            !wirelessEnabled -> "USB 有线模式 · 支持即插即用低延迟投屏（无需蓝牙配对）"
            wirelessMode == TvWirelessMode.LAN_WIFI -> {
                val ssid = settings.connection.existingWifiSsid
                if (ssid.isNotBlank()) "局域网 Wi-Fi：$ssid" else "局域网模式（自动接入电视当前 Wi-Fi 或网线）"
            }
            else -> {
                val ch = settings.connection.wifiP2pPreferredChannel
                "Wi-Fi Direct 5GHz 点对点通道 · 信道：${if (ch == 0) "自动优选" else ch.toString()}"
            }
        }
        val lastProblem = if (pendingReconnect) {
            context.getString(R.string.reconnect_for_new_settings)
        } else {
            LastConnectionProblem.homeLine(context)
        }

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
        root.removeAllViews()
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        if (hadFocus && focusedTag != null) {
            view.findViewWithTag<View>(focusedTag)?.requestFocus()
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
    )

    interface Callbacks {
        fun onStartOrEnterProjection()
        fun onChooseTransportMode(wireless: Boolean)
        fun onChooseWirelessMode(mode: TvWirelessMode)
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
            setPadding(dp(4), dp(4), dp(8), dp(16))
        }

        // 1. Hero Connection Stage Card
        val heroCard = card(palette, highlighted = model.sessionActive)
        val badgeRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val statusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    when {
                        model.sessionActive -> 0xFF34D399.toInt()
                        model.sessionRunning -> 0xFF60A5FA.toInt()
                        else -> palette.secondaryText
                    },
                )
            }
        }
        val statusText = when {
            model.sessionActive -> "已连接 iPhone · 正在投屏"
            model.sessionRunning -> if (model.wirelessEnabled) "后台待命 · 等待 iPhone 蓝牙连接" else "后台待命 · 等待 iPhone USB 连接"
            else -> "就绪 · 内置离线 MFi 认证 · 点击下方开启投屏"
        }
        badgeRow.addView(statusDot)
        badgeRow.addView(label(statusText, 13, palette.secondaryText, bold = true))
        heroCard.addView(badgeRow)
        heroCard.addView(space(dp(14)))

        val titleRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val iconView = ImageView(context).apply {
            setImageResource(R.drawable.ic_carplay)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginEnd = dp(16)
            }
        }
        val titleCol = column().apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(
            label(
                "Apple CarPlay 电视接收端",
                23,
                palette.primaryText,
                bold = true,
            ),
        )
        titleCol.addView(space(dp(4)))
        val subtitleLine = if (model.wirelessEnabled) {
            "${model.wirelessModeLabel} · 电视蓝牙：${model.bluetoothDeviceName}"
        } else {
            "${model.wirelessModeLabel} · 支持插入数据线自动启动"
        }
        titleCol.addView(
            label(
                subtitleLine,
                14,
                palette.secondaryText,
            ),
        )
        titleRow.addView(iconView)
        titleRow.addView(titleCol)
        heroCard.addView(titleRow)

        model.hotspotSummary?.takeIf { it.isNotBlank() }?.let { hotspot ->
            heroCard.addView(space(dp(12)))
            heroCard.addView(label(hotspot, 13, palette.accent, bold = true))
        }

        model.lastProblemSummary?.takeIf { it.isNotBlank() }?.let { problem ->
            heroCard.addView(space(dp(12)))
            val warnBox = column().apply {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(palette.warningSurface)
                    setStroke(dp(1), palette.warningOutline)
                }
            }
            warnBox.addView(label("状态提示：$problem", 13, palette.warningText))
            heroCard.addView(warnBox)
        }

        heroCard.addView(space(dp(18)))

        val primaryButton = TvPlayButton(context).apply {
            tag = "primary_launch"
            text = when {
                model.sessionActive -> "进入全屏 CarPlay 画面"
                model.sessionRunning -> "打开全屏投屏等待画面"
                model.wirelessEnabled -> "立即启动无线 CarPlay 投屏"
                else -> "立即启动 USB 有线 CarPlay 投屏"
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            minHeight = dp(56)
            minimumHeight = dp(56)
            style(TvPlayButtonKind.PRIMARY, palette, overlay = false)
            setOnClickListener { callbacks.onStartOrEnterProjection() }
        }
        primaryLaunchButton = primaryButton
        heroCard.addView(
            primaryButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        if (model.sessionRunning || model.sessionActive) {
            heroCard.addView(space(dp(10)))
            val stopButton = TvPlayButton(context).apply {
                tag = "stop_session"
                text = "断开当前连接并停止后台服务"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                style(TvPlayButtonKind.TONAL_DANGER, palette, overlay = false)
                setOnClickListener { callbacks.onStopSession() }
            }
            heroCard.addView(
                stopButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        column.addView(heroCard)
        column.addView(space(dp(16)))

        // 2. Quick Transport & Wireless Mode Switcher
        val modeCard = card(palette, highlighted = false)
        modeCard.addView(label("连接模式切换（无线 / USB 有线）", 16, palette.primaryText, bold = true))
        modeCard.addView(space(dp(10)))

        val transportRow = row()
        val wirelessBtn = TvPlayButton(context).apply {
            tag = "mode_wireless"
            text = if (model.wirelessEnabled) "✓ 无线 CarPlay" else "无线 CarPlay"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            style(
                if (model.wirelessEnabled) TvPlayButtonKind.PRIMARY else TvPlayButtonKind.SECONDARY,
                palette,
                overlay = false,
            )
            setOnClickListener { callbacks.onChooseTransportMode(true) }
        }
        val usbBtn = TvPlayButton(context).apply {
            tag = "mode_usb"
            text = if (!model.wirelessEnabled) "✓ USB 有线直连" else "USB 有线直连"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            style(
                if (!model.wirelessEnabled) TvPlayButtonKind.PRIMARY else TvPlayButtonKind.SECONDARY,
                palette,
                overlay = false,
            )
            setOnClickListener { callbacks.onChooseTransportMode(false) }
        }
        transportRow.addView(
            wirelessBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) },
        )
        transportRow.addView(
            usbBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        modeCard.addView(transportRow)

        if (model.wirelessEnabled) {
            modeCard.addView(space(dp(10)))
            val switchRow = row()
            val isDirect = model.wirelessMode == TvWirelessMode.WIFI_DIRECT
            val directBtn = TvPlayButton(context).apply {
                tag = "mode_direct"
                text = if (isDirect) "✓ Wi-Fi Direct 直连" else "Wi-Fi Direct 直连"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                style(
                    if (isDirect) TvPlayButtonKind.PRIMARY else TvPlayButtonKind.SECONDARY,
                    palette,
                    overlay = false,
                )
                setOnClickListener { callbacks.onChooseWirelessMode(TvWirelessMode.WIFI_DIRECT) }
            }
            val lanBtn = TvPlayButton(context).apply {
                tag = "mode_lan"
                text = if (!isDirect) "✓ 家庭局域网 / 网线" else "家庭局域网 / 网线"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                style(
                    if (!isDirect) TvPlayButtonKind.PRIMARY else TvPlayButtonKind.SECONDARY,
                    palette,
                    overlay = false,
                )
                setOnClickListener { callbacks.onChooseWirelessMode(TvWirelessMode.LAN_WIFI) }
            }
            switchRow.addView(
                directBtn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) },
            )
            switchRow.addView(
                lanBtn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            modeCard.addView(switchRow)
        }
        column.addView(modeCard)
        column.addView(space(dp(16)))

        // 3. 3-Step Visual Connection Guide
        val guideCard = card(palette, highlighted = false)
        if (!model.wirelessEnabled) {
            guideCard.addView(label("3 步 USB 有线连接 iPhone 指南", 17, palette.primaryText, bold = true))
            guideCard.addView(space(dp(12)))
            guideCard.addView(
                stepItem(
                    "1",
                    "使用支持数据传输的 USB 线连接电视与 iPhone",
                    "请插在电视或机顶盒的 USB 接口上（仅充电线无法传输 CarPlay 数据）。",
                    palette,
                ),
            )
            guideCard.addView(space(dp(10)))
            guideCard.addView(
                stepItem(
                    "2",
                    "解锁 iPhone 并点击「信任此电脑」",
                    "在 iPhone 弹窗点击「信任」并输入锁屏密码，同时在电视弹窗允许 USB 设备访问。",
                    palette,
                ),
            )
            guideCard.addView(space(dp(10)))
            guideCard.addView(
                stepItem(
                    "3",
                    "点击上方「立即启动 USB 有线 CarPlay 投屏」",
                    "无需开启 VPN，直接通过 USB 数据线建立高速投屏通道。",
                    palette,
                ),
            )
        } else {
            val isDirect = model.wirelessMode == TvWirelessMode.WIFI_DIRECT
            guideCard.addView(label("3 步无线连接 iPhone 指南", 17, palette.primaryText, bold = true))
            guideCard.addView(space(dp(12)))
            if (isDirect) {
                guideCard.addView(
                    stepItem(
                        "1",
                        "开启 iPhone 与电视的蓝牙和 Wi-Fi",
                        "Wi-Fi Direct 免配置直连模式无需填写路由器密码，自动建立 5GHz 高速无线通道。",
                        palette,
                    ),
                )
            } else {
                guideCard.addView(
                    stepItem(
                        "1",
                        "将电视与 iPhone 连接至同一家庭路由器",
                        "电视可通过 Wi-Fi 或千兆网线接入路由器，确保与 iPhone 处于同一局域网。",
                        palette,
                    ),
                )
            }
            guideCard.addView(space(dp(10)))
            guideCard.addView(
                stepItem(
                    "2",
                    "蓝牙配对「${model.bluetoothDeviceName}」",
                    "在 iPhone「设置 → 蓝牙」中搜索并配对本电视（当前目标：${model.pairedPhoneSummary}）。",
                    palette,
                ),
            )
            guideCard.addView(space(dp(10)))
            guideCard.addView(
                stepItem(
                    "3",
                    "点击上方「立即启动无线 CarPlay 投屏」",
                    "首次连接时在 iPhone 弹窗确认「使用 CarPlay 车载」，即可进入 60fps 全屏画面。",
                    palette,
                ),
            )
        }

        column.addView(guideCard)
        scroll.addView(column)
        return scroll
    }

    private fun stepItem(
        number: String,
        title: String,
        subtitle: String,
        palette: TvColorScheme,
    ): View {
        val row = row().apply { gravity = Gravity.TOP }
        val badge = TextView(context).apply {
            text = number
            gravity = Gravity.CENTER
            setTextColor(palette.onAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                topMargin = dp(2)
                marginEnd = dp(12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.accent)
            }
        }
        val textCol = column().apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(label(title, 15, palette.primaryText, bold = true))
        textCol.addView(space(dp(2)))
        textCol.addView(label(subtitle, 13, palette.secondaryText))
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

    private fun card(palette: TvColorScheme, highlighted: Boolean): LinearLayout = column().apply {
        setPadding(dp(22), dp(20), dp(22), dp(20))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(if (highlighted) palette.card else palette.surface)
            setStroke(dp(if (highlighted) 2 else 1), if (highlighted) palette.accent else palette.outline)
        }
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
