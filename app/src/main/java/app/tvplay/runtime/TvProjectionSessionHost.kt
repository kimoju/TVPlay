package app.tvplay.runtime

import android.content.Context
import android.content.res.Resources
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import app.tvplay.R
import app.tvplay.config.MfiAssetInstaller
import app.tvplay.config.TvPlayBluetooth
import app.tvplay.config.TvPreferencesStore
import app.tvplay.engine.auth.AccessoryIdentity
import app.tvplay.engine.av.AndroidMediaSink
import app.tvplay.engine.av.TvDisplaySpec
import app.tvplay.engine.network.BluetoothLinkCheck
import app.tvplay.engine.network.BluetoothRadioCheck
import app.tvplay.engine.network.IphoneUsbMatcher
import app.tvplay.engine.network.WirelessStartupFailure
import app.tvplay.engine.session.CarPlayController
import app.tvplay.engine.session.CarPlayStatus
import app.tvplay.engine.session.TvFailureReason
import app.tvplay.engine.session.TvSessionConfig
import app.tvplay.engine.session.TvSessionState
import app.tvplay.engine.session.TvTransportMode
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.engine.session.UsbDeviceFilter
import app.tvplay.ui.stage.CarPlaySessionDisplay
import app.tvplay.ui.stage.StageWaitingOverlay

typealias ProjectionSessionManager = TvProjectionSessionHost

data class ProjectionCanvasSupport(
    val supported: Boolean,
    val reason: String,
    val details: String,
)

/**
 * Process-local hand-off for keeping the active CarPlay projection session alive while the
 * projection stage activity is backgrounded or recreated.
 */
object CarPlayBackgroundSession {
    @Volatile
    var active = false

    private var stopAction: (((() -> Unit)) -> Unit)? = null
    private var stopping = false
    private var owner: Any? = null

    @Synchronized
    fun isOwner(candidate: Any): Boolean = owner === candidate

    @Synchronized
    fun hasSession(): Boolean = stopAction != null || stopping

    @Synchronized
    fun currentController(): CarPlayController? = controller

    private val stopWaiters = mutableListOf<() -> Unit>()

    fun stop(completion: () -> Unit = {}) {
        val action: (((() -> Unit)) -> Unit)?
        synchronized(this) {
            if (stopping) {
                stopWaiters.add(completion)
                return
            }
            action = stopAction
            if (action != null) {
                stopping = true
                stopWaiters.add(completion)
            }
        }
        if (action == null) {
            completion()
            return
        }
        action.invoke {
            val callbacks = synchronized(this) {
                stopping = false
                stopWaiters.toList().also { stopWaiters.clear() }
            }
            callbacks.forEach { it() }
        }
    }

    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
        val display: CarPlaySessionDisplay,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0
    private var display: CarPlaySessionDisplay? = null

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        val currentDisplay = display ?: return null
        return Snapshot(currentController, currentSink, width, height, currentDisplay)
    }

    @Synchronized
    fun store(
        controller: CarPlayController,
        sink: AndroidMediaSink,
        width: Int,
        height: Int,
        owner: Any,
        display: CarPlaySessionDisplay,
        stop: (() -> Unit) -> Unit,
    ) {
        this.stopAction = stop
        this.owner = owner
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
        this.display = display
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null, keepOwner: Boolean = false) {
        if (expected != null && controller !== expected) return
        controller = null
        sink = null
        if (!keepOwner) {
            stopAction = null
            owner = null
        }
        active = false
        width = 0
        height = 0
        display = null
    }
}

/**
 * Coordinates CarPlay projection session configuration, decoder canvas capability evaluation,
 * reconnect backoff scheduling, wireless startup retry budgeting, and user-facing status formatting.
 */
object TvProjectionSessionHost {
    const val RECONNECT_DELAY_MILLIS = 2_000L
    const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L

    private val DEFAULT_IPHONE_USB_FILTERS: List<UsbDeviceFilter> = emptyList()

    fun createRuntimeConfig(
        context: Context,
        airPlayIdentity: AccessoryIdentity = TvPreferencesStore.loadIdentity(context),
        manufacturer: String = TvPreferencesStore.loadManufacturer(context),
        model: String = TvPreferencesStore.loadModel(context),
        wirelessEnabled: Boolean = TvPreferencesStore.loadWirelessEnabled(context),
        wirelessHotspotMode: TvWirelessMode = TvPreferencesStore.loadWirelessMode(context),
        existingWifiSsid: String = TvPreferencesStore.loadLanSsid(context),
        existingWifiPassphrase: String = TvPreferencesStore.loadLanPassphrase(context),
        existingWifiInterface: String = TvPreferencesStore.loadLanInterface(context),
    ): TvSessionConfig = MfiAssetInstaller.buildSessionConfig(
        context = context,
        identity = airPlayIdentity,
        iphoneDevices = DEFAULT_IPHONE_USB_FILTERS,
        manufacturer = manufacturer,
        model = model,
        transport = if (wirelessEnabled) TvTransportMode.WIRELESS else TvTransportMode.WIRED,
        wirelessMode = wirelessHotspotMode,
        wirelessBluetoothAddress = TvPreferencesStore.loadPairedPhoneAddress(context),
        wifiDirectPreferredChannel = TvPreferencesStore.loadWifiDirectChannel(context),
        lanWifiSsid = existingWifiSsid,
        lanWifiPassphrase = existingWifiPassphrase,
        lanNetworkInterface = existingWifiInterface.trim().takeIf { it.isNotEmpty() },
    )

