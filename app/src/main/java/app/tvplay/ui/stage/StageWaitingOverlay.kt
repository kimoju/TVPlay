package app.tvplay.ui.stage

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.tvplay.R
import app.tvplay.config.TvPlayBluetooth
import app.tvplay.config.WaitingScreenColors
import app.tvplay.engine.network.BluetoothLinkCheck
import app.tvplay.engine.network.BluetoothRadioCheck
import app.tvplay.engine.network.WirelessStartupFailure
import app.tvplay.engine.session.CarPlayStatus
import app.tvplay.engine.session.TvFailureReason
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.runtime.ConnectionFailureGuidance
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
)

/**
 * Builds the 10-foot TV waiting / connection status overlay and formats user-facing stage text.
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
    ): ProjectionWaitingStageBindings {
        val density = context.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val smallPad = (12 * density).toInt()

        val statusView = TextView(context).apply {
            text = context.getString(R.string.starting_carplay)
            setTextColor(waitingColors.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, smallPad, 0, 0)
        }
        val helpView = TextView(context).apply {
            visibility = View.GONE
            setTextColor(waitingColors.helpText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            val hPad = (16 * density).toInt()
            val vPad = (12 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(waitingColors.helpBackground)
                setStroke((1 * density).toInt().coerceAtLeast(1), waitingColors.helpBorder)
            }
        }
        val reconnectAction = createTvButton(
            context = context,
            text = context.getString(R.string.reconnect),
            fillColor = waitingColors.buttonFill,
            textColor = Color.WHITE,
            strokeColor = waitingColors.buttonBorder,
            onClick = onReconnectClicked,
        )
        val reconnectBanner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            val hPad = (16 * density).toInt()
            val vPad = (12 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(waitingColors.bannerBackground)
                setStroke((1 * density).toInt().coerceAtLeast(1), waitingColors.bannerBorder)
            }
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.reconnect_for_new_settings)
                    setTextColor(waitingColors.primaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, (10 * density).toInt())
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
            fillColor = 0xFF2563EB.toInt(),
            textColor = Color.WHITE,
            strokeColor = 0xFF93C5FD.toInt(),
            onClick = onReconnectClicked,
        )
        val bluetoothButton = if (onBluetoothSettingsClicked != null) {
            createTvButton(
                context = context,
                text = "电视蓝牙配对",
                fillColor = waitingColors.buttonFill,
                textColor = Color.WHITE,
                strokeColor = waitingColors.buttonBorder,
                onClick = onBluetoothSettingsClicked,
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
            onClick = onSettingsClicked,
        )
        val disconnectButton = createTvButton(
            context = context,
            text = context.getString(R.string.disconnect),
            fillColor = 0xCC7F1D1D.toInt(),
            textColor = Color.WHITE,
            strokeColor = 0xFFFCA5A5.toInt(),
            onClick = onDisconnectClicked,
        )

        val actionButtonsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
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
            setPadding(pad, pad, pad, pad)
            addView(
                ProgressBar(context).apply { isIndeterminate = true },
                LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt()).apply {
                    bottomMargin = (18 * density).toInt()
                },
            )
            addView(statusView)
            addView(
                instructionsView,
                LinearLayout.LayoutParams((520 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(
                helpView,
                LinearLayout.LayoutParams((520 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (14 * density).toInt()
                },
            )
            addView(
                reconnectBanner,
                LinearLayout.LayoutParams((520 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (14 * density).toInt()
                },
            )
            addView(
                actionButtonsRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = (22 * density).toInt()
                },
            )
        }

        val overlay = FrameLayout(context).apply {
            background = ColorDrawable(waitingColors.background)
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
            primary -> 0xFF2563EB.toInt()
            else -> 0xFF1E293B.toInt()
        },
        textColor = Color.WHITE,
        strokeColor = when {
            danger -> 0xFFFCA5A5.toInt()
            primary -> 0xFF93C5FD.toInt()
            else -> 0xFF475569.toInt()
        },
        onClick = onClick,
    )

    fun createTvButton(
        context: Context,
        text: String,
        fillColor: Int,
        textColor: Int,
        strokeColor: Int,
        onClick: () -> Unit,
    ): TextView {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            val hPad = (20 * density).toInt()
            val vPad = (10 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            fun applyBg(focused: Boolean) {
                background = GradientDrawable().apply {
                    cornerRadius = 999f * density
                    setColor(fillColor)
                    setStroke(
                        ((if (focused) 2.5f else 1f) * density).toInt().coerceAtLeast(1),
                        if (focused) Color.WHITE else strokeColor,
                    )
                }
            }
            applyBg(false)
            setOnFocusChangeListener { _, hasFocus ->
                applyBg(hasFocus)
                animate()
                    .scaleX(if (hasFocus) 1.06f else 1.0f)
                    .scaleY(if (hasFocus) 1.06f else 1.0f)
                    .setDuration(110L)
                    .start()
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
        return when {
            status is CarPlayStatus.IphoneNotListed ->
                context.getString(
                    if (status.nonHubDeviceListed) R.string.connection_help_usb_device_not_iphone
                    else R.string.connection_help_no_usb_device,
                )
            !wirelessEnabled ->
                context.getString(R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an)
            wirelessHotspotMode == TvWirelessMode.LAN_WIFI -> {
                val ssidHint = existingWifiSsid.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                "1. 确保电视与 iPhone 连接至同一局域网 Wi-Fi$ssidHint\n" +
                    "2. 在 iPhone「设置 → 蓝牙」中搜索并配对「$btName」\n" +
                    "3. 在 iPhone 弹窗中确认「使用 CarPlay 车载」，即可全屏投屏"
            }
            else ->
                "1. 当前处于 Wi-Fi Direct 5GHz 直连模式\n" +
                    "2. 在 iPhone「设置 → 蓝牙」中搜索并配对「$btName」\n" +
                    "3. 蓝牙握手后将自动建立点对点无线 CarPlay 通道"
        }
    }

    fun friendlyStage(
        context: Context,
        status: CarPlayStatus,
        wirelessEnabled: Boolean = true,
        wirelessHotspotMode: TvWirelessMode = TvWirelessMode.LAN_WIFI,
    ): String {
        if (status is CarPlayStatus.Failed && status.knownCause != null) {
            return context.getString(ConnectionFailureGuidance.stage(status.knownCause!!))
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
