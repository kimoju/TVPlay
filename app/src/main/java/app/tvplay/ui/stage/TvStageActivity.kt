package app.tvplay.ui.stage

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.tvplay.R
import app.tvplay.config.MfiAssetInstaller
import app.tvplay.config.TvDeviceCapabilities
import app.tvplay.config.TvPlayBluetooth
import app.tvplay.config.TvPreferencesStore
import app.tvplay.engine.session.isNonPhoneBluetoothAccessory
import java.util.concurrent.Executors
import app.tvplay.config.VideoSurfaceType
import app.tvplay.config.WaitingScreenColorPreset
import app.tvplay.config.WaitingScreenColors
import app.tvplay.config.WiredPermissions
import app.tvplay.config.WirelessPermissions
import app.tvplay.engine.av.CarPlayPhysicalSizePreset
import app.tvplay.engine.av.DisplayMetricMath
import app.tvplay.engine.av.DisplaySafeAreaMath
import app.tvplay.engine.av.DisplayScaleMath
import app.tvplay.engine.av.PhysicalSizeBasis
import app.tvplay.engine.av.TvAvRenderPipeline
import app.tvplay.engine.av.TvDisplaySpec
import app.tvplay.engine.av.UiDensityScale
import app.tvplay.engine.av.ViewportTouchMapper
import app.tvplay.engine.av.ViewportVideoLayout
import app.tvplay.engine.network.LanNetworkLink
import app.tvplay.engine.network.UsbTunnelVpnService
import app.tvplay.engine.network.WirelessStartupFailure
import app.tvplay.engine.protocol.iap2.Iap2ArtworkTransfer
import app.tvplay.engine.protocol.rtsp.AirPlayDeviceInfo
import app.tvplay.engine.protocol.rtsp.AirPlayKnobState
import app.tvplay.engine.protocol.rtsp.AirPlaySession
import app.tvplay.engine.protocol.rtsp.AirPlaySessionListener
import app.tvplay.engine.protocol.rtsp.CarPlayMediaEngine
import app.tvplay.engine.session.CarPlayStatus
import app.tvplay.engine.session.TvNowPlayingMetadata
import app.tvplay.engine.session.TvSessionConfig
import app.tvplay.engine.session.TvSessionEngine
import app.tvplay.engine.session.TvTransportMode
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.remote.CarPlayNightMode
import app.tvplay.remote.CarPlayNightModeController
import app.tvplay.remote.CarPlaySurfaceBounds
import app.tvplay.remote.CarPlayVideoSurfaceMode
import app.tvplay.remote.TvRemoteInputController
import app.tvplay.remote.TvRemoteKeyMap
import app.tvplay.remote.TvVideoColorFilter
import app.tvplay.remote.carPlayVideoSurfaceMode
import app.tvplay.runtime.CarPlayBackgroundSession
import app.tvplay.runtime.LastConnectionProblem
import app.tvplay.runtime.PendingReconnect
import app.tvplay.runtime.ProjectionCanvasSupport
import app.tvplay.runtime.TvDiagnosticsLogger
import app.tvplay.runtime.TvMediaKeyRouter
import app.tvplay.runtime.TvProjectionSessionHost
import app.tvplay.runtime.TvSessionForegroundService
import app.tvplay.ui.theme.appDialogBuilder
import app.tvplay.runtime.WirelessStartupRetryBudget
import app.tvplay.ui.theme.AppAppearance
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.ui.workbench.TvWorkbenchActivity

typealias TvPlayProjectionActivity = TvStageActivity

/**
 * Fullscreen hardware-accelerated CarPlay stage activity for Android TV.
 *
 * Delegates session orchestration to [TvProjectionSessionHost] & [TvSessionEngine],
 * video surface layout to [TvVideoSurfaceHost], remote D-pad/Menu/Back keys to
 * [TvRemoteInputController], and overlay UI to [StageWaitingOverlay],
 * [StageQuickControlOverlay], [StagePictureTuningOverlay], and [StageFpsHudOverlay].
 */
class TvStageActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var rootFrame: FrameLayout
    private lateinit var videoContainer: FrameLayout
    private var videoView: View? = null
    private var fallbackSurfaceView: SurfaceView? = null
    private var fallbackBounds: CarPlaySurfaceBounds? = null
    private var pictureBinding: TvVideoColorFilter.Binding? = null
    private var activeSurface: Surface? = null

    private lateinit var waitingBindings: ProjectionWaitingStageBindings
    private var quickControlOverlay: View? = null
    private var pictureTuningOverlay: StagePictureTuningOverlay? = null
    private lateinit var fpsHudOverlay: StageFpsHudOverlay

    private var sessionEngine: TvSessionEngine? = null
    private var avPipeline: TvAvRenderPipeline? = null
    private var activeAirPlaySession: AirPlaySession? = null
    private var sessionDisplay: CarPlaySessionDisplay? = null
    private val nightModeController = CarPlayNightModeController(initialNight = true) { night ->
        sessionEngine?.setNightMode(night)
    }
    private val retryBudget = WirelessStartupRetryBudget()
    private val legacySiriPresses = mutableSetOf<Triple<Int, Int, Int>>()
    private var suppressBackReleaseAfterLongPress = false

    private var streamingActive = false
    private var firstFrameRendered = false
    private val isProjectionVisible: Boolean
        get() = streamingActive && firstFrameRendered
    private val hasLiveAirPlaySession: Boolean
        get() = activeAirPlaySession?.isClosed == false
    private var sessionHevc = false
    private var vpnPromptedThisSession = false
    private var permissionsPromptedThisSession = false
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttempts = 0
    private var lastStatus: CarPlayStatus? = null
    @Volatile
    private var restartGeneration = 0
    @Volatile
    private var teardownInProgress = false
    @Volatile
    private var pendingStartAfterTeardown = false
    private val retiringPipelines = java.util.concurrent.CopyOnWriteArraySet<TvAvRenderPipeline>()
    private val teardownExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tvplay-stage-teardown").apply { isDaemon = true }
    }
    private var bluetoothReceiverRegistered = false
    private var activeDialog: android.app.AlertDialog? = null
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isFinishing || isDestroyed || isProjectionVisible || streamingActive || activeAirPlaySession != null) return
            if (!TvPreferencesStore.loadWirelessEnabled(this@TvStageActivity)) return
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                        return
                    }
                    reconnectAttempts = 0
                    cancelScheduledReconnect()
                    reconnectNow()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (bondState != BluetoothDevice.BOND_BONDED) return
                    handleBluetoothPeerReady(extractBluetoothDevice(intent))
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    handleBluetoothPeerReady(extractBluetoothDevice(intent))
                }
            }
        }
    }

    private fun extractBluetoothDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothPeerReady(device: BluetoothDevice?) {
        if (device != null) {
            val rawName = runCatching { device.name }.getOrNull()
            val majorClass = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
            if (isNonPhoneBluetoothAccessory(rawName, majorClass)) return
            val address = runCatching { device.address }.getOrNull()?.takeIf { it.isNotBlank() }
            TvPlayBluetooth.sanitizeSavedPhone(this)
            if (address != null && TvPreferencesStore.loadPairedPhoneAddress(this) == null) {
                TvPreferencesStore.savePairedPhone(this, address, rawName?.ifBlank { "我的 iPhone" } ?: "我的 iPhone")
            }
        }
        reconnectAttempts = 0
        val engine = sessionEngine
        if (engine != null && !engine.isClosed()) {
            engine.notifyBluetoothPeerChanged()
            if (lastStatus is CarPlayStatus.Failed || lastStatus is CarPlayStatus.ControlEnded) {
                cancelScheduledReconnect()
                reconnectNow()
            }
            return
        }
        cancelScheduledReconnect()
        reconnectNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLaunchIntentMode(intent)
        CarPlayScreen.forwarded(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching {
            registerReceiver(
                bluetoothStateReceiver,
                IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                },
            )
            bluetoothReceiverRegistered = true
        }

        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = false
        }
        videoContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        rootFrame.addView(
            videoContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        setupVideoSurface()
        setupWaitingOverlay()
        setupFpsHudOverlay()

        setContentView(rootFrame)
        applyImmersiveFullscreen()
        rootFrame.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            if (w != oldRight - oldLeft || h != oldBottom - oldTop) {
                updateVideoLayout(w, h)
            }
        }

        startOrAttachSession()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val previousWireless = TvPreferencesStore.loadWirelessEnabled(this)
        applyLaunchIntentMode(intent)
        val currentWireless = TvPreferencesStore.loadWirelessEnabled(this)
        if (
            (!currentWireless && isIphoneUsbAttachment(intent)) ||
            previousWireless != currentWireless ||
            PendingReconnect.isPending()
        ) {
            reconnectNow()
        }
    }

    private fun applyLaunchIntentMode(launchIntent: Intent?) {
        if (launchIntent == null) return
        if (launchIntent.hasExtra("wireless")) {
            val wireless = launchIntent.getBooleanExtra("wireless", TvPreferencesStore.loadWirelessEnabled(this))
            launchIntent.removeExtra("wireless")
            TvPreferencesStore.saveWirelessEnabled(this, wireless)
        }
    }

    private fun isIphoneUsbAttachment(launchIntent: Intent?): Boolean {
        if (launchIntent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launchIntent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            launchIntent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        return device == null || device.vendorId == 0x05AC
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
        if (streamingActive) {
            activeSurface?.let { surface ->
                avPipeline?.setMainSurface(surface, videoContainer.width, videoContainer.height)
                avPipeline?.refreshPicture()
            }
        }
        refreshOverlayVisibility()
        if (TvPreferencesStore.loadFpsCounter(this) && streamingActive) {
            fpsHudOverlay.start()
        } else {
            fpsHudOverlay.stop()
        }
        if (PendingReconnect.isPending()) {
            waitingBindings.reconnectBannerView.visibility = View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        fpsHudOverlay.stop()
    }

    override fun onDestroy() {
        cancelScheduledReconnect()
        runCatching { activeDialog?.dismiss() }
        activeDialog = null
        if (bluetoothReceiverRegistered) {
            runCatching { unregisterReceiver(bluetoothStateReceiver) }
            bluetoothReceiverRegistered = false
        }
        fpsHudOverlay.stop()
        pictureBinding?.close()
        pictureBinding = null
        activeSurface = null
        CarPlayScreen.destroyed(this)
        if (isFinishing && !isProjectionVisible) {
            stopActiveSession()
        }
        super.onDestroy()
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (quickControlOverlay?.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                event.keyCode == KeyEvent.KEYCODE_MENU
            ) {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (suppressBackReleaseAfterLongPress &&
                        (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_BUTTON_B)
                    ) {
                        suppressBackReleaseAfterLongPress = false
                    } else {
                        hideQuickControlOverlay()
                    }
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (pictureTuningOverlay?.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    closePictureTuningOverlay()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        val waitingVisible = waitingBindings.stageOverlay.visibility == View.VISIBLE
        if (waitingVisible && !isProjectionVisible) {
            if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                showQuickControlOverlay()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                returnToWorkbench()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        val handled = TvRemoteInputController.dispatchKeyEvent(
            context = this,
            event = event,
            legacySiriPresses = legacySiriPresses,
            menuOpen = quickControlOverlay?.visibility == View.VISIBLE || pictureTuningOverlay?.visibility == View.VISIBLE,
            suppressBackReleaseAfterLongPress = suppressBackReleaseAfterLongPress,
            setSuppressBackRelease = { suppressBackReleaseAfterLongPress = it },
            hasActiveScreenStream = isProjectionVisible,
            sessionActive = isProjectionVisible,
            sendKnob = { state, momentary ->
                if (sessionEngine?.sendKnob(state, momentary) == true) {
                    true
                } else {
                    activeAirPlaySession?.let {
                        it.sendKnob(state, momentary)
                        true
                    } ?: false
                }
            },
            sendMediaButton = { button ->
                if (sessionEngine?.sendMedia(button) == true) {
                    true
                } else {
                    activeAirPlaySession?.let {
                        it.sendMedia(button)
                        true
                    } ?: false
                }
            },
            onToggleQuickMenu = ::toggleQuickControlOverlay,
            onOpenQuickMenu = ::showQuickControlOverlay,
            onRequestSiri = {
                if (sessionEngine?.invokeSiri() != true) {
                    activeAirPlaySession?.invokeSiri()
                }
            },
            onLog = TvDiagnosticsLogger::record,
        )
        if (handled == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isProjectionVisible && quickControlOverlay?.visibility != View.VISIBLE) {
            val viewW = videoContainer.width.coerceAtLeast(1)
            val viewH = videoContainer.height.coerceAtLeast(1)
            val layout = TvVideoSurfaceHost.computeContentRect(sessionDisplay, viewW, viewH)
            val contacts = ViewportTouchMapper.contacts(event, layout)
            if (sessionEngine?.sendTouch(contacts) != true) {
                activeAirPlaySession?.sendTouch(contacts)
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun setupVideoSurface() {
        pictureBinding?.close()
        pictureBinding = null
        videoContainer.removeAllViews()

        val preferred = TvPreferencesStore.loadVideoSurfaceType(this)
        val hevc = TvPreferencesStore.loadHevcEnabled(this)
        val unsafeDecoder = TvVideoSurfaceHost.findTextureUnsafeVideoDecoder(hevc)
        val isHwAccelerated = window?.decorView?.takeIf { it.isAttachedToWindow }?.isHardwareAccelerated
            ?: (((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) != 0 ||
                (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_HARDWARE_ACCELERATED) != 0)
        val mode = carPlayVideoSurfaceMode(
            hardwareAccelerated = isHwAccelerated,
            smoothVideo = TvPreferencesStore.loadSmoothVideo(this) && preferred != VideoSurfaceType.TEXTURE_VIEW,
            directVideoOutput = preferred == VideoSurfaceType.SURFACE_VIEW,
            textureUnsafeDecoder = unsafeDecoder != null,
        )

        if (mode == CarPlayVideoSurfaceMode.SURFACE) {
            val surfaceView = SurfaceView(this)
            surfaceView.holder.addCallback(
                object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        activeSurface = holder.surface
                        avPipeline?.setMainSurface(holder.surface, surfaceView.width, surfaceView.height)
                        avPipeline?.refreshPicture()
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        activeSurface = holder.surface
                        avPipeline?.setMainSurface(holder.surface, width, height)
                        updateVideoLayout(videoContainer.width, videoContainer.height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        val surface = holder.surface
                        val live = avPipeline
                        val detaches = (listOfNotNull(live) + retiringPipelines).distinct()
                            .map { owner -> owner.beginSurfaceDetach(surface, parkMain = !isFinishing) }
                        detaches.forEach { it.await() }
                        live?.clearSurface(110, surface)
                        activeSurface = null
                    }
                },
            )
            fallbackSurfaceView = surfaceView
            videoView = surfaceView
            videoContainer.addView(
                surfaceView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
        } else {
            val textureView = TextureView(this)
            textureView.isOpaque = true
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    val surface = Surface(texture)
                    activeSurface = surface
                    avPipeline?.setMainSurface(surface, width, height)
                    avPipeline?.refreshPicture()
                    updateVideoLayout(videoContainer.width, videoContainer.height)
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    activeSurface?.let { avPipeline?.setMainSurface(it, width, height) }
                    updateVideoLayout(videoContainer.width, videoContainer.height)
                }

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    activeSurface?.let { surface ->
                        val live = avPipeline
                        val detaches = (listOfNotNull(live) + retiringPipelines).distinct()
                            .map { owner -> owner.beginSurfaceDetach(surface, parkMain = !isFinishing) }
                        detaches.forEach { it.await() }
                        live?.clearSurface(110, surface)
                        surface.release()
                    }
                    activeSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
            pictureBinding = TvVideoColorFilter.Binding(textureView)
            fallbackSurfaceView = null
            videoView = textureView
            videoContainer.addView(
                textureView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    private fun setupWaitingOverlay() {
        val waitingColors = TvPreferencesStore.loadWaitingScreenColors(this)
        val wirelessEnabled = TvPreferencesStore.loadWirelessEnabled(this)
        val wirelessMode = TvPreferencesStore.loadWirelessHotspotMode(this)
        var lanSsid = TvPreferencesStore.loadExistingWifiSsid(this)
        if (wirelessEnabled && wirelessMode == TvWirelessMode.LAN_WIFI && lanSsid.isBlank()) {
            val detected = LanNetworkLink.detectConnectedWifiSsid(this)
            if (detected != null) {
                lanSsid = detected
                TvPreferencesStore.saveExistingWifiCredentials(
                    this,
                    detected,
                    TvPreferencesStore.loadExistingWifiPassphrase(this),
                )
            }
        }

        waitingBindings = StageWaitingOverlay.build(
            context = this,
            waitingColors = waitingColors,
            wirelessEnabled = wirelessEnabled,
            wirelessHotspotMode = wirelessMode,
            existingWifiSsid = lanSsid,
            onReconnectClicked = ::reconnectNow,
            onSettingsClicked = ::returnToWorkbench,
            onDisconnectClicked = ::disconnectAndExit,
            onBluetoothSettingsClicked = ::openBluetoothSettings,
            onSwitchModeClicked = ::openConnectionModePicker,
        )
        rootFrame.addView(
            waitingBindings.stageOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun setupFpsHudOverlay() {
        fpsHudOverlay = StageFpsHudOverlay(
            context = this,
            handler = mainHandler,
            counters = { avPipeline?.liveVideoCounters() },
        )
        val density = resources.displayMetrics.density
        val margin = (20 * density).toInt()
        rootFrame.addView(
            fpsHudOverlay.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = margin
                rightMargin = margin
            },
        )
    }

    private fun startOrAttachSession(adoptExisting: Boolean = true) {
        if (teardownInProgress) {
            pendingStartAfterTeardown = true
            return
        }
        val shouldAdopt = adoptExisting &&
            !PendingReconnect.isPending() &&
            sessionEngine == null &&
            CarPlayBackgroundSession.hasSession()
        PendingReconnect.clear()
        waitingBindings.reconnectBannerView.visibility = View.GONE
        if (shouldAdopt && adoptBackgroundSession()) {
            return
        }
        if (sessionEngine != null || avPipeline != null || CarPlayBackgroundSession.hasSession()) {
            reconnectNow()
            return
        }
        lastStatus = null
        val controllerGeneration = ++restartGeneration
        val wirelessEnabled = TvPreferencesStore.loadWirelessEnabled(this)
        val wirelessMode = TvPreferencesStore.loadWirelessHotspotMode(this)
        var lanSsid = TvPreferencesStore.loadExistingWifiSsid(this)
        if (wirelessEnabled && wirelessMode == TvWirelessMode.LAN_WIFI && lanSsid.isBlank()) {
            val detected = LanNetworkLink.detectConnectedWifiSsid(this)
            if (detected != null) {
                lanSsid = detected
                TvPreferencesStore.saveExistingWifiCredentials(
                    this,
                    detected,
                    TvPreferencesStore.loadExistingWifiPassphrase(this),
                )
            }
        }
        waitingBindings.connectionInstructionsView.text = StageWaitingOverlay.connectionInstructions(
            context = this,
            status = null,
            wirelessEnabled = wirelessEnabled,
            wirelessHotspotMode = wirelessMode,
            existingWifiSsid = lanSsid,
        )
        waitingBindings.connectionHelpView.visibility = View.GONE
        if (wirelessEnabled) {
            TvPlayBluetooth.sanitizeSavedPhone(this)
        }

        if (!permissionsPromptedThisSession) {
            val baseMissing = if (wirelessEnabled) {
                WirelessPermissions.missing(this, wirelessMode)
            } else {
                WiredPermissions.missing(this)
            }
            val micMissing = if (
                TvPreferencesStore.loadMicrophoneEnabled(this) &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                listOf(android.Manifest.permission.RECORD_AUDIO)
            } else {
                emptyList()
            }
            val missingPermissions = (baseMissing + micMissing).distinct()
            if (missingPermissions.isNotEmpty()) {
                permissionsPromptedThisSession = true
                runCatching {
                    requestPermissions(missingPermissions.toTypedArray(), REQUEST_STAGE_PERMISSIONS)
                }
            }
        }
        if (!wirelessEnabled && !vpnPromptedThisSession) {
            val vpnIntent = runCatching { android.net.VpnService.prepare(this) }.getOrNull()
            if (vpnIntent != null) {
                vpnPromptedThisSession = true
                val requested = runCatching {
                    @Suppress("DEPRECATION")
                    startActivityForResult(vpnIntent, REQUEST_STAGE_VPN_PERMISSION)
                    true
                }.getOrDefault(false)
                if (requested) return
            }
        }

        runCatching { MfiAssetInstaller.ensure(this) }
            .onFailure { error ->
                TvDiagnosticsLogger.record("MFi asset bootstrap note: ${error.message}")
            }

        val identity = TvPreferencesStore.loadIdentity(this)
        val pairings = TvPreferencesStore.loadPairings(this) { id, key ->
            TvPreferencesStore.savePairing(this, id, key)
        }
        val fps = TvPreferencesStore.loadFps(this)
        val hevc = TvPreferencesStore.loadHevcEnabled(this)
        sessionHevc = hevc
        val videoSync = TvPreferencesStore.loadVideoSyncEnabled(this)
        val disableAudio = TvPreferencesStore.loadDisableAudioOutput(this)
        val micEnabled = TvPreferencesStore.loadMicrophoneEnabled(this) &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val bufferedAudio = TvPreferencesStore.loadMainBufferedAudio(this)

        val metrics = resources.displayMetrics
        val rawW = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1280)
        val rawH = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(720)
        val scalePercent = TvPreferencesStore.loadDisplayScalePercent(this)
        val uiScale = TvPreferencesStore.loadUiScalePercent(this)
        val widthMm = TvPreferencesStore.loadWidthPhysicalMm(this)
        val basis = TvPreferencesStore.loadPhysicalSizeBasis(this)
        val physicalMm = DisplayMetricMath.resolvePhysicalSizeMm(
            currentWidthPixels = rawW,
            currentHeightPixels = rawH,
            maximumWidthPixels = rawW,
            maximumHeightPixels = rawH,
            referenceMillimeters = widthMm,
            basis = basis,
        )

        var displaySpec = TvDisplaySpec(
            widthPixels = rawW,
            heightPixels = rawH,
            widthPhysicalMm = physicalMm.widthMm,
            heightPhysicalMm = physicalMm.heightMm,
            fps = fps,
            primaryInputDevice = if (TvDeviceCapabilities.shouldUseKnobAsPrimaryInput(this)) 3 else 1,
        )
        displaySpec = DisplayScaleMath.applyPercent(displaySpec, scalePercent)
        displaySpec = UiDensityScale.apply(displaySpec, uiScale)
        val disableSafeArea = TvPreferencesStore.loadMainDisplayDisableSafeArea(this)
        val safeAreaDrawOutside = TvPreferencesStore.loadSafeAreaDrawOutside(this)
        displaySpec = displaySpec.copy(
            safeArea = if (disableSafeArea) {
                null
            } else {
                DisplaySafeAreaMath.toInsets(
                    mapping = TvPreferencesStore.loadSafeAreaRect(this, rawW, rawH),
                    activityWidthPixels = rawW,
                    activityHeightPixels = rawH,
                    displayWidthPixels = displaySpec.widthPixels,
                    displayHeightPixels = displaySpec.heightPixels,
                )
            },
            safeAreaDrawOutside = safeAreaDrawOutside,
        )
        sessionDisplay = CarPlaySessionDisplay(
            width = displaySpec.widthPixels,
            height = displaySpec.heightPixels,
            rotation = 0,
            windowWidth = rawW,
            windowHeight = rawH,
        )

        val pipeline = TvAvRenderPipeline(
            context = applicationContext,
            videoWidth = displaySpec.widthPixels,
            videoHeight = displaySpec.heightPixels,
            preferSoftwareHevcDecoder = TvPreferencesStore.loadHevcSoftwareDecoderEnabled(this),
            audioFocusEnabled = TvPreferencesStore.loadAudioFocusEnabled(this),
            audioFocusAutoYield = TvPreferencesStore.loadAudioFocusAutoYield(this),
            mediaBufferMillis = TvPreferencesStore.loadMediaBufferMillis(this, wirelessEnabled),
            onAudioDiagnostic = TvDiagnosticsLogger::record,
            onScreenStreamActiveChanged = { streamType, active ->
                if (streamType == 110 && controllerGeneration == restartGeneration) {
                    mainHandler.post {
                        if (controllerGeneration != restartGeneration) return@post
                        streamingActive = active
                        refreshOverlayVisibility()
                    }
                }
            },
            videoPacingDelayMillis = if (videoSync && fallbackSurfaceView != null) 40 + 3_000 / fps.coerceIn(30, 60) else 0,
            mainVideoFrameRate = fps,
            vendorLowLatencyDecoder = TvPreferencesStore.loadLowLatencyDecoder(this),
            mtkDecoderTuningEnabled = TvPreferencesStore.loadMtkDecoderTuning(this),
        ).also { av ->
            av.setAudioOutputDisabled(disableAudio)
            av.setFirstVideoFrameListener {
                if (controllerGeneration == restartGeneration) {
                    mainHandler.post {
                        if (controllerGeneration != restartGeneration) return@post
                        reconnectAttempts = 0
                        cancelScheduledReconnect()
                        firstFrameRendered = true
                        streamingActive = true
                        activeAirPlaySession?.let { retryBudget.firstFrame(it, System.currentTimeMillis()) }
                        LastConnectionProblem.clear(this)
                        refreshOverlayVisibility()
                        if (TvPreferencesStore.loadFpsCounter(this)) {
                            fpsHudOverlay.start()
                        }
                    }
                }
            }
            av.setVideoStreamDimensionsListener { streamW, streamH ->
                if (controllerGeneration == restartGeneration) {
                    mainHandler.post {
                        if (controllerGeneration != restartGeneration) return@post
                        sessionDisplay = CarPlaySessionDisplay(
                            width = streamW,
                            height = streamH,
                            rotation = 0,
                            windowWidth = videoContainer.width.coerceAtLeast(rawW),
                            windowHeight = videoContainer.height.coerceAtLeast(rawH),
                        )
                        updateVideoLayout(videoContainer.width, videoContainer.height)
                    }
                }
            }
            activeSurface?.let { surface ->
                av.setMainSurface(surface, videoContainer.width, videoContainer.height)
            }
        }
        avPipeline = pipeline

        val mediaEngine = CarPlayMediaEngine(
            sink = pipeline,
            microphoneEnabled = micEnabled,
        )

        if (wirelessEnabled) {
            val bonded = TvPlayBluetooth.bondedDevices(this)
            TvPlayBluetooth.sanitizeSavedPhone(this, bonded)
            if (TvPreferencesStore.loadPairedPhoneAddress(this) == null) {
                val singleIphone = bonded.filter { it.isLikelyIphone }.singleOrNull()
                if (singleIphone != null) {
                    TvPreferencesStore.savePairedPhone(this, singleIphone.address, singleIphone.name)
                }
            }
        }

        val sessionConfig = MfiAssetInstaller.buildSessionConfig(
            context = this,
            identity = identity,
            transport = if (wirelessEnabled) TvTransportMode.WIRELESS else TvTransportMode.WIRED,
            wirelessMode = wirelessMode,
            wirelessBluetoothAddress = TvPreferencesStore.loadPairedPhoneAddress(this),
            wifiDirectPreferredChannel = TvPreferencesStore.loadWifiP2pPreferredChannel(this),
            lanWifiSsid = TvPreferencesStore.loadExistingWifiSsid(this),
            lanWifiPassphrase = TvPreferencesStore.loadExistingWifiPassphrase(this),
            lanNetworkInterface = TvPreferencesStore.loadExistingWifiInterface(this).trim().takeIf { it.isNotEmpty() },
        )
        val streamServerConfig = MfiAssetInstaller.buildStreamServerConfig(
            context = this,
            identity = identity,
            main = displaySpec,
            hevc = hevc,
            disableAudioOutput = disableAudio,
            microphone = micEnabled,
            mainBufferedAudio = bufferedAudio,
        )

        val sessionListener = createSessionListener(controllerGeneration, wirelessEnabled)

        val controller = TvSessionEngine(
            context = this,
            config = sessionConfig,
            airPlayConfig = streamServerConfig,
            identity = identity,
            pairings = pairings,
            listener = sessionListener,
            media = mediaEngine,
            reportStatus = { status -> onCarPlayStatusChanged(status, controllerGeneration) },
            loadPairRecord = { TvPreferencesStore.loadLockdownRecord(this) },
            savePairRecord = { record -> TvPreferencesStore.saveLockdownRecord(this, record) },
            clearPairRecord = { TvPreferencesStore.clearLockdownRecord(this) },
        )
        sessionEngine = controller
        val initialDisplay = sessionDisplay ?: CarPlaySessionDisplay(
            width = displaySpec.widthPixels,
            height = displaySpec.heightPixels,
            rotation = 0,
            windowWidth = rawW,
            windowHeight = rawH,
        )
        registerBackgroundSession(
            controller = controller,
            pipeline = pipeline,
            width = displaySpec.widthPixels,
            height = displaySpec.heightPixels,
            display = initialDisplay,
        )
        if (TvPreferencesStore.loadMediaKeyForwardingEnabled(this)) {
            TvMediaKeyRouter.attach(this, controller) { change ->
                if (change == android.media.AudioManager.AUDIOFOCUS_GAIN) {
                    pipeline.onMediaAudioFocusChanged(change)
                }
            }
        }
        runCatching {
            TvSessionForegroundService.startOrUpdate(
                this,
                wirelessEnabled,
                getString(R.string.waiting_for_iphone),
            )
        }
        controller.start()
    }

    private fun registerBackgroundSession(
        controller: TvSessionEngine,
        pipeline: TvAvRenderPipeline,
        width: Int,
        height: Int,
        display: CarPlaySessionDisplay,
    ) {
        val appContext = applicationContext
        val weakSelf = java.lang.ref.WeakReference(this)
        CarPlayBackgroundSession.store(
            controller = controller,
            sink = pipeline,
            width = width,
            height = height,
            owner = this,
            display = display,
        ) { done ->
            val stage = weakSelf.get()
            if (stage != null) {
                stage.stopActiveSession {
                    TvSessionForegroundService.stop(appContext)
                    done()
                }
            } else {
                CarPlayBackgroundSession.clear(controller)
                TvMediaKeyRouter.release(appContext)
                Thread({
                    runCatching {
                        controller.close()
                        controller.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
                    }
                    runCatching {
                        pipeline.close()
                        pipeline.awaitVideoReleased(SINK_RELEASE_WAIT_MILLIS)
                    }
                    mainHandler.post {
                        TvSessionForegroundService.stop(appContext)
                        done()
                    }
                }, "tvplay-bg-session-stop").apply { isDaemon = true }.start()
            }
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        val controllerGeneration = ++restartGeneration
        val wirelessEnabled = TvPreferencesStore.loadWirelessEnabled(this)
        sessionEngine = snapshot.controller
        avPipeline = snapshot.sink
        sessionDisplay = snapshot.display
        val liveSession = snapshot.controller.activeAirPlaySession()
        activeAirPlaySession = liveSession
        val hasStream = liveSession != null || CarPlayBackgroundSession.active
        streamingActive = hasStream
        firstFrameRendered = hasStream
        registerBackgroundSession(
            controller = snapshot.controller,
            pipeline = snapshot.sink,
            width = snapshot.width,
            height = snapshot.height,
            display = snapshot.display,
        )
        snapshot.controller.attachUi(
            createSessionListener(controllerGeneration, wirelessEnabled),
            { status -> onCarPlayStatusChanged(status, controllerGeneration) },
        )
        snapshot.sink.setScreenStreamActiveChangedListener { streamType, active ->
            if (streamType == 110 && controllerGeneration == restartGeneration) {
                mainHandler.post {
                    if (controllerGeneration != restartGeneration) return@post
                    streamingActive = active
                    refreshOverlayVisibility()
                }
            }
        }
        snapshot.sink.setFirstVideoFrameListener {
            if (controllerGeneration == restartGeneration) {
                mainHandler.post {
                    if (controllerGeneration != restartGeneration) return@post
                    reconnectAttempts = 0
                    cancelScheduledReconnect()
                    firstFrameRendered = true
                    streamingActive = true
                    activeAirPlaySession?.let { retryBudget.firstFrame(it, System.currentTimeMillis()) }
                    LastConnectionProblem.clear(this)
                    refreshOverlayVisibility()
                    if (TvPreferencesStore.loadFpsCounter(this)) {
                        fpsHudOverlay.start()
                    }
                }
            }
        }
        snapshot.sink.setVideoStreamDimensionsListener { streamW, streamH ->
            if (controllerGeneration == restartGeneration) {
                mainHandler.post {
                    if (controllerGeneration != restartGeneration) return@post
                    sessionDisplay = CarPlaySessionDisplay(
                        width = streamW,
                        height = streamH,
                        rotation = 0,
                        windowWidth = videoContainer.width.coerceAtLeast(snapshot.width),
                        windowHeight = videoContainer.height.coerceAtLeast(snapshot.height),
                    )
                    updateVideoLayout(videoContainer.width, videoContainer.height)
                }
            }
        }
        activeSurface?.let { surface ->
            snapshot.sink.setMainSurface(surface, videoContainer.width, videoContainer.height)
            snapshot.sink.refreshPicture()
        }
        updateVideoLayout(videoContainer.width, videoContainer.height)
        if (TvPreferencesStore.loadMediaKeyForwardingEnabled(this)) {
            TvMediaKeyRouter.attach(this, snapshot.controller) { change ->
                if (change == android.media.AudioManager.AUDIOFOCUS_GAIN) {
                    snapshot.sink.onMediaAudioFocusChanged(change)
                }
            }
        }
        refreshOverlayVisibility()
        return true
    }

    private fun createSessionListener(
        controllerGeneration: Int,
        wirelessEnabled: Boolean,
    ): AirPlaySessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            if (controllerGeneration != restartGeneration) return
            activeAirPlaySession = session
            CarPlayBackgroundSession.active = true
            val nightMode = resolveNightMode()
            if (sessionEngine?.setNightMode(nightMode) != true) {
                session.setNightMode(nightMode)
            }
            mainHandler.post {
                if (controllerGeneration != restartGeneration) return@post
                reconnectAttempts = 0
                cancelScheduledReconnect()
                streamingActive = true
                if (!firstFrameRendered) {
                    waitingBindings.stageStatusView.text = "iPhone 已建立 AirPlay 会话，正在启动视频解码与画面渲染..."
                }
                if (!isFinishing && !isDestroyed) {
                    TvSessionForegroundService.startOrUpdate(this@TvStageActivity, wirelessEnabled, "iPhone")
                }
                refreshOverlayVisibility()
            }
        }

        override fun onVideoFrameRendered(session: AirPlaySession) {
            if (controllerGeneration != restartGeneration) return
            if (activeAirPlaySession == null) {
                activeAirPlaySession = session
            }
            CarPlayBackgroundSession.active = true
            mainHandler.post {
                if (controllerGeneration != restartGeneration) return@post
                reconnectAttempts = 0
                cancelScheduledReconnect()
                firstFrameRendered = true
                streamingActive = true
                retryBudget.firstFrame(session, System.currentTimeMillis())
                LastConnectionProblem.clear(this@TvStageActivity)
                refreshOverlayVisibility()
                if (TvPreferencesStore.loadFpsCounter(this@TvStageActivity)) {
                    fpsHudOverlay.start()
                }
            }
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            if (controllerGeneration != restartGeneration) return
            mainHandler.post {
                if (controllerGeneration != restartGeneration) return@post
                if (!isFinishing && !isDestroyed) {
                    TvSessionForegroundService.startOrUpdate(
                        this@TvStageActivity,
                        wirelessEnabled,
                        info.name.ifBlank { "iPhone" },
                    )
                }
            }
        }

        override fun onHostUiRequested(session: AirPlaySession) {
            if (controllerGeneration != restartGeneration) return
            mainHandler.post {
                if (controllerGeneration != restartGeneration || isFinishing || isDestroyed) return@post
                showQuickControlOverlay()
            }
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (controllerGeneration != restartGeneration) return
            val active = activeAirPlaySession
            if (active != null && active !== session) {
                TvDiagnosticsLogger.record("Non-active AirPlay connection ended; keeping live session")
                return
            }
            val hadActiveSession = active != null || streamingActive
            activeAirPlaySession = null
            CarPlayBackgroundSession.active = false
            mainHandler.post {
                if (controllerGeneration != restartGeneration) return@post
                streamingActive = false
                firstFrameRendered = false
                fpsHudOverlay.stop()
                refreshOverlayVisibility()
                if (hadActiveSession) {
                    scheduleAutoReconnect(2_000L)
                }
            }
        }

        override fun onTransportError(message: String) {
            if (controllerGeneration != restartGeneration) return
            TvDiagnosticsLogger.record("AirPlay transport error: $message")
            mainHandler.post {
                if (controllerGeneration != restartGeneration) return@post
                val active = activeAirPlaySession
                if (active != null && !active.isClosed) {
                    TvDiagnosticsLogger.record("Ignoring transport error while active AirPlay session is open: $message")
                    return@post
                }
                val hadActive = active != null || streamingActive
                if (hadActive) {
                    activeAirPlaySession = null
                    CarPlayBackgroundSession.active = false
                    streamingActive = false
                    firstFrameRendered = false
                    fpsHudOverlay.stop()
                    refreshOverlayVisibility()
                    scheduleAutoReconnect(2_000L)
                }
            }
        }

        override fun onDebugLog(message: String) {
            TvDiagnosticsLogger.record(message)
            if (controllerGeneration != restartGeneration || isProjectionVisible) return
            val progressHint = when {
                message.contains("wireless accepted inbound iAP2 RFCOMM") ||
                    message.contains("wireless RFCOMM connected") ->
                    "蓝牙 RFCOMM 通道已连接，正在进行 iAP2 身份与 MFi 认证..."
                message.contains("iap2 tx=0x1D00") ->
                    "正在向 iPhone 发送 CarPlay 身份特征，等待 MFi 签名验证..."
                message.contains("iap2 tx=0xAA03") ->
                    "MFi 硬件证书签名已提交，等待 iPhone 验证通过..."
                message.contains("iap2 rx=0xAA05") ->
                    "MFi 认证已通过，正在等待 iPhone 请求无线网络配置..."
                message.contains("iap2 tx=0x5703") ->
                    "已向 iPhone 下发 Wi-Fi 凭证 (0x5703)，等待 iPhone 接入电视无线网络..."
                message.contains("iap2 tx=0x4301") ->
                    "已发送启动无线 CarPlay 指令 (0x4301)，等待 iPhone 连接 AirPlay 端口..."
                message.contains("first-tcp watchdog disarmed") ||
                    message.contains("wireless type-130 tunnel data stream accepted") ->
                    "iPhone 已连入电视无线网络，正在协商 RTSP 音视频流..."
                else -> null
            } ?: return
            mainHandler.post {
                if (controllerGeneration != restartGeneration || isProjectionVisible) return@post
                if (lastStatus !is CarPlayStatus.Failed) {
                    waitingBindings.stageStatusView.text = progressHint
                }
            }
        }
    }

    private fun onCarPlayStatusChanged(status: CarPlayStatus) {
        onCarPlayStatusChanged(status, restartGeneration)
    }

    private fun onCarPlayStatusChanged(status: CarPlayStatus, controllerGeneration: Int) {
        mainHandler.post {
            if (isFinishing || isDestroyed || controllerGeneration != restartGeneration) return@post
            lastStatus = status
            val wirelessEnabled = TvPreferencesStore.loadWirelessEnabled(this)
            val wirelessMode = TvPreferencesStore.loadWirelessHotspotMode(this)
            val lanSsid = TvPreferencesStore.loadExistingWifiSsid(this)

            waitingBindings.stageStatusView.text = StageWaitingOverlay.friendlyStage(
                context = this,
                status = status,
                wirelessEnabled = wirelessEnabled,
                wirelessHotspotMode = wirelessMode,
            )
            waitingBindings.connectionInstructionsView.text = StageWaitingOverlay.connectionInstructions(
                context = this,
                status = status,
                wirelessEnabled = wirelessEnabled,
                wirelessHotspotMode = wirelessMode,
                existingWifiSsid = lanSsid,
            )

            if (status is CarPlayStatus.Failed) {
                LastConnectionProblem.follow(this, status, status.startupFailure)
                waitingBindings.connectionHelpView.text = StageWaitingOverlay.helpMessage(
                    context = this,
                    failed = status,
                    wirelessEnabled = wirelessEnabled,
                    wirelessMode = wirelessMode,
                )
                waitingBindings.connectionHelpView.visibility = View.VISIBLE
            } else {
                waitingBindings.connectionHelpView.visibility = View.GONE
            }

            if (status is CarPlayStatus.WirelessActive || status is CarPlayStatus.WirelessActiveFallback) {
                reconnectAttempts = 0
                cancelScheduledReconnect()
                streamingActive = true
            } else if (status is CarPlayStatus.RunningControl) {
                reconnectAttempts = 0
                cancelScheduledReconnect()
            } else if (status is CarPlayStatus.ControlEnded) {
                if (!isProjectionVisible && !hasLiveAirPlaySession) {
                    streamingActive = false
                    firstFrameRendered = false
                    scheduleAutoReconnect(3_500L)
                }
            } else if (status is CarPlayStatus.Failed) {
                if (!isProjectionVisible && !hasLiveAirPlaySession) {
                    streamingActive = false
                    firstFrameRendered = false
                    if (
                        wirelessEnabled &&
                        wirelessMode == TvWirelessMode.LAN_WIFI &&
                        status.startupFailure == WirelessStartupFailure.HOTSPOT_CONFIGURATION
                    ) {
                        showLanWifiConfigDialog()
                    }
                    val retryAllowed = status.startupFailure?.retryable != false &&
                        !status.wifiResetRequired
                    if (retryAllowed) {
                        val delayMillis = computeReconnectDelayMillis(
                            startupDelay = null,
                            reason = status.message,
                            reconnectAttempts = reconnectAttempts++,
                        )
                        scheduleAutoReconnect(delayMillis)
                    }
                }
            }
            refreshOverlayVisibility()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STAGE_PERMISSIONS) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED } && !isProjectionVisible) {
                reconnectAttempts = 0
                val engine = sessionEngine
                if (engine != null && !engine.isClosed() && lastStatus !is CarPlayStatus.Failed) {
                    engine.notifyBluetoothPeerChanged()
                } else {
                    cancelScheduledReconnect()
                    reconnectNow()
                }
            }
        } else if (requestCode == REQUEST_BLUETOOTH_DISCOVERABLE_PERMISSION) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                requestBluetoothDiscoverable()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_STAGE_VPN_PERMISSION) {
            startOrAttachSession(adoptExisting = false)
        }
    }

    private fun scheduleAutoReconnect(delayMillis: Long) {
        cancelScheduledReconnect()
        val task = Runnable {
            reconnectRunnable = null
            if (!isFinishing && !isDestroyed && !isProjectionVisible && !hasLiveAirPlaySession) {
                reconnectNow()
            }
        }
        reconnectRunnable = task
        mainHandler.postDelayed(task, delayMillis)
    }

    private fun showTrackedDialog(dialog: android.app.AlertDialog) {
        runCatching { activeDialog?.dismiss() }
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
            }
        }
        dialog.show()
    }

    private fun openConnectionModePicker() {
        val currentWireless = TvPreferencesStore.loadWirelessEnabled(this)
        val currentMode = TvPreferencesStore.loadWirelessHotspotMode(this)
        val currentIndex = when {
            !currentWireless -> 2
            currentMode == TvWirelessMode.LAN_WIFI -> 1
            else -> 0
        }
        val options = arrayOf(
            "无线 · Wi-Fi Direct 5GHz 直连（推荐：免路由器密码，点对点直连）",
            "无线 · 家庭局域网模式（电视与 iPhone 连接同一 Wi-Fi，需配置密码）",
            "USB 有线连接模式（使用 Lightning / USB-C 数据线直连电视 USB 接口）",
        )
        appDialogBuilder(this)
            .setTitle("切换 CarPlay 连接模式")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                dialog.dismiss()
                intent?.removeExtra("wireless")
                when (which) {
                    0 -> {
                        TvPreferencesStore.saveWirelessEnabled(this, true)
                        TvPreferencesStore.saveWirelessHotspotMode(this, TvWirelessMode.WIFI_DIRECT)
                        permissionsPromptedThisSession = false
                        reconnectAttempts = 0
                        reconnectNow()
                    }
                    1 -> {
                        TvPreferencesStore.saveWirelessEnabled(this, true)
                        TvPreferencesStore.saveWirelessHotspotMode(this, TvWirelessMode.LAN_WIFI)
                        permissionsPromptedThisSession = false
                        reconnectAttempts = 0
                        val currentPass = TvPreferencesStore.loadExistingWifiPassphrase(this)
                        val currentSsid = TvPreferencesStore.loadExistingWifiSsid(this)
                            .ifBlank { LanNetworkLink.detectConnectedWifiSsid(this).orEmpty() }
                        if (currentSsid.isNotBlank()) {
                            TvPreferencesStore.saveExistingWifiCredentials(this, currentSsid, currentPass)
                        }
                        if (currentSsid.isBlank() || (currentPass.isBlank() && !LanNetworkLink.isConnectedWifiOpen(this))) {
                            showLanWifiConfigDialog()
                        } else {
                            reconnectNow()
                        }
                    }
                    2 -> {
                        TvPreferencesStore.saveWirelessEnabled(this, false)
                        permissionsPromptedThisSession = false
                        vpnPromptedThisSession = false
                        reconnectAttempts = 0
                        reconnectNow()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .create()
            .also(::showTrackedDialog)
    }

    private fun showLanWifiConfigDialog() {
        if (isFinishing || isDestroyed) return
        val density = resources.displayMetrics.density
        val palette = TvColorScheme.resolve(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = (24 * density).toInt()
            val padV = (12 * density).toInt()
            setPadding(padH, padV, padH, padV)
        }
        val detectedSsid = LanNetworkLink.detectConnectedWifiSsid(this).orEmpty()
        val ssidInput = EditText(this).apply {
            hint = "家庭路由器 Wi-Fi 名称 (SSID)"
            setText(TvPreferencesStore.loadExistingWifiSsid(this@TvStageActivity).ifBlank { detectedSsid })
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            isSingleLine = true
        }
        val passLabel = TextView(this).apply {
            text = "家庭路由器 Wi-Fi 密码（iAP2 握手时需下发给 iPhone，若为无密码开放网络可留空）："
            setTextColor(palette.textSecondary)
            setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt())
        }
        val passInput = EditText(this).apply {
            hint = "WPA2 Wi-Fi 密码"
            setText(TvPreferencesStore.loadExistingWifiPassphrase(this@TvStageActivity))
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(
            ssidInput,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            passLabel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            passInput,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        appDialogBuilder(this)
            .setTitle("配置家庭局域网路由器 Wi-Fi")
            .setMessage(
                "局域网模式下，无线 CarPlay 蓝牙握手 (0x5703 / 0x4301) 必须向 iPhone 下发当前路由器的真实 Wi-Fi 名称与密码，iPhone 才会连接电视的 AirPlay 端口。\n" +
                    "• 请在下方确认路由器 Wi-Fi 名称并填写密码；\n" +
                    "• 或直接改用「Wi-Fi Direct 5GHz 直连」（免填密码）。",
            )
            .setView(container)
            .setPositiveButton("保存并重新连接") { _, _ ->
                val ssid = ssidInput.text?.toString()?.trim().orEmpty()
                val pass = passInput.text?.toString().orEmpty()
                TvPreferencesStore.saveExistingWifiCredentials(this, ssid, pass)
                reconnectAttempts = 0
                reconnectNow()
            }
            .setNeutralButton("改用 Wi-Fi Direct 直连（推荐）") { _, _ ->
                TvPreferencesStore.saveWirelessEnabled(this, true)
                TvPreferencesStore.saveWirelessHotspotMode(this, TvWirelessMode.WIFI_DIRECT)
                permissionsPromptedThisSession = false
                reconnectAttempts = 0
                reconnectNow()
            }
            .setNegativeButton("取消", null)
            .create()
            .also(::showTrackedDialog)
    }

    private fun openBluetoothSettings() {
        val btName = TvPlayBluetooth.localName(this) ?: "本机电视蓝牙"
        val options = arrayOf(
            "开启本机蓝牙可见（推荐：让 iPhone 在蓝牙设置中直接搜到「$btName」）",
            "打开电视系统蓝牙设置（进入系统蓝牙界面配对）",
        )
        appDialogBuilder(this)
            .setTitle("电视与 iPhone 蓝牙配对")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> requestBluetoothDiscoverable()
                    1 -> launchSystemBluetoothSettings()
                }
            }
            .setNegativeButton("取消", null)
            .create()
            .also(::showTrackedDialog)
    }

    private fun requestBluetoothDiscoverable() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BLUETOOTH_DISCOVERABLE_PERMISSION,
            )
            return
        }
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        runCatching {
            startActivity(discoverableIntent)
            val btName = TvPlayBluetooth.localName(this) ?: "本机电视蓝牙"
            Toast.makeText(
                this,
                "请在弹窗中点击「允许」，随后在 iPhone「设置 → 蓝牙」底部点击「$btName」配对",
                Toast.LENGTH_LONG,
            ).show()
        }.onFailure {
            launchSystemBluetoothSettings()
        }
    }

    private fun launchSystemBluetoothSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun refreshOverlayVisibility() {
        val showVideo = streamingActive && firstFrameRendered
        waitingBindings.stageOverlay.visibility = if (showVideo) View.GONE else View.VISIBLE
        if (!showVideo &&
            quickControlOverlay?.visibility != View.VISIBLE &&
            pictureTuningOverlay?.visibility != View.VISIBLE &&
            waitingBindings.stageOverlay.findFocus() == null
        ) {
            waitingBindings.backFromProjectionButton.requestFocus()
        }
    }

    private fun updateVideoLayout(viewWidth: Int, viewHeight: Int) {
        fallbackBounds = TvVideoSurfaceHost.applyVideoLayout(
            videoView = videoView,
            fallbackVideoView = fallbackSurfaceView,
            currentFallbackBounds = fallbackBounds,
            display = sessionDisplay,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
        )
    }

    private fun toggleQuickControlOverlay() {
        if (quickControlOverlay?.visibility == View.VISIBLE) {
            hideQuickControlOverlay()
        } else {
            showQuickControlOverlay()
        }
    }

    private fun showQuickControlOverlay(focusIndex: Int = 0) {
        hideQuickControlOverlay()
        val wirelessEnabled = TvPreferencesStore.loadWirelessEnabled(this)
        val wirelessMode = TvPreferencesStore.loadWirelessHotspotMode(this)
        val fps = TvPreferencesStore.loadFps(this)
        val hevcEnabled = TvPreferencesStore.loadHevcEnabled(this)
        val scalePercent = TvPreferencesStore.loadDisplayScalePercent(this)
        val fpsCounterShown = TvPreferencesStore.loadFpsCounter(this)
        val nightMode = TvPreferencesStore.loadCarPlayNightMode(this)

        val overlay = StageQuickControlOverlay.build(
            context = this,
            wirelessEnabled = wirelessEnabled,
            wirelessHotspotMode = wirelessMode,
            fps = fps,
            sessionHevc = sessionHevc,
            hevcEnabled = hevcEnabled,
            displayScalePercent = scalePercent,
            fpsCounterShown = fpsCounterShown,
            carPlayNightMode = nightMode,
            onResumeProjection = ::hideQuickControlOverlay,
            onReturnToWorkbench = {
                hideQuickControlOverlay()
                returnToWorkbench()
            },
            onOpenPicturePanel = {
                hideQuickControlOverlay()
                openPictureTuningOverlay()
            },
            onCycleDisplayScale = {
                val options = listOf(100, 115, 130, 85, 75)
                val next = options[(options.indexOf(scalePercent) + 1) % options.size]
                TvPreferencesStore.saveDisplayScalePercent(this, next)
                PendingReconnect.mark()
                showQuickControlOverlay(focusIndex = 3)
            },
            onToggleFps = {
                val nextFps = if (fps >= 60) 30 else 60
                TvPreferencesStore.saveFps(this, nextFps)
                PendingReconnect.mark()
                showQuickControlOverlay(focusIndex = 4)
            },
            onToggleHevc = {
                TvPreferencesStore.saveHevcEnabled(this, !hevcEnabled)
                PendingReconnect.mark()
                showQuickControlOverlay(focusIndex = 5)
            },
            onToggleFpsCounter = {
                val next = !fpsCounterShown
                TvPreferencesStore.saveFpsCounter(this, next)
                if (next && streamingActive) fpsHudOverlay.start() else fpsHudOverlay.stop()
                showQuickControlOverlay(focusIndex = 6)
            },
            onCycleNightMode = {
                val next = when (nightMode) {
                    CarPlayNightMode.SYSTEM -> CarPlayNightMode.NIGHT
                    CarPlayNightMode.NIGHT -> CarPlayNightMode.DAY
                    CarPlayNightMode.DAY -> CarPlayNightMode.SYSTEM
                }
                TvPreferencesStore.saveCarPlayNightMode(this, next)
                val resolvedNight = resolveNightMode()
                if (sessionEngine?.setNightMode(resolvedNight) != true) {
                    activeAirPlaySession?.setNightMode(resolvedNight)
                }
                showQuickControlOverlay(focusIndex = 7)
            },
            onReconnectNow = {
                hideQuickControlOverlay()
                reconnectNow()
            },
            onDisconnectAndExit = {
                hideQuickControlOverlay()
                disconnectAndExit()
            },
            initialFocusIndex = focusIndex,
        )
        quickControlOverlay = overlay
        rootFrame.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.requestFocus()
    }

    private fun hideQuickControlOverlay() {
        quickControlOverlay?.let { rootFrame.removeView(it) }
        quickControlOverlay = null
    }

    private fun openPictureTuningOverlay() {
        closePictureTuningOverlay()
        val adjustmentsAvailable = fallbackSurfaceView == null
        val panel = StagePictureTuningOverlay(
            context = this,
            adjustmentsAvailable = adjustmentsAvailable,
            initialPalette = TvColorScheme.DARK,
            close = ::closePictureTuningOverlay,
        )
        pictureTuningOverlay = panel
        val density = resources.displayMetrics.density
        rootFrame.addView(
            panel,
            FrameLayout.LayoutParams(
                (420 * density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        panel.requestFocus()
    }

    private fun closePictureTuningOverlay() {
        app.tvplay.remote.CarPlayPicture.showOriginal(false)
        pictureTuningOverlay?.let { rootFrame.removeView(it) }
        pictureTuningOverlay = null
    }

    private fun resolveNightMode(): Boolean {
        val sysNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return when (TvPreferencesStore.loadCarPlayNightMode(this)) {
            CarPlayNightMode.NIGHT -> true
            CarPlayNightMode.DAY -> false
            CarPlayNightMode.SYSTEM -> sysNight
        }
    }

    private fun closeRetiringPipeline(pipeline: TvAvRenderPipeline) {
        pipeline.close()
        pipeline.whenVideoReleased { retiringPipelines.remove(pipeline) }
        pipeline.awaitVideoReleased(SINK_RELEASE_WAIT_MILLIS)
    }

    private fun reconnectNow() {
        cancelScheduledReconnect()
        PendingReconnect.clear()
        waitingBindings.reconnectBannerView.visibility = View.GONE
        lastStatus = null
        if (teardownInProgress) {
            pendingStartAfterTeardown = true
            return
        }
        val oldEngine = sessionEngine ?: CarPlayBackgroundSession.snapshot()?.controller
        val oldPipeline = avPipeline ?: CarPlayBackgroundSession.snapshot()?.sink
        if (oldEngine == null && oldPipeline == null) {
            startOrAttachSession(adoptExisting = false)
            return
        }
        val generation = ++restartGeneration
        teardownInProgress = true
        pendingStartAfterTeardown = false
        activeAirPlaySession = null
        streamingActive = false
        firstFrameRendered = false
        fpsHudOverlay.stop()
        TvMediaKeyRouter.release(this)
        sessionEngine = null
        oldPipeline?.let(retiringPipelines::add)
        avPipeline = null
        CarPlayBackgroundSession.clear(oldEngine, keepOwner = true)
        waitingBindings.stageStatusView.text = "正在重置连接并重新启动投屏会话..."
        refreshOverlayVisibility()
        teardownExecutor.execute {
            oldEngine?.close()
            oldEngine?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            oldPipeline?.let(::closeRetiringPipeline)
            mainHandler.post {
                teardownInProgress = false
                if (!isFinishing && !isDestroyed && (generation == restartGeneration || pendingStartAfterTeardown)) {
                    pendingStartAfterTeardown = false
                    startOrAttachSession(adoptExisting = false)
                }
            }
        }
    }

    private fun returnToWorkbench() {
        if (!isProjectionVisible) {
            cancelScheduledReconnect()
            stopActiveSession()
            TvSessionForegroundService.stop(this)
        }
        startActivity(
            Intent(this, TvWorkbenchActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("page", "home")
            },
        )
        if (!isProjectionVisible) {
            finish()
        }
    }

    private fun disconnectAndExit() {
        cancelScheduledReconnect()
        stopActiveSession()
        TvSessionForegroundService.stop(this)
        returnToWorkbench()
    }

    private fun stopActiveSession(onComplete: (() -> Unit)? = null) {
        val currentEngine = sessionEngine ?: CarPlayBackgroundSession.snapshot()?.controller
        val currentPipeline = avPipeline ?: CarPlayBackgroundSession.snapshot()?.sink
        activeAirPlaySession = null
        streamingActive = false
        firstFrameRendered = false
        lastStatus = null
        TvMediaKeyRouter.release(this)
        sessionEngine = null
        currentPipeline?.let(retiringPipelines::add)
        avPipeline = null
        CarPlayBackgroundSession.clear(currentEngine)
        if (currentEngine != null || currentPipeline != null) {
            teardownExecutor.execute {
                currentEngine?.close()
                currentEngine?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
                currentPipeline?.let(::closeRetiringPipeline)
                if (onComplete != null) {
                    mainHandler.post(onComplete)
                }
            }
        } else if (onComplete != null) {
            mainHandler.post(onComplete)
        }
    }

    private fun cancelScheduledReconnect() {
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
    }

    private fun applyImmersiveFullscreen() {
        runCatching {
            val decorView = window?.decorView ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                (decorView.windowInsetsController ?: window?.insetsController)?.let { controller ->
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
            }
        }
    }

    companion object {
        private const val REQUEST_STAGE_VPN_PERMISSION = 2001
        private const val REQUEST_STAGE_PERMISSIONS = 2002
        private const val REQUEST_BLUETOOTH_DISCOVERABLE_PERMISSION = 2003
        private const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        private const val SINK_RELEASE_WAIT_MILLIS = 1_500L

        fun connectionInstructions(
            context: Context,
            status: CarPlayStatus?,
            wirelessEnabled: Boolean,
            wirelessHotspotMode: TvWirelessMode,
            existingWifiSsid: String = "",
        ): String = StageWaitingOverlay.connectionInstructions(
            context = context,
            status = status,
            wirelessEnabled = wirelessEnabled,
            wirelessHotspotMode = wirelessHotspotMode,
            existingWifiSsid = existingWifiSsid,
        )

        fun friendlyStage(
            context: Context,
            status: CarPlayStatus,
            wirelessEnabled: Boolean = true,
            wirelessHotspotMode: TvWirelessMode = TvWirelessMode.LAN_WIFI,
        ): String = StageWaitingOverlay.friendlyStage(
            context = context,
            status = status,
            wirelessEnabled = wirelessEnabled,
            wirelessHotspotMode = wirelessHotspotMode,
        )

        fun friendlyStage(
            context: Context,
            message: String,
            englishMessage: String = message,
        ): String = StageWaitingOverlay.friendlyStage(context, message, englishMessage)

        fun describeStatus(
            status: CarPlayStatus,
            res: Resources,
            wirelessEnabled: Boolean,
            wirelessHotspotMode: TvWirelessMode,
        ): String = StageWaitingOverlay.describeStatus(status, res, wirelessEnabled, wirelessHotspotMode)

        fun computeReconnectDelayMillis(
            startupDelay: Long?,
            reason: String,
            reconnectAttempts: Int,
        ): Long = TvProjectionSessionHost.computeReconnectDelayMillis(
            startupDelay = startupDelay,
            reason = reason,
            reconnectAttempts = reconnectAttempts,
        )

        fun evaluateLargerCanvasSupport(
            display: TvDisplaySpec,
            sessionHevc: Boolean,
            hevcSoftwareDecoderEnabled: Boolean = false,
        ): ProjectionCanvasSupport =
            TvProjectionSessionHost.evaluateLargerCanvasSupport(
                display = display,
                sessionHevc = sessionHevc,
                hevcSoftwareDecoderEnabled = hevcSoftwareDecoderEnabled,
            )
    }
}
