package app.tvplay.ui.stage

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.tvplay.R
import app.tvplay.config.TvPlayBluetooth
import app.tvplay.config.TvPreferencesStore
import app.tvplay.config.WaitingScreenColors
import app.tvplay.engine.network.BluetoothLinkCheck
import app.tvplay.engine.network.BluetoothRadioCheck
import app.tvplay.engine.network.WirelessStartupFailure
import app.tvplay.engine.session.CarPlayStatus
import app.tvplay.engine.session.TvFailureReason
import app.tvplay.engine.session.TvProjectionProtocol
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.runtime.ConnectionFailureGuidance
import app.tvplay.ui.theme.AmbientMeshBackgroundDrawable
import app.tvplay.ui.theme.HeroBentoCardDrawable
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.ui.theme.TvIconGlyph
import app.tvplay.ui.theme.TvQrCodeRenderer
import app.tvplay.ui.theme.TvVectorIcons
import java.util.Locale

typealias ProjectionWaitingView = StageWaitingOverlay

/**
 * View bindings for the 10-foot waiting / connection progress stage in [TvStageActivity].
 */
class ProjectionWaitingStageBindings(
    val stageOverlay: FrameLayout,
    val stageStatusView: TextView,
    val connectionInstructionsView: TextView,
    val connectionHelpView: TextView,
    val reconnectBannerView: LinearLayout,
    val reconnectActionView: TextView,
    val backFromProjectionButton: TextView,
    val qrCardView: LinearLayout? = null,
    val qrImageView: ImageView? = null,
    val qrCaptionView: TextView? = null,
)

/**
 * Builds the 10-foot TV waiting / connection status overlay (`ui-ux-pro-max` Spatial Bento Console).
 */
object StageWaitingOverlay {
    fun build(
        context: Context,
        waitingColors: WaitingScreenColors,
        wirelessEnabled: Boolean,
        wirelessHotspotMode: TvWirelessMode,
        existingWifiSsid: String = "",
        onReconnectClicked: () -> Unit,
        onSettingsClicked: () -> Unit,
        onDisconnectClicked: () -> Unit,
        onBluetoothSettingsClicked: (() -> Unit)? = null,
        onSwitchModeClicked: (() -> Unit)? = null,
        onQrGuideClicked: (() -> Unit)? = null,
    ): ProjectionWaitingStageBindings {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt().coerceAtLeast(1)
        val smallPad = dp(10)
        val darkScheme = TvColorScheme.DARK

        val modeGlyph = when {
            !wirelessEnabled -> TvIconGlyph.USB
            wirelessHotspotMode == TvWirelessMode.LAN_WIFI -> TvIconGlyph.LAN
            else -> TvIconGlyph.WIFI
        }
        val protocol = runCatching { TvPreferencesStore.loadProjectionProtocol(context) }
            .getOrDefault(TvProjectionProtocol.AUTO)
        val carLinkPin = runCatching { TvPreferencesStore.loadCarLinkPinCode(context) }
            .getOrDefault("864209")
        val protoLabel = when (protocol) {
            TvProjectionProtocol.AUTO -> "CarPlay + CarLink 双模"
            TvProjectionProtocol.APPLE_CARPLAY -> "CarPlay"
            TvProjectionProtocol.ICCOA_CARLINK -> "ICCOA CarLink · PIN $carLinkPin"
            TvProjectionProtocol.ANDROID_AUTO -> "Android Auto"
            TvProjectionProtocol.HUAWEI_HICAR -> "华为 HiCar"
        }
        val modeBadgeText = when {
            !wirelessEnabled -> "USB 有线 $protoLabel 高速直连"
            wirelessHotspotMode == TvWirelessMode.LAN_WIFI -> {
                val ssidSuffix = existingWifiSsid.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                "无线 $protoLabel · 家庭局域网$ssidSuffix"
            }
            else -> "无线 $protoLabel · Wi-Fi Direct 5GHz 直连"
        }

        val topBadgeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(16), dp(7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * density
                setColor(ColorUtils.setAlphaComponent(0xFF3B82F6.toInt(), 0x26))
                setStroke(dp(1), ColorUtils.setAlphaComponent(0xFF60A5FA.toInt(), 0x70))
            }
            addView(
                ProgressBar(context).apply { isIndeterminate = true },
                LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    marginEnd = dp(10)
                },
            )
            addView(
                TextView(context).apply {
                    text = modeBadgeText
                    setTextColor(waitingColors.primaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    compoundDrawablePadding = dp(7)
                    val icon = TvVectorIcons.drawable(
                        context = context,
                        glyph = modeGlyph,
                        color = 0xFF60A5FA.toInt(),
                        sizeDp = 15,
                        strokeDp = 2f,
                    )
                    setCompoundDrawablesRelative(icon, null, null, null)
                },
            )
        }

