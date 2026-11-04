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
 * 1. Live connection stage card (wireless / USB status, active session badge, primary launch CTA).
 * 2. Quick transport switcher (Wireless CarPlay vs USB Wired CarPlay).
 * 3. 3-step visual iPhone connection guide with the TV's Bluetooth name and LAN/Wi-Fi Direct tips.
 */
class WorkbenchHeroStage(
    private val context: Context,
    private val settings: TvSettingsRepository = TvSettingsRepository(context),
) {
    val root: FrameLayout = FrameLayout(context)
    var primaryLaunchButton: View = View(context)
        private set
    var onLaunchProjection: (() -> Unit)? = null
    var onToggleTransportMode: (() -> Unit)? = null
    var onOpenBluetoothSettings: (() -> Unit)? = null

    fun updateStatus(
        hasActiveSession: Boolean,
        pendingReconnect: Boolean = false,
        onDisconnectSession: () -> Unit = {},
    ) {
        val wireless = settings.connection.wirelessEnabled
        val wirelessMode = settings.connection.wirelessHotspotMode
        val modeLabel = if (wirelessMode == TvWirelessMode.LAN_WIFI) {
            "家庭局域网 Wi-Fi"
        } else {
            "Wi-Fi Direct 5GHz 直连"
        }
        val btName = TvPlayBluetooth.localName(context) ?: "TVPlay"
        val pairedAddress = settings.connection.pairedPhoneAddress
        val pairedSummary = if (pairedAddress != null) {
            "${settings.connection.pairedPhoneName} ($pairedAddress)"
        } else {
            "尚未指定 iPhone"
        }
        val hotspotSummary = when {
            !wireless -> null
            wirelessMode == TvWirelessMode.LAN_WIFI -> {
                val ssid = settings.connection.existingWifiSsid
                if (ssid.isNotBlank()) "局域网 SSID：$ssid" else "局域网共享模式（与电视同路由）"
            }
            else -> "Wi-Fi Direct 信道：${if (settings.connection.wifiP2pPreferredChannel == 0) "自动" else settings.connection.wifiP2pPreferredChannel}"
        }
        val lastProblem = if (pendingReconnect) {
            context.getString(R.string.reconnect_for_new_settings)
        } else {
            LastConnectionProblem.homeLine(context)
        }

        val view = build(
            model = StageModel(
                wirelessEnabled = wireless,
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
                    onLaunchProjection?.invoke()
                }

                override fun onToggleTransport(wireless: Boolean) {
                    if (settings.connection.wirelessEnabled != wireless) {
                        onToggleTransportMode?.invoke()
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
        root.removeAllViews()
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }
    data class StageModel(
        val wirelessEnabled: Boolean,
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
        fun onToggleTransport(wireless: Boolean)
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
            model.sessionRunning -> "后台待命 · 等待 iPhone 连接"
            else -> "就绪 · 点击下方按钮开启投屏舞台"
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
                if (model.wirelessEnabled) "无线 Apple CarPlay" else "USB 有线 Apple CarPlay",
                24,
                palette.primaryText,
                bold = true,
            ),
        )
        titleCol.addView(space(dp(4)))
        titleCol.addView(
            label(
                if (model.wirelessEnabled) {
                    "${model.wirelessModeLabel} · 蓝牙名称：${model.bluetoothDeviceName}"
                } else {
                    "通过 USB 数据线直连 iPhone · 零延迟高码率传输"
                },
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
            warnBox.addView(label("上次连接提示：$problem", 13, palette.warningText))
            heroCard.addView(warnBox)
        }

        heroCard.addView(space(dp(18)))

        val primaryButton = TvPlayButton(context).apply {
            text = when {
                model.sessionActive -> "进入全屏 CarPlay 画面"
                model.sessionRunning -> "打开全屏投屏等待舞台"
                else -> "立即启动全屏 CarPlay 投屏"
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

        // 2. Quick Transport Switcher
        val modeCard = card(palette, highlighted = false)
        modeCard.addView(label("连接方式快速切换", 16, palette.primaryText, bold = true))
        modeCard.addView(space(dp(10)))
        val switchRow = row()
        val wirelessBtn = TvPlayButton(context).apply {
            text = if (model.wirelessEnabled) "✓ 无线 CarPlay" else "无线 CarPlay"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            style(
                if (model.wirelessEnabled) TvPlayButtonKind.PRIMARY else TvPlayButtonKind.SECONDARY,
                palette,
                overlay = false,
            )
            setOnClickListener { callbacks.onToggleTransport(true) }
        }
        val usbBtn = TvPlayButton(context).apply {
            text = if (!model.wirelessEnabled) "✓ USB 有线连接" else "USB 有线连接"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            style(
                if (!model.wirelessEnabled) TvPlayButtonKind.PRIMARY else TvPlayButtonKind.SECONDARY,
                palette,
                overlay = false,
            )
            setOnClickListener { callbacks.onToggleTransport(false) }
        }
        switchRow.addView(
            wirelessBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) },
        )
        switchRow.addView(
            usbBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        modeCard.addView(switchRow)
        column.addView(modeCard)
        column.addView(space(dp(16)))

        // 3. 3-Step Visual Connection Guide
        val guideCard = card(palette, highlighted = false)
        guideCard.addView(label("3 步连接 iPhone 指南", 17, palette.primaryText, bold = true))
        guideCard.addView(space(dp(12)))

        if (model.wirelessEnabled) {
            guideCard.addView(
                stepItem(
                    "1",
                    "开启 iPhone 蓝牙与 Wi-Fi",
                    "确保电视与 iPhone 处于同一局域网 Wi-Fi，或开启 Wi-Fi Direct 直连。",
                    palette,
                ),
            )
            guideCard.addView(space(dp(10)))
            guideCard.addView(
                stepItem(
                    "2",
                    "蓝牙配对「${model.bluetoothDeviceName}」",
                    "在 iPhone「设置 → 蓝牙」中连接本机，已配对目标：${model.pairedPhoneSummary}。",
                    palette,
                ),
            )
            guideCard.addView(space(dp(10)))
            guideCard.addView(
                stepItem(
                    "3",
                    "确认启用 CarPlay 车载",
                    "首次连接时在 iPhone 弹窗点击「使用 CarPlay 车载」，画面将自动全屏呈现。",
                    palette,
                ),
            )
        } else {
            guideCard.addView(
                stepItem(
                    "1",
                    "使用数据线连接 iPhone 与电视 USB 口",
                    "请使用支持数据传输的 Lightning / USB-C 线缆插入电视或机顶盒 USB 接口。",
                    palette,
                ),
            )
            guideCard.addView(space(dp(10)))
            guideCard.addView(
                stepItem(
                    "2",
                    "解锁 iPhone 并点击「信任此电脑」",
                    "在电视弹出 USB 权限提示时勾选默认允许并点击确定。",
                    palette,
                ),
            )
            guideCard.addView(space(dp(10)))
            guideCard.addView(
                stepItem(
                    "3",
                    "自动进入全屏 CarPlay",
                    "握手完成后将自动启动 60fps 硬件解码投屏。",
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