    fun evaluateLargerCanvasSupport(
        display: TvDisplaySpec,
        sessionHevc: Boolean,
        hevcSoftwareDecoderEnabled: Boolean,
    ): ProjectionCanvasSupport =
        if (maxOf(display.widthPixels, display.heightPixels) > 3840 ||
            minOf(display.widthPixels, display.heightPixels) > 2160
        ) {
            ProjectionCanvasSupport(
                false,
                "canvas_4k_limit",
                "Decoder capability check skipped: canvas exceeds enlargement limit",
            )
        } else {
            evaluateDecoderCanvasSupport(display, sessionHevc, hevcSoftwareDecoderEnabled)
        }

    fun evaluateDecoderCanvasSupport(
        display: TvDisplaySpec,
        sessionHevc: Boolean,
        hevcSoftwareDecoderEnabled: Boolean,
    ): ProjectionCanvasSupport = try {
        val mime = if (sessionHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
        }
        if (decoder == null) {
            ProjectionCanvasSupport(false, "no_decoder", "Decoder capability mime=$mime result=no_decoder")
        } else {
            val hardware = if (Build.VERSION.SDK_INT >= 29) {
                decoder.isHardwareAccelerated
            } else {
                !decoder.name.startsWith("OMX.google.") && !decoder.name.startsWith("c2.android.")
            }
            val video = decoder.getCapabilitiesForType(mime).videoCapabilities
            val sizeSupported = video?.isSizeSupported(display.widthPixels, display.heightPixels) == true
            val rateSupported = sizeSupported && video?.areSizeAndRateSupported(
                display.widthPixels,
                display.heightPixels,
                display.fps.toDouble(),
            ) == true
            val reason = when {
                !hardware -> "software_decoder"
                sessionHevc && hevcSoftwareDecoderEnabled -> "software_hevc_selected"
                video == null -> "no_video_capabilities"
                !sizeSupported -> "canvas_dimensions_unsupported"
                !rateSupported -> "frame_rate_unsupported"
                else -> "supported"
            }
            ProjectionCanvasSupport(
                reason == "supported",
                reason,
                "Decoder capability codec=${decoder.name} mime=$mime hardware=$hardware " +
                    "sizeSupported=$sizeSupported rateSupported=$rateSupported result=$reason",
            )
        }
    } catch (error: Exception) {
        ProjectionCanvasSupport(
            false,
            "capability_query_${error.javaClass.simpleName}",
            "Decoder capability query failed error=${error.javaClass.simpleName}",
        )
    }

    fun computeReconnectDelayMillis(
        startupDelay: Long?,
        reason: String,
        reconnectAttempts: Int,
    ): Long = when {
        startupDelay != null -> startupDelay
        reason.contains("AirPlay iAP tunnel", ignoreCase = true) -> IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        else -> (RECONNECT_DELAY_MILLIS * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
    }

    fun nextWirelessStartupDelayMillis(
        wireless: Boolean,
        status: CarPlayStatus,
        retryBudget: WirelessStartupRetryBudget,
        startupFailure: WirelessStartupFailure? = null,
        wifiResetRequired: Boolean = false,
    ): Long? {
        if (!wireless || status !is CarPlayStatus.Failed) return null
        val effectiveReset = wifiResetRequired || status.wifiResetRequired
        val effectiveStartup = startupFailure ?: status.startupFailure
        if (effectiveReset || status.knownCause != null) return null
        if (effectiveStartup != null && !effectiveStartup.retryable) return null
        return retryBudget.nextDelayMillis()
    }

    fun describeStatus(
        status: CarPlayStatus,
        res: Resources,
        wirelessEnabled: Boolean,
        wirelessHotspotMode: TvWirelessMode,
    ): String = StageWaitingOverlay.describeStatus(
        status = status,
        res = res,
        wirelessEnabled = wirelessEnabled,
        wirelessHotspotMode = wirelessHotspotMode,
    )

    fun describeSessionState(
        state: TvSessionState,
        res: Resources,
        wirelessEnabled: Boolean,
        wirelessHotspotMode: TvWirelessMode,
    ): String = when (state) {
        TvSessionState.Idle -> res.getString(R.string.waiting_for_iphone)
        is TvSessionState.Searching -> state.detail.ifBlank {
            if (wirelessEnabled) res.getString(R.string.waiting_for_paired_iphone)
            else res.getString(R.string.waiting_for_iphone_over_usb)
        }
        is TvSessionState.Starting -> state.detail.ifBlank {
            if (wirelessEnabled) {
                if (wirelessHotspotMode == TvWirelessMode.LAN_WIFI) {
                    res.getString(R.string.existing_wifi_attaching)
                } else {
                    res.getString(R.string.starting_wireless_hotspot)
                }
            } else {
                res.getString(R.string.connecting_iap2_control)
            }
        }
        is TvSessionState.Authenticated -> state.detail.ifBlank {
            res.getString(R.string.mfi_authentication_ready)
        }
        is TvSessionState.Streaming -> state.detail.ifBlank {
            if (wirelessEnabled) res.getString(R.string.wireless_carplay_active)
            else res.getString(R.string.carplay_control_running)
        }
        is TvSessionState.Failed -> when {
            state.cause != null -> res.getString(ConnectionFailureGuidance.stage(state.cause!!))
            state.detail.isNotBlank() -> res.getString(R.string.status_failed, state.detail)
            else -> res.getString(R.string.status_failed, state.step)
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

    fun connectionInstructions(
        context: Context,
        status: CarPlayStatus?,
        wirelessEnabled: Boolean,
        wirelessHotspotMode: TvWirelessMode,
        existingWifiSsid: String,
    ): String = StageWaitingOverlay.connectionInstructions(
        context = context,
        status = status,
        wirelessEnabled = wirelessEnabled,
        wirelessHotspotMode = wirelessHotspotMode,
        existingWifiSsid = existingWifiSsid,
    )
}