        val emblemContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    ColorUtils.setAlphaComponent(0xFF3B82F6.toInt(), 0x42),
                    ColorUtils.setAlphaComponent(0xFF0E1523.toInt(), 0xCC),
                ),
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), ColorUtils.setAlphaComponent(0xFF38BDF8.toInt(), 0x80))
            }
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_carplay)
                },
                FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER),
            )
        }

        val statusView = TextView(context).apply {
            text = when (protocol) {
                TvProjectionProtocol.ICCOA_CARLINK ->
                    "正在启动安卓 CarLink 投屏服务 · 连接码：$carLinkPin"
                TvProjectionProtocol.AUTO ->
                    "正在启动 CarPlay + 安卓 CarLink 双模服务 · PIN：$carLinkPin"
                else -> context.getString(R.string.starting_carplay)
            }
            setTextColor(waitingColors.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val instructionsView = TextView(context).apply {
            text = connectionInstructions(
                context = context,
                status = null,
                wirelessEnabled = wirelessEnabled,
                wirelessHotspotMode = wirelessHotspotMode,
                existingWifiSsid = existingWifiSsid,
            )
            setTextColor(waitingColors.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f)
            setLineSpacing(0f, 1.28f)
            gravity = Gravity.START
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(ColorUtils.setAlphaComponent(waitingColors.buttonFill, 0x78))
                setStroke(dp(1), ColorUtils.setAlphaComponent(waitingColors.buttonBorder, 0x80))
            }
        }

        val qrImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val defaultQr = TvQrCodeRenderer.renderBitmap(
                content = "ICCOA://TVPLAY?PIN=$carLinkPin",
                targetSizePx = dp(84),
                quietZoneModules = 2,
            )
            if (defaultQr != null) {
                setImageBitmap(defaultQr)
            }
        }
        val qrCaptionView = TextView(context).apply {
            text = "连接码 $carLinkPin"
            setTextColor(waitingColors.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        val qrCardView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = if (wirelessEnabled && protocol.enablesCarLink) View.VISIBLE else View.GONE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(ColorUtils.setAlphaComponent(waitingColors.buttonFill, 0x90))
                setStroke(dp(1), ColorUtils.setAlphaComponent(0xFF38BDF8.toInt(), 0x88))
            }
            if (onQrGuideClicked != null) {
                isFocusable = true
                isClickable = true
                setOnClickListener { onQrGuideClicked() }
            }
            addView(
                FrameLayout(context).apply {
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(Color.WHITE)
                    }
                    addView(qrImageView, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(
                qrCaptionView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        val instructionsAndQrRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                instructionsView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                qrCardView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(10)
                },
            )
        }

        val helpView = TextView(context).apply {
            visibility = View.GONE
            setTextColor(waitingColors.helpText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(0f, 1.28f)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(waitingColors.helpBackground)
                setStroke(dp(1), waitingColors.helpBorder)
            }
        }

        val reconnectAction = createTvButton(
            context = context,
            text = context.getString(R.string.reconnect),
            fillColor = waitingColors.buttonFill,
            textColor = Color.WHITE,
            strokeColor = waitingColors.buttonBorder,
            glyph = TvIconGlyph.REFRESH,
            onClick = onReconnectClicked,
        )
        val reconnectBanner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(waitingColors.bannerBackground)
                setStroke(dp(1), waitingColors.bannerBorder)
            }
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.reconnect_for_new_settings)
                    setTextColor(waitingColors.primaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(10))
                },
            )
            addView(
                reconnectAction,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val quickReconnectButton = createTvButton(
            context = context,
            text = context.getString(R.string.reconnect),
            fillColor = 0xFF3B82F6.toInt(),
            textColor = Color.WHITE,
            strokeColor = 0xFF93C5FD.toInt(),
            glyph = TvIconGlyph.REFRESH,
            onClick = onReconnectClicked,
        )
        val bluetoothButton = if (onBluetoothSettingsClicked != null && protocol != TvProjectionProtocol.ICCOA_CARLINK) {
            createTvButton(
                context = context,
                text = "电视蓝牙配对",
                fillColor = waitingColors.buttonFill,
                textColor = Color.WHITE,
                strokeColor = waitingColors.buttonBorder,
                glyph = TvIconGlyph.BLUETOOTH,
                onClick = onBluetoothSettingsClicked,
            )
        } else {
            null
        }
        val qrGuideButton = if (onQrGuideClicked != null && protocol.enablesCarLink) {
            createTvButton(
                context = context,
                text = "扫码与连接码",
                fillColor = waitingColors.buttonFill,
                textColor = Color.WHITE,
                strokeColor = waitingColors.buttonBorder,
                glyph = TvIconGlyph.SPARKLES,
                onClick = onQrGuideClicked,
            )
        } else {
            null
        }
        val switchModeButton = if (onSwitchModeClicked != null) {
            createTvButton(
                context = context,
                text = "切换连接模式",
                fillColor = waitingColors.buttonFill,
                textColor = Color.WHITE,
                strokeColor = waitingColors.buttonBorder,
                glyph = TvIconGlyph.RADAR,
                onClick = onSwitchModeClicked,
            )
        } else {
            null
        }
        val settingsButton = createTvButton(
            context = context,
            text = context.getString(R.string.settings),
            fillColor = waitingColors.buttonFill,
            textColor = Color.WHITE,
            strokeColor = waitingColors.buttonBorder,
            glyph = TvIconGlyph.HOME,
            onClick = onSettingsClicked,
        )
        val disconnectButton = createTvButton(
            context = context,
            text = context.getString(R.string.disconnect),
            fillColor = 0xCC7F1D1D.toInt(),
            textColor = Color.WHITE,
            strokeColor = 0xFFFCA5A5.toInt(),
            glyph = TvIconGlyph.POWER,
            onClick = onDisconnectClicked,
        )

        val actionButtonsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * density
                setColor(ColorUtils.setAlphaComponent(0xFF0A0F1A.toInt(), 0xAA))
                setStroke(dp(1), ColorUtils.setAlphaComponent(waitingColors.buttonBorder, 0x66))
            }
            addView(
                quickReconnectButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    rightMargin = smallPad
                },
            )
            if (bluetoothButton != null) {
                addView(
                    bluetoothButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        rightMargin = smallPad
                    },
                )
            }
            if (qrGuideButton != null) {
                addView(
                    qrGuideButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        rightMargin = smallPad
                    },
                )
            }
            if (switchModeButton != null) {
                addView(
                    switchModeButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        rightMargin = smallPad
                    },
                )
            }
            addView(
                settingsButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    rightMargin = smallPad
                },
            )
            addView(
                disconnectButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val stageCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = true
            clipToPadding = true
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = if (waitingColors.background == Color.BLACK) {
                HeroBentoCardDrawable(darkScheme, cornerRadiusPx = dp(24).toFloat(), activeGlow = false)
            } else {
                HeroBentoCardDrawable(darkScheme, cornerRadiusPx = dp(24).toFloat(), activeGlow = true)
            }
            addView(topBadgeRow)
            addView(emblemContainer)
            addView(statusView)
            addView(
                instructionsAndQrRow,
                LinearLayout.LayoutParams(dp(580), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                },
            )
            addView(
                helpView,
                LinearLayout.LayoutParams(dp(580), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                },
            )
            addView(
                reconnectBanner,
                LinearLayout.LayoutParams(dp(580), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                },
            )
            addView(
                actionButtonsRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(14)
                },
            )
        }

        val overlay = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            background = AmbientMeshBackgroundDrawable(
                palette = darkScheme,
                pureBlack = waitingColors.background == Color.BLACK,
            )
            addView(
                stageCard,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }

        return ProjectionWaitingStageBindings(
            stageOverlay = overlay,
            stageStatusView = statusView,
            connectionInstructionsView = instructionsView,
            connectionHelpView = helpView,
            reconnectBannerView = reconnectBanner,
            reconnectActionView = reconnectAction,
            backFromProjectionButton = settingsButton,
            qrCardView = qrCardView,
            qrImageView = qrImageView,
            qrCaptionView = qrCaptionView,
        )
    }

    fun createTvButton(
        context: Context,
        label: String,
        primary: Boolean = false,
        danger: Boolean = false,
        onClick: () -> Unit,
    ): TextView = createTvButton(
        context = context,
        text = label,
        fillColor = when {
            danger -> 0xCC7F1D1D.toInt()
            primary -> 0xFF3B82F6.toInt()
            else -> 0xFF141E30.toInt()
        },
        textColor = Color.WHITE,
        strokeColor = when {
            danger -> 0xFFFCA5A5.toInt()
            primary -> 0xFF93C5FD.toInt()
            else -> 0xFF334155.toInt()
        },
        onClick = onClick,
    )

    fun createTvButton(
        context: Context,
        text: String,
        fillColor: Int,
        textColor: Int,
        strokeColor: Int,
        glyph: TvIconGlyph? = null,
        onClick: () -> Unit,
    ): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            val hPad = (18 * density).toInt()
            val vPad = (10 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            if (glyph != null) {
                compoundDrawablePadding = (7 * density).toInt()
                val icon = TvVectorIcons.drawable(context, glyph, textColor, sizeDp = 15, strokeDp = 2f)
                setCompoundDrawablesRelative(icon, null, null, null)
            }
            fun applyBg(focused: Boolean) {
                val topColor = ColorUtils.blendARGB(fillColor, Color.WHITE, if (focused) 0.20f else 0.08f)
                val bottomColor = if (focused) ColorUtils.blendARGB(fillColor, Color.WHITE, 0.10f) else fillColor
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(topColor, bottomColor),
                ).apply {
                    cornerRadius = 999f * density
                    setStroke(
                        ((if (focused) 2.5f else 1f) * density).toInt().coerceAtLeast(1),
                        if (focused) 0xFF38BDF8.toInt() else strokeColor,
                    )
                }
            }
            applyBg(false)
            setOnFocusChangeListener { _, hasFocus ->
                applyBg(hasFocus)
            }
            setOnClickListener { onClick() }
        }
    }

    fun connectionInstructions(
        context: Context,
        status: CarPlayStatus?,
        wirelessEnabled: Boolean,
        wirelessHotspotMode: TvWirelessMode,
        existingWifiSsid: String = "",
    ): String {
        val btName = TvPlayBluetooth.localName(context) ?: "本机电视蓝牙"
        val protocol = runCatching { TvPreferencesStore.loadProjectionProtocol(context) }
            .getOrDefault(TvProjectionProtocol.AUTO)
        val carLinkPin = runCatching { TvPreferencesStore.loadCarLinkPinCode(context) }
            .getOrDefault("864209")
        return when {
            status is CarPlayStatus.IphoneNotListed ->
                if (protocol == TvProjectionProtocol.ICCOA_CARLINK) {
                    "未检测到支持 AOA2 车联的安卓手机，请检查 USB 数据线并开启 USB 调试或车联权限"
                } else {
                    context.getString(
                        if (status.nonHubDeviceListed) R.string.connection_help_usb_device_not_iphone
                        else R.string.connection_help_no_usb_device,
                    )
                }
            !wirelessEnabled ->
                when (protocol) {
                    TvProjectionProtocol.ICCOA_CARLINK ->
                        "01  使用支持数据传输的 USB 线连接安卓手机与电视 USB 接口\n" +
                            "02  在手机上允许 USB 配件 / CarWith / 车联访问权限\n" +
                            "03  如手机弹出连接码验证，请输入 6 位连接码「$carLinkPin」"
                    TvProjectionProtocol.AUTO ->
                        context.getString(R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an) +
                            "\n（安卓 CarLink AOA2 有线连接码：$carLinkPin）"
                    else ->
                        context.getString(R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an)
                }
            wirelessHotspotMode == TvWirelessMode.LAN_WIFI -> {
                val ssidHint = existingWifiSsid.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                when (protocol) {
                    TvProjectionProtocol.ICCOA_CARLINK ->
                        "01  保持手机蓝牙与 Wi-Fi 开启（请勿在系统蓝牙中连接电视，避免触发电视蓝牙音乐）\n" +
                            "02  打开手机「设置 → 更多连接 → CarWith / Jovi InCar / Car+ 车联」点击无线连接\n" +
                            "03  在手机弹窗中输入 6 位连接码「$carLinkPin」，或点击下方「扫码与连接码」扫码直连"
                    TvProjectionProtocol.AUTO ->
                        "01  确保电视与手机连接至同一局域网 Wi-Fi$ssidHint\n" +
                            "02  iPhone 在系统蓝牙连接「$btName」；安卓请勿连系统蓝牙，直接打开「设置 → 更多连接 → CarWith/车联」\n" +
                            "03  iPhone 确认启用 CarPlay；小米 / vivo / OPPO 在弹窗输入连接码「$carLinkPin」"
                    else ->
                        "01  确保电视与手机连接至同一局域网 Wi-Fi$ssidHint\n" +
                            "02  如未配对，点击下方「电视蓝牙配对」开启可见性，并在手机中连接「$btName」\n" +
                            "03  在 iPhone 弹窗中确认「使用 CarPlay 车载」，自动进入全屏画面"
                }
            }
            else -> {
                when (protocol) {
                    TvProjectionProtocol.ICCOA_CARLINK ->
                        "01  保持手机蓝牙与 Wi-Fi 开启（请勿在系统蓝牙中配对电视，以免误入蓝牙音乐）\n" +
                            "02  打开手机「设置 → 更多连接 → CarWith / Jovi InCar / Car+ 车联」搜索连接\n" +
                            "03  在手机弹窗中输入 6 位连接码「$carLinkPin」，或用右侧二维码扫码加入 5GHz 热点"
                    TvProjectionProtocol.AUTO ->
                        "01  当前处于 Wi-Fi Direct 5GHz 高速点对点直连模式\n" +
                            "02  iPhone 连接蓝牙「$btName」；安卓请勿连系统蓝牙，直接打开「设置 → 更多连接 → CarWith/车联」\n" +
                            "03  iPhone 自动建立 CarPlay；小米 / vivo / OPPO 在弹窗输入连接码「$carLinkPin」"
                    else ->
                        "01  当前处于 Wi-Fi Direct 5GHz 高速点对点直连模式\n" +
                            "02  如未配对，点击下方「电视蓝牙配对」开启可见性，并在手机中连接「$btName」\n" +
                            "03  蓝牙握手完成后自动建立 5GHz 无线 CarPlay 通道"
                }
            }
        }
    }

    fun friendlyStage(
        context: Context,
        status: CarPlayStatus,
        wirelessEnabled: Boolean = true,
        wirelessHotspotMode: TvWirelessMode = TvWirelessMode.LAN_WIFI,
    ): String {
        if (status is CarPlayStatus.Failed) {
            if (status.knownCause != null) {
                return context.getString(ConnectionFailureGuidance.stage(status.knownCause!!))
            }
            if (status.startupFailure == WirelessStartupFailure.HOTSPOT_CONFIGURATION && status.message.isNotBlank()) {
                return status.message
            }
        }
        val protocol = runCatching { TvPreferencesStore.loadProjectionProtocol(context) }
            .getOrDefault(TvProjectionProtocol.AUTO)
        val carLinkPin = runCatching { TvPreferencesStore.loadCarLinkPinCode(context) }
            .getOrDefault("864209")
        if (protocol == TvProjectionProtocol.ICCOA_CARLINK) {
            return when (status) {
                CarPlayStatus.StartingHotspot ->
                    "正在启动 5GHz Wi-Fi Direct 直连热点与 BLE 广播…"
                is CarPlayStatus.HotspotReady ->
                    "安卓车联热点 ${status.ssid} (${status.band}) 已就绪 · 连接码：$carLinkPin"
                CarPlayStatus.WaitingForPairedIphone,
                CarPlayStatus.RunningWireless ->
                    "安卓 CarLink 广播就绪 (BLE 0xFCFB) · 手机连接码：$carLinkPin"
                CarPlayStatus.ConnectingBluetooth ->
                    "正在与手机进行蓝牙 BLE 车联握手…"
                CarPlayStatus.WirelessActive,
                CarPlayStatus.WirelessActiveFallback,
                CarPlayStatus.RunningControl ->
                    "正在开启全屏安卓车联画面…"
                CarPlayStatus.ControlEnded ->
                    "车联等待超时，正在重新刷新广播与热点…"
                CarPlayStatus.DiscoveringIphone,
                CarPlayStatus.WaitingForIphone ->
                    "正在等待安卓手机通过无线或 USB 连接 · 连接码：$carLinkPin"
                is CarPlayStatus.IphoneNotListed ->
                    "未检测到支持 AOA2 车联的安卓手机"
                CarPlayStatus.AttachingNetwork ->
                    "正在启动安卓 CarLink 投屏监听服务…"
                is CarPlayStatus.Failed ->
                    "安卓车联连接中断，正在自动重试…"
                else ->
                    "正在准备安卓 CarLink 投屏服务 · 连接码：$carLinkPin"
            }
        }
        if (protocol == TvProjectionProtocol.AUTO) {
            when (status) {
                is CarPlayStatus.HotspotReady ->
                    return "无线热点 ${status.ssid} (${status.band}) 已就绪 · 安卓连接码：$carLinkPin"
                CarPlayStatus.WaitingForPairedIphone,
                CarPlayStatus.RunningWireless ->
                    return "等待 iPhone 蓝牙连接或安卓 CarLink 接入 · 连接码：$carLinkPin"
                else -> {}
            }
        }
        val message = describeStatus(status, context.resources, wirelessEnabled, wirelessHotspotMode)
        val englishResources = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        ).resources
        val englishMessage = describeStatus(status, englishResources, wirelessEnabled, wirelessHotspotMode)
        return friendlyStage(context, message, englishMessage)
    }

    fun friendlyStage(
        context: Context,
        message: String,
        englishMessage: String = message,
    ): String = when {
        message == context.getString(R.string.vpn_authorization_unavailable) -> message
        message == context.getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per) -> message
        message == context.getString(R.string.waiting_for_mfi_coprocessor) ||
            message == context.getString(R.string.requesting_mfi_usb_permission) -> message
        message == context.getString(R.string.existing_wifi_attaching) ||
            message == context.getString(R.string.starting_wireless_hotspot) ||
            message == context.getString(R.string.starting_airplay_service) ||
            message.startsWith("局域网网络已就绪") ||
            message.startsWith("无线直连已就绪") -> message
        message == context.getString(R.string.hotspot_network_not_ready) ||
            message == context.getString(R.string.first_tcp_timeout) ||
            message == context.getString(R.string.wifi_direct_refused_beside_hotspot) ||
            message.endsWith(context.getString(R.string.wireless_startup_retries_exhausted)) -> message
        TvFailureReason.entries.any { message == context.getString(ConnectionFailureGuidance.stage(it)) } -> message
        message == context.getString(R.string.car_sees_no_usb_device) ||
            message == context.getString(R.string.car_sees_usb_device_not_iphone) -> message
        message == context.getString(R.string.status_waiting_reenumeration) ->
            context.getString(R.string.getting_carplay_ready)
        englishMessage.contains("CarPlay configuration request", true) ->
            context.getString(R.string.iphone_usb_mode_rejected)
        englishMessage.contains("Turn on Wi-Fi", true) ->
            context.getString(R.string.turn_on_wi_fi_in_the_head_unit_s_settings_to_connect)
        englishMessage.contains(BluetoothRadioCheck.RADIO_OFF_MARK, true) ->
            context.getString(R.string.turn_on_bluetooth_in_the_head_unit_s_settings_to_connect)
        englishMessage.contains("Allow precise Location", true) ->
            context.getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p)
        englishMessage.contains("Allow Nearby devices", true) ->
            context.getString(R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per)
        englishMessage.contains("createGroup failed", true) ->
            context.getString(R.string.the_head_unit_couldn_t_start_carplay_wi_fi_check_wi_fi_and)
        englishMessage.contains("needs a reset", true) ->
            context.getString(R.string.a_previous_wi_fi_direct_connection_is_still_running_reset)
        englishMessage.contains(BluetoothLinkCheck.UNREAL_MARK, true) ->
            context.getString(R.string.bluetooth_connections_not_real)
        englishMessage.contains(BluetoothLinkCheck.NO_SOCKETS_MARK, true) ->
            context.getString(R.string.bluetooth_cannot_open_connections)
        englishMessage.contains("socket", true) || englishMessage.contains("RFCOMM", true) ->
            context.getString(R.string.your_iphone_isn_t_available_unlock_it_and_check_bluetooth)
        englishMessage.contains("unsupported", true) || englishMessage.contains("not supported", true) ->
            context.getString(R.string.this_head_unit_may_not_support_wireless_carplay_try_a_usb)
        englishMessage.contains("denied", true) || englishMessage.contains("permission", true) ->
            context.getString(R.string.allow_the_connection_permission_to_continue)
        englishMessage.contains("Failed", true) || englishMessage.contains("error", true) ->
            context.getString(R.string.connection_interrupted_retrying)
        englishMessage.contains("Waiting for iPhone", true) || englishMessage.contains("Discovering iPhone", true) ->
            context.getString(R.string.connect_your_iphone_with_a_usb_cable)
        englishMessage.contains("paired", true) -> context.getString(R.string.looking_for_your_paired_iphone)
        englishMessage.contains("Bluetooth", true) -> context.getString(R.string.connecting_to_your_iphone)
        englishMessage.contains("reconnect", true) || englishMessage.contains("ended", true) ->
            context.getString(R.string.reconnecting_to_your_iphone)
        englishMessage.contains("active", true) || englishMessage.contains("running", true) ->
            context.getString(R.string.opening_carplay)
        else -> context.getString(R.string.getting_carplay_ready)
    }

    fun describeStatus(
        status: CarPlayStatus,
        res: Resources,
        wirelessEnabled: Boolean,
        wirelessHotspotMode: TvWirelessMode,
    ): String = when (status) {
        CarPlayStatus.DiscoveringMfi -> res.getString(R.string.preparing_mfi_authentication)
        CarPlayStatus.WaitingForMfi -> res.getString(R.string.waiting_for_mfi_coprocessor)
        CarPlayStatus.RequestingMfiPermission -> res.getString(R.string.requesting_mfi_usb_permission)
        CarPlayStatus.MfiReady -> res.getString(R.string.mfi_authentication_ready)
        CarPlayStatus.StartingHotspot -> res.getString(
            if (wirelessHotspotMode == TvWirelessMode.LAN_WIFI) R.string.existing_wifi_attaching
            else R.string.starting_wireless_hotspot,
        )
        is CarPlayStatus.HotspotReady ->
            if (wirelessHotspotMode == TvWirelessMode.LAN_WIFI) {
                res.getString(R.string.existing_wifi_ready, status.ssid, status.band, status.channel, status.address)
            } else {
                res.getString(
                    R.string.status_hotspot_ready,
                    status.backend,
                    status.ssid,
                    status.band,
                    if (status.channel == 0) res.getString(R.string.auto_value) else status.channel.toString(),
                )
            }
        CarPlayStatus.WaitingForPairedIphone -> res.getString(R.string.waiting_for_paired_iphone)
        CarPlayStatus.ConnectingBluetooth -> res.getString(R.string.connecting_bluetooth)
        CarPlayStatus.RunningWireless -> res.getString(R.string.wireless_carplay_control_running)
        CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback ->
            res.getString(R.string.wireless_carplay_active)
        CarPlayStatus.DiscoveringIphone -> res.getString(R.string.discovering_iphone)
        CarPlayStatus.WaitingForIphone -> res.getString(R.string.waiting_for_iphone_over_usb)
        is CarPlayStatus.IphoneNotListed ->
            res.getString(
                if (status.nonHubDeviceListed) R.string.car_sees_usb_device_not_iphone
                else R.string.car_sees_no_usb_device,
            )
        CarPlayStatus.RequestingIphonePermission -> res.getString(R.string.requesting_iphone_usb_permission)
        CarPlayStatus.WaitingForReenumeration -> res.getString(R.string.status_waiting_reenumeration)
        CarPlayStatus.SelectingConfiguration -> res.getString(R.string.selecting_carplay_configuration)
        CarPlayStatus.OpeningDataPaths -> res.getString(R.string.opening_usb_data_paths)
        CarPlayStatus.Pairing -> res.getString(R.string.pairing_with_iphone)
        CarPlayStatus.ConnectingControl -> res.getString(R.string.connecting_iap2_control)
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) res.getString(R.string.starting_airplay_service)
            else res.getString(R.string.status_attaching_ncm)
        CarPlayStatus.RunningControl -> res.getString(R.string.carplay_control_running)
        CarPlayStatus.ControlEnded -> res.getString(R.string.carplay_control_window_ended)
        is CarPlayStatus.Failed -> when (status.startupFailure) {
            WirelessStartupFailure.HOTSPOT_NOT_READY -> res.getString(R.string.hotspot_network_not_ready)
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> res.getString(R.string.first_tcp_timeout)
            WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT ->
                res.getString(R.string.wifi_direct_refused_beside_hotspot)
            else -> res.getString(R.string.status_failed, status.message)
        }
    }

    fun helpMessage(
        context: Context,
        failed: CarPlayStatus.Failed,
        wirelessEnabled: Boolean = true,
        wirelessMode: TvWirelessMode = TvWirelessMode.LAN_WIFI,
    ): String = context.getString(
        ConnectionFailureGuidance.message(
            detail = failed.message,
            wireless = wirelessEnabled,
            mode = wirelessMode,
            wifiResetRequired = failed.wifiResetRequired,
            startupFailure = failed.startupFailure,
            knownCause = failed.knownCause,
        ),
    )
}
