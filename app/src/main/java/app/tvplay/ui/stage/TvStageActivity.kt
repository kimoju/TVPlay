package app.tvplay.ui.stage

import android.app.Activity
import android.bluetooth.BluetoothAdapter
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
import android.widget.FrameLayout
import app.tvplay.R
import app.tvplay.config.MfiAssetInstaller
import app.tvplay.config.TvDeviceCapabilities
import app.tvplay.config.TvPlayBluetooth
import app.tvplay.config.TvPreferencesStore
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
    private var sessionHevc = false
    private var vpnPromptedThisSession = false
    private var permissionsPromptedThisSession = false
    private var reconnectRunnable: Runnable? = null
    @Volatile
    private var restartGeneration = 0
    private val teardownExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tvplay-stage-teardown").apply { isDaemon = true }
    }
    private var bluetoothReceiverRegistered = false
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                return
            }
            if (isFinishing || isDestroyed || streamingActive) return
            if (!TvPreferencesStore.loadWirelessEnabled(this@TvStageActivity)) return
            cancelScheduledReconnect()
            sessionEngine?.reconnectIphone() ?: reconnectNow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLaunchIntentMode(intent)
        CarPlayScreen.forwarded(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching {
            registerReceiver(
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
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
            isIphoneUsbAttachment(intent) ||
            previousWireless != currentWireless ||
            PendingReconnect.isPending()
        ) {
            reconnectNow()
        }
    }

    private fun applyLaunchIntentMode(launchIntent: Intent?) {
        if (launchIntent == null) return
        when {
            isIphoneUsbAttachment(launchIntent) -> {
                TvPreferencesStore.saveWirelessEnabled(this, false)
            }
            launchIntent.hasExtra("wireless") -> {
                val wireless = launchIntent.getBooleanExtra("wireless", TvPreferencesStore.loadWirelessEnabled(this))
                TvPreferencesStore.saveWirelessEnabled(this, wireless)
            }
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
        if (bluetoothReceiverRegistered) {
            runCatching { unregisterReceiver(bluetoothStateReceiver) }
            bluetoothReceiverRegistered = false
        }
        fpsHudOverlay.stop()
        pictureBinding?.close()
        pictureBinding = null
        activeSurface = null
        CarPlayScreen.destroyed(this)
        if (isFinishing) {
            stopActiveSession()
        }
        super.onDestroy()
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (quickControlOverlay?.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP) {
                    hideQuickControlOverlay()
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
        if (waitingVisible && !streamingActive) {
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
            hasActiveScreenStream = streamingActive,
            sessionActive = streamingActive,
            sendKnob = { state, momentary ->
                activeAirPlaySession?.let {
                    it.sendKnob(state, momentary)
                    true
                } ?: (sessionEngine?.sendKnob(state, momentary) == true)
            },
            sendMediaButton = { button ->
                activeAirPlaySession?.let {
                    it.sendMedia(button)
                    true
                } ?: (sessionEngine?.sendMedia(button) == true)
            },
            onToggleQuickMenu = ::toggleQuickControlOverlay,
            onOpenQuickMenu = ::showQuickControlOverlay,
            onRequestSiri = {
                activeAirPlaySession?.invokeSiri() ?: sessionEngine?.invokeSiri()
            },
            onLog = TvDiagnosticsLogger::record,
        )
        if (handled == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (streamingActive && quickControlOverlay?.visibility != View.VISIBLE) {
            val viewW = videoContainer.width.coerceAtLeast(1)
            val viewH = videoContainer.height.coerceAtLeast(1)
            val layout = TvVideoSurfaceHost.computeContentRect(sessionDisplay, viewW, viewH)
            val contacts = ViewportTouchMapper.contacts(event, layout)
            activeAirPlaySession?.sendTouch(contacts)
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
        val mode = carPlayVideoSurfaceMode(
            hardwareAccelerated = window?.decorView?.isHardwareAccelerated != false,
            smoothVideo = TvPreferencesStore.loadSmoothVideo(this),
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
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        activeSurface = holder.surface
                        avPipeline?.setMainSurface(holder.surface, width, height)
                        updateVideoLayout(videoContainer.width, videoContainer.height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        avPipeline?.clearSurface(110, holder.surface)
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
                    updateVideoLayout(videoContainer.width, videoContainer.height)
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    activeSurface?.let { avPipeline?.setMainSurface(it, width, height) }
                    updateVideoLayout(videoContainer.width, videoContainer.height)
                }

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    activeSurface?.let {
                        avPipeline?.clearSurface(110, it)
                        it.release()
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
        val lanSsid = TvPreferencesStore.loadExistingWifiSsid(this)

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
        val shouldAdopt = adoptExisting &&
            !PendingReconnect.isPending() &&
            sessionEngine == null &&
            CarPlayBackgroundSession.hasSession()
        PendingReconnect.clear()
        waitingBindings.reconnectBannerView.visibility = View.GONE
        if (shouldAdopt && adoptBackgroundSession()) {
            return
        }
        if (sessionEngine != null) {
            stopActiveSession()
        } else if (CarPlayBackgroundSession.hasSession()) {
            CarPlayBackgroundSession.stop {}
        }
        val controllerGeneration = ++restartGeneration
        val wirelessEnabled = TvPreferencesStore.loadWirelessEnabled(this)
        val wirelessMode = TvPreferencesStore.loadWirelessHotspotMode(this)
        if (wirelessEnabled) {
            TvPlayBluetooth.sanitizeSavedPhone(this)
        }

        if (!permissionsPromptedThisSession) {
            val missingPermissions = if (wirelessEnabled) {
                WirelessPermissions.missing(this, wirelessMode)
            } else {
                WiredPermissions.missing(this)
            }
            if (missingPermissions.isNotEmpty()) {
                permissionsPromptedThisSession = true
                runCatching {
                    requestPermissions(missingPermissions.toTypedArray(), REQUEST_STAGE_PERMISSIONS)
                }
            }
        }

        if (!wirelessEnabled && !vpnPromptedThisSession) {
            val vpnPrepare = UsbTunnelVpnService.prepare(this)
            if (vpnPrepare != null) {
                vpnPromptedThisSession = true
                val dialogStarted = runCatching {
                    startActivityForResult(vpnPrepare, REQUEST_STAGE_VPN_PERMISSION)
                    true
                }.getOrDefault(false)
                if (dialogStarted) return
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
        CarPlayBackgroundSession.store(
            controller = controller,
            sink = pipeline,
            width = displaySpec.widthPixels,
            height = displaySpec.heightPixels,
            owner = this,
            display = initialDisplay,
        ) { done ->
            stopActiveSession()
            TvSessionForegroundService.stop(this@TvStageActivity)
            done()
        }
        if (TvPreferencesStore.loadMediaKeyForwardingEnabled(this)) {
            TvMediaKeyRouter.attach(this, controller, pipeline::onMediaAudioFocusChanged)
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
        CarPlayBackgroundSession.store(
            controller = snapshot.controller,
            sink = snapshot.sink,
            width = snapshot.width,
            height = snapshot.height,
            owner = this,
            display = snapshot.display,
        ) { done ->
            stopActiveSession()
            TvSessionForegroundService.stop(this@TvStageActivity)
            done()
        }
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
        activeSurface?.let { surface ->
            snapshot.sink.setMainSurface(surface, videoContainer.width, videoContainer.height)
        }
        updateVideoLayout(videoContainer.width, videoContainer.height)
        if (TvPreferencesStore.loadMediaKeyForwardingEnabled(this)) {
            TvMediaKeyRouter.attach(this, snapshot.controller, snapshot.sink::onMediaAudioFocusChanged)
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
            session.setNightMode(nightMode)
            mainHandler.post {
                if (controllerGeneration != restartGeneration) return@post
                streamingActive = true
                TvSessionForegroundService.startOrUpdate(this@TvStageActivity, wirelessEnabled, "iPhone")
                refreshOverlayVisibility()
            }
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            if (controllerGeneration != restartGeneration) return
            mainHandler.post {
                if (controllerGeneration != restartGeneration) return@post
                TvSessionForegroundService.startOrUpdate(
                    this@TvStageActivity,
                    wirelessEnabled,
                    info.name.ifBlank { "iPhone" },
                )
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
                val hadActive = activeAirPlaySession != null || streamingActive
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
        }
    }

    private fun onCarPlayStatusChanged(status: CarPlayStatus) {
        onCarPlayStatusChanged(status, restartGeneration)
    }

    private fun onCarPlayStatusChanged(status: CarPlayStatus, controllerGeneration: Int) {
        mainHandler.post {
            if (isFinishing || isDestroyed || controllerGeneration != restartGeneration) return@post
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
                cancelScheduledReconnect()
                streamingActive = true
            } else if (status is CarPlayStatus.RunningControl) {
                cancelScheduledReconnect()
            } else if (status is CarPlayStatus.ControlEnded || status is CarPlayStatus.Failed) {
                streamingActive = false
                firstFrameRendered = false
                val retryAllowed = status !is CarPlayStatus.Failed ||
                    status.startupFailure != WirelessStartupFailure.BLUETOOTH_UNUSABLE
                if (retryAllowed) {
                    scheduleAutoReconnect(3_500L)
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
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED } && !streamingActive) {
                cancelScheduledReconnect()
                sessionEngine?.reconnectIphone() ?: startOrAttachSession(adoptExisting = false)
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
            if (!isFinishing && !isDestroyed && !streamingActive) {
                sessionEngine?.reconnectIphone() ?: reconnectNow()
            }
        }
        reconnectRunnable = task
        mainHandler.postDelayed(task, delayMillis)
    }

    private fun openBluetoothSettings() {
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
        if (!showVideo) {
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

    private fun showQuickControlOverlay() {
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
                showQuickControlOverlay()
            },
            onToggleFps = {
                val nextFps = if (fps >= 60) 30 else 60
                TvPreferencesStore.saveFps(this, nextFps)
                PendingReconnect.mark()
                showQuickControlOverlay()
            },
            onToggleHevc = {
                TvPreferencesStore.saveHevcEnabled(this, !hevcEnabled)
                PendingReconnect.mark()
                showQuickControlOverlay()
            },
            onToggleFpsCounter = {
                val next = !fpsCounterShown
                TvPreferencesStore.saveFpsCounter(this, next)
                if (next && streamingActive) fpsHudOverlay.start() else fpsHudOverlay.stop()
                showQuickControlOverlay()
            },
            onCycleNightMode = {
                val next = when (nightMode) {
                    CarPlayNightMode.SYSTEM -> CarPlayNightMode.NIGHT
                    CarPlayNightMode.NIGHT -> CarPlayNightMode.DAY
                    CarPlayNightMode.DAY -> CarPlayNightMode.SYSTEM
                }
                TvPreferencesStore.saveCarPlayNightMode(this, next)
                activeAirPlaySession?.setNightMode(resolveNightMode())
                showQuickControlOverlay()
            },
            onReconnectNow = {
                hideQuickControlOverlay()
                reconnectNow()
            },
            onDisconnectAndExit = {
                hideQuickControlOverlay()
                disconnectAndExit()
            },
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

    private fun reconnectNow() {
        cancelScheduledReconnect()
        PendingReconnect.clear()
        waitingBindings.reconnectBannerView.visibility = View.GONE
        val oldEngine = sessionEngine ?: CarPlayBackgroundSession.snapshot()?.controller
        val oldPipeline = avPipeline ?: CarPlayBackgroundSession.snapshot()?.sink
        if (oldEngine == null && oldPipeline == null) {
            startOrAttachSession(adoptExisting = false)
            return
        }
        val generation = ++restartGeneration
        activeAirPlaySession = null
        streamingActive = false
        firstFrameRendered = false
        fpsHudOverlay.stop()
        TvMediaKeyRouter.release(this)
        sessionEngine = null
        avPipeline = null
        CarPlayBackgroundSession.clear(oldEngine, keepOwner = true)
        waitingBindings.stageStatusView.text = "正在重置连接并重新启动投屏会话..."
        refreshOverlayVisibility()
        teardownExecutor.execute {
            oldEngine?.close()
            oldEngine?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            oldPipeline?.close()
            mainHandler.post {
                if (!isFinishing && !isDestroyed && generation == restartGeneration) {
                    startOrAttachSession(adoptExisting = false)
                }
            }
        }
    }

    private fun returnToWorkbench() {
        if (!streamingActive) {
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
        if (!streamingActive) {
            finish()
        }
    }

    private fun disconnectAndExit() {
        cancelScheduledReconnect()
        stopActiveSession()
        TvSessionForegroundService.stop(this)
        returnToWorkbench()
    }

    private fun stopActiveSession() {
        val currentEngine = sessionEngine
        activeAirPlaySession = null
        streamingActive = false
        firstFrameRendered = false
        TvMediaKeyRouter.release(this)
        currentEngine?.close()
        sessionEngine = null
        avPipeline?.close()
        avPipeline = null
        CarPlayBackgroundSession.clear(currentEngine)
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
        private const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L

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
