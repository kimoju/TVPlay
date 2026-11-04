package app.tvplay.ui.workbench

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.tvplay.CarPlayBackgroundSession
import app.tvplay.LastConnectionProblem
import app.tvplay.PendingReconnect
import app.tvplay.TvPlayBluetooth
import app.tvplay.TvPlayButton
import app.tvplay.TvPlayButtonKind
import app.tvplay.TvPlayPalette
import app.tvplay.orchestration.WirelessHotspotMode
import app.tvplay.settings.TvPlaySettingsRepository

/**
 * Left pane of the 10-foot TVPlay Workbench:
 * - Hero status card with live CarPlay session indicator and primary projection actions
 * - 3-step TV / Projector wireless connection guide
 */
internal class WorkbenchStagePane(
    private val context: Context,
    private val settings: TvPlaySettingsRepository,
) {
    fun build(
        palette: TvPlayPalette,
        setupError: String?,
        onOpenCarPlay: () -> Unit,
        onReconnectIfRunning: () -> Unit,
        onMarkReconnectNeeded: () -> Unit,
        onOpenSystemIntent: (Intent) -> Unit,
        onSessionStopped: () -> Unit,
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Brand & Status Hero Card
        val heroCard = card(palette).apply {
            setPadding(dp(24), dp(22), dp(24), dp(22))
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            label("TVPlay", 30, palette.primaryText, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        titleRow.addView(badge("离线 MFi 已就绪", palette.success))
        heroCard.addView(titleRow)

        heroCard.addView(space(dp(6)))
        heroCard.addView(label("Apple CarPlay 无线与有线大屏投屏工作台", 15, palette.secondaryText))

        heroCard.addView(space(dp(16)))
        val hasSession = CarPlayBackgroundSession.hasSession()
        val statusText = when {
            setupError != null -> "认证初始化异常：$setupError"
            hasSession -> "● 当前 CarPlay 投屏会话正在运行"
            else -> "● 准备就绪 · 等待 iPhone 发起无线或 USB 投屏"
        }
        val statusColor = when {
            setupError != null -> palette.warning
            hasSession -> palette.success
            else -> palette.accent
        }
        heroCard.addView(label(statusText, 16, statusColor, bold = true))

        LastConnectionProblem.homeLine(context)?.let { problemLine ->
            heroCard.addView(space(dp(8)))
            heroCard.addView(label(problemLine, 13, palette.warning))
        }

        if (PendingReconnect.isPending(CarPlayBackgroundSession.currentController())) {
            heroCard.addView(space(dp(12)))
            heroCard.addView(
                label("部分画质或音频设置已更改，将在下次连接时生效。", 13, palette.warning),
            )
            heroCard.addView(space(dp(8)))
            heroCard.addView(
                actionButton("立即重新连接使设置生效", primary = false, palette = palette) {
                    onReconnectIfRunning()
                },
            )
        }

        heroCard.addView(space(dp(18)))
        val primaryLabel = if (hasSession) {
            "返回全屏 CarPlay 投屏画面"
        } else {
            "立即进入全屏 CarPlay 投屏"
        }
        heroCard.addView(
            actionButton(primaryLabel, primary = true, palette = palette) {
                onOpenCarPlay()
            }.apply {
                requestFocus()
            },
        )

        heroCard.addView(space(dp(10)))
        val wirelessMode = settings.connection.wirelessHotspotMode
        val wirelessModeText = when (wirelessMode) {
            WirelessHotspotMode.EXISTING_WIFI -> "同一局域网 Wi-Fi（推荐电视使用）"
            WirelessHotspotMode.WIFI_P2P -> "Wi-Fi Direct 5GHz 直连"
            else -> "手动无线热点"
        }
        heroCard.addView(
            actionButton("无线通道 · $wirelessModeText", primary = false, palette = palette) {
                val nextMode = if (wirelessMode == WirelessHotspotMode.EXISTING_WIFI) {
                    WirelessHotspotMode.WIFI_P2P
                } else {
                    WirelessHotspotMode.EXISTING_WIFI
                }
                settings.connection.wirelessHotspotMode = nextMode
                settings.connection.wirelessEnabled = true
                onMarkReconnectNeeded()
            },
        )

        heroCard.addView(space(dp(10)))
        heroCard.addView(
            actionButton("打开系统蓝牙配对设置", primary = false, palette = palette) {
                onOpenSystemIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            },
        )

        if (hasSession) {
            heroCard.addView(space(dp(10)))
            heroCard.addView(
                dangerButton("断开当前 CarPlay 投屏会话", palette = palette) {
                    CarPlayBackgroundSession.stop {
                        onSessionStopped()
                    }
                },
            )
        }

        container.addView(heroCard)
        container.addView(space(dp(16)))

        // 3-Step TV Connection Guide Card
        val guideCard = card(palette).apply {
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }
        guideCard.addView(label("电视 / 投影仪三步无线连接指南", 18, palette.primaryText, bold = true))
        guideCard.addView(space(dp(12)))

        val step1Desc = when (wirelessMode) {
            WirelessHotspotMode.EXISTING_WIFI -> {
                val ssid = settings.connection.existingWifiSsid.ifBlank { "同一家庭路由器 Wi-Fi" }
                "确保电视与 iPhone 连接在 $ssid（支持电视插网线、iPhone 连同一路由器 Wi-Fi）。"
            }
            WirelessHotspotMode.WIFI_P2P ->
                "使用 Wi-Fi Direct 点对点 5GHz 直连，无需路由器，iPhone 将通过蓝牙协商直连通道。"
            else ->
                "确保电视与 iPhone 处于同一无线网络环境。"
        }
        guideCard.addView(stepRow("1", "确认无线通道", step1Desc, palette))
        guideCard.addView(space(dp(10)))

        val btName = TvPlayBluetooth.localName(context) ?: "Android TV"
        guideCard.addView(
            stepRow(
                "2",
                "iPhone 蓝牙配对连接",
                "在 iPhone 上打开「设置 → 通用 → CarPlay 车载」，在可用设备中选择「$btName」完成配对。",
                palette,
            ),
        )
        guideCard.addView(space(dp(10)))
        guideCard.addView(
            stepRow(
                "3",
                "电视遥控器全屏操控",
                "连接成功后自动进入全屏画面。遥控器方向键移动焦点、确认键点击，按「菜单键」或「长按返回键」呼出画质与控制面板。",
                palette,
            ),
        )

        container.addView(guideCard)
        return container
    }

    private fun card(palette: TvPlayPalette): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(20), dp(22), dp(20))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setColor(palette.surface)
            setStroke(dp(1), palette.outline)
        }
    }

    private fun badge(text: String, tintColor: Int): TextView = TextView(context).apply {
        this.text = text
        setTextColor(tintColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(ColorUtils.setAlphaComponent(tintColor, 0x24))
            setStroke(dp(1), ColorUtils.setAlphaComponent(tintColor, 0x66))
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
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
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

    private fun actionButton(
        label: String,
        primary: Boolean,
        palette: TvPlayPalette,
        onClick: () -> Unit,
    ): TvPlayButton = TvPlayButton(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        minHeight = dp(54)
        minimumHeight = dp(54)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        isFocusable = true
        style(
            if (primary) TvPlayButtonKind.PRIMARY else TvPlayButtonKind.SECONDARY,
            palette,
            overlay = false,
        )
        setOnClickListener { onClick() }
    }

    private fun dangerButton(label: String, palette: TvPlayPalette, onClick: () -> Unit): TvPlayButton =
        TvPlayButton(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            minHeight = dp(52)
            minimumHeight = dp(52)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isFocusable = true
            style(TvPlayButtonKind.TONAL_DANGER, palette, overlay = false)
            setOnClickListener { onClick() }
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
