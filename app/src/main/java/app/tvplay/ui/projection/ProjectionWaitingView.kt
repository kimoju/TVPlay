package app.tvplay.ui.projection

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.tvplay.ConnectionFailureGuidance
import app.tvplay.R
import app.tvplay.TvPlayBluetooth
import app.tvplay.WaitingScreenColors
import app.tvplay.network.WirelessStartupFailure
import app.tvplay.orchestration.CarPlayStatus
import app.tvplay.orchestration.ConnectionFailureCause
import app.tvplay.orchestration.WirelessHotspotMode
import app.tvplay.transport.BluetoothLinkCheck
import app.tvplay.transport.BluetoothRadioCheck
import kotlin.math.roundToInt

internal class ProjectionWaitingStageBindings(
    val viewport: FrameLayout,
    val stageStatusView: TextView,
    val connectionInstructionsView: TextView,
    val connectionHelpView: TextView,
    val startupRetryButton: View,
    val wifiRecoveryButton: View,
    val settingsGestureHint: TextView,
)

/**
 * Full-screen 10-foot TV waiting stage and status formatting for [app.tvplay.TvPlayProjectionActivity].
 */
internal object ProjectionWaitingView {
    fun build(
        context: Context,
        appNight: Boolean,
        initialInstructions: String,
        onRetryClicked: () -> Unit,
        onWifiRecoveryClicked: () -> Unit,
        onBackToWorkbenchClicked: () -> Unit,
    ): ProjectionWaitingStageBindings {
        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

        val viewport = FrameLayout(context).apply {
            setBackgroundColor(WaitingScreenColors.of(appNight).background)
            isClickable = true
            setPadding(dp(48), dp(36), dp(48), dp(36))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(40), dp(36), dp(40), dp(32))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#131824"))
                setStroke(dp(1), Color.parseColor("#28344E"))
            }
        }

        val headerBadge = TextView(context).apply {
            text = "TVPlay · Apple CarPlay 大屏投屏接收端"
            textSize = 14f
            setTextColor(Color.parseColor("#64B5F6"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#17263C"))
            }
        }
        card.addView(headerBadge, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(18) })

        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = context.getString(R.string.carplay)
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(16) })

        val stage = TextView(context).apply {
            text = "正在等待 iPhone 连接 CarPlay…"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        card.addView(stage, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val instructions = TextView(context).apply {
            text = initialInstructions
            textSize = 16f
            setTextColor(Color.parseColor("#B0BEC5"))
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#0E131D"))
                setStroke(dp(1), Color.parseColor("#1E293B"))
            }
        }
        card.addView(instructions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        val help = TextView(context).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#FFB74D"))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        card.addView(help, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val retry = createTvButton(context, "立即重试连接", primary = true, onClick = onRetryClicked).apply {
            visibility = View.GONE
        }
        actionsRow.addView(retry, LinearLayout.LayoutParams(dp(220), dp(54)).apply { marginEnd = dp(14) })

        val recovery = createTvButton(context, "切换无线网络模式", primary = false, onClick = onWifiRecoveryClicked).apply {
            visibility = View.GONE
        }
        actionsRow.addView(recovery, LinearLayout.LayoutParams(dp(220), dp(54)).apply { marginEnd = dp(14) })

        val back = createTvButton(context, "返回 TVPlay 工作台", primary = false, onClick = onBackToWorkbenchClicked)
        actionsRow.addView(back, LinearLayout.LayoutParams(dp(240), dp(54)))
        card.addView(actionsRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        val gestureHint = TextView(context).apply {
            text = "遥控器快捷键：方向键移动焦点 · 确认键选择 · 菜单键 / 长按返回键呼出快捷控制中心"
            textSize = 13f
            setTextColor(Color.parseColor("#78909C"))
            gravity = Gravity.CENTER
        }
        card.addView(gestureHint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        viewport.addView(
            card,
            FrameLayout.LayoutParams(dp(760), -2, Gravity.CENTER),
        )

        return ProjectionWaitingStageBindings(
            viewport = viewport,
            stageStatusView = stage,
            connectionInstructionsView = instructions,
            connectionHelpView = help,
            startupRetryButton = retry,
            wifiRecoveryButton = recovery,
            settingsGestureHint = gestureHint,
        )
    }

    fun createTvButton(
        context: Context,
        label: String,
        primary: Boolean = false,
        danger: Boolean = false,
        onClick: () -> Unit,
    ): TextView {
        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
        return TextView(context).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(20), dp(12), dp(20), dp(12))

            fun applyVisual(focused: Boolean) {
                val fill = when {
                    danger && focused -> Color.parseColor("#EF4444")
                    danger -> Color.parseColor("#2D161B")
                    primary && focused -> Color.parseColor("#2563EB")
                    primary -> Color.parseColor("#1D4ED8")
                    focused -> Color.parseColor("#1E293B")
                    else -> Color.parseColor("#172033")
                }
                val stroke = when {
                    focused -> Color.parseColor("#60A5FA")
                    danger -> Color.parseColor("#7F1D1D")
                    primary -> Color.parseColor("#3B82F6")
                    else -> Color.parseColor("#2B3954")
                }
                setTextColor(
                    when {
                        danger && !focused -> Color.parseColor("#FCA5A5")
                        else -> Color.WHITE
                    },
                )
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(fill)
                    setStroke(dp(if (focused) 3 else 1), stroke)
                }
                scaleX = if (focused) 1.03f else 1.0f
                scaleY = if (focused) 1.03f else 1.0f
            }

            applyVisual(false)
            setOnFocusChangeListener { _, hasFocus -> applyVisual(hasFocus) }
            setOnClickListener { onClick() }
        }
    }

    fun connectionInstructions(
        context: Context,
        status: CarPlayStatus?,
        wirelessEnabled: Boolean,
        wirelessHotspotMode: WirelessHotspotMode,
        existingWifiSsid: String,
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
            wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI -> {
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
        ConnectionFailureCause.entries.any { message == context.getString(ConnectionFailureGuidance.stage(it)) } -> message
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
        wirelessHotspotMode: WirelessHotspotMode,
    ): String = when (status) {
        CarPlayStatus.DiscoveringMfi -> res.getString(R.string.preparing_mfi_authentication)
        CarPlayStatus.WaitingForMfi -> res.getString(R.string.waiting_for_mfi_coprocessor)
        CarPlayStatus.RequestingMfiPermission -> res.getString(R.string.requesting_mfi_usb_permission)
        CarPlayStatus.MfiReady -> res.getString(R.string.mfi_authentication_ready)
        CarPlayStatus.StartingHotspot -> res.getString(
            if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) R.string.existing_wifi_attaching
            else R.string.starting_wireless_hotspot,
        )
        is CarPlayStatus.HotspotReady ->
            if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
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
}
