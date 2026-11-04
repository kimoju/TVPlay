package app.tvplay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.tvplay.airplay.AirPlayConfig
import app.tvplay.airplay.AirPlayDisplayConfig
import app.tvplay.airplay.AirPlayDisplaySettings
import app.tvplay.airplay.AirPlayIcon
import app.tvplay.airplay.AirPlayIdentity
import app.tvplay.airplay.AirPlayKnobState
import app.tvplay.airplay.AirPlayPhysicalSizeBasis
import app.tvplay.airplay.AirPlayPhysicalSizeMm
import app.tvplay.airplay.AirPlaySafeArea
import app.tvplay.airplay.AirPlaySession
import app.tvplay.airplay.AirPlaySessionListener
import app.tvplay.airplay.CarPlayDisplayScale
import app.tvplay.airplay.CarPlayMediaButton
import app.tvplay.airplay.CarPlayMediaEngine
import app.tvplay.airplay.CarPlayUiScale
import app.tvplay.R
import app.tvplay.media.AndroidMediaSink
import app.tvplay.media.CarPlayTouchMapper
import app.tvplay.media.CarPlayVideoLayout
import app.tvplay.media.OpusEncoderSupport
import app.tvplay.network.CarPlayVpnService
import app.tvplay.network.WirelessStartupFailure
import app.tvplay.network.WirelessStartupPolicy
import app.tvplay.orchestration.CarPlayController
import app.tvplay.orchestration.CarPlayRuntimeConfig
import app.tvplay.orchestration.CarPlayStatus
import app.tvplay.orchestration.CarPlayTransport
import app.tvplay.orchestration.ConnectionFailureCause
import app.tvplay.orchestration.ManualHotspotBand
import app.tvplay.orchestration.ManualHotspotSecurity
import app.tvplay.orchestration.MfiTarget
import app.tvplay.orchestration.WirelessHotspotMode
import app.tvplay.projection.ProjectionCanvasSupport
import app.tvplay.projection.ProjectionSessionManager
import app.tvplay.projection.RemoteInputDispatcher
import app.tvplay.projection.VideoSurfaceController
import app.tvplay.transport.BluetoothLinkCheck
import app.tvplay.transport.BluetoothRadioCheck
import app.tvplay.transport.Iap2IdentificationConfig
import app.tvplay.transport.IphoneUsbMatcher
import app.tvplay.transport.UsbDeviceId
import app.tvplay.ui.projection.ProjectionWaitingView
import app.tvplay.ui.projection.QuickControlOverlayView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Full-screen TVPlay CarPlay projection host.
 *
 * Renders decoded video through a [TextureView] (or [SurfaceView] when smooth/direct video is
 * active), maps TV remote D-pad keys to CarPlay's native knob focus model via [CarPlayRemoteKeys],
 * and provides an Apple TV-style quick control overlay menu on `MENU` or long-press `BACK`.
 */
open class TvPlayProjectionActivity : ComponentActivity() {
    private var connectionPanel: View? = null
    private var connectionInstructionsView: TextView? = null
    private var connectionHelpView: TextView? = null
    private var wifiRecoveryButton: View? = null
    private var reconnectAttempts = 0
    private val legacySiriPresses = mutableSetOf<Triple<Int, Int, Int>>()
    private val startupRetryBudget = WirelessStartupRetryBudget()
    private var startupRetryStopped = false
    private var startupRetryButton: View? = null
    private var startupFailureGeneration = -1
    private lateinit var airPlayIdentity: AirPlayIdentity
    private var forwarded = false

    private fun createRuntimeConfig(): CarPlayRuntimeConfig =
        ProjectionSessionManager.createRuntimeConfig(
            context = this,
            airPlayIdentity = airPlayIdentity,
            mfiTarget = mfiTarget,
            mfiI2cPath = mfiI2cPath,
            remoteMfiServer = remoteMfiServer,
            remoteMfiToken = remoteMfiToken,
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            wirelessEnabled = wirelessEnabled,
            wirelessHotspotMode = wirelessHotspotMode,
            manualHotspotSsid = manualHotspotSsid,
            manualHotspotPassphrase = manualHotspotPassphrase,
            manualHotspotBand = manualHotspotBand,
            manualHotspotChannel = manualHotspotChannel,
            manualHotspotSecurity = manualHotspotSecurity,
            manualHotspotInterface = manualHotspotInterface,
            existingWifiSsid = existingWifiSsid,
            existingWifiPassphrase = existingWifiPassphrase,
            existingWifiInterface = existingWifiInterface,
        )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onVpnConsentResult(result.resultCode)
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied missing=" +
                        missingWirelessPermissions().joinToString(",") { it.substringAfterLast('.') }
                },
            )
            wirelessPermissionsRefused = !wirelessPermissionsReady
            showMissingWirelessPermission()
            maybeStartCarPlay()
        }
    private val wiredPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingWiredPermissions = false
            wiredPermissionsResolved = true
            appendLog(
                if (grants.values.all { it }) {
                    "Wired startup permissions granted"
                } else {
                    "Local network access denied"
                },
            )
            requestStartupPrerequisites()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            requestStartupPrerequisites()
        }

    private var videoView: View? = null
    private var blackCornerOverlay: CarPlayBlackCornerOverlay? = null
    private var pendingIphoneCornerMask: Pair<AirPlaySession, Pair<ByteArray, Int>>? = null
    private var iphoneCornerMaskDecodeGeneration = 0

    private fun localCornerRadius(): Int =
        if (blackCornerRadiusDp == CarPlayBlackCornerOverlay.IPHONE_MASK) 24 else blackCornerRadiusDp

    private fun clearIphoneCornerMask() {
        ++iphoneCornerMaskDecodeGeneration
        pendingIphoneCornerMask = null
        blackCornerOverlay?.setIphoneMask(null)
    }

    private fun installIphoneCornerMask(session: AirPlaySession, png: ByteArray, displayWidth: Int) {
        val revision = ++iphoneCornerMaskDecodeGeneration
        Thread({
            val decoded = runCatching { IphoneCornerMask.decode(png, displayWidth) }.getOrNull()
            runOnUiThread {
                if (revision == iphoneCornerMaskDecodeGeneration && activeAirPlaySession === session &&
                    !shuttingDown.get() && blackCornerRadiusDp == CarPlayBlackCornerOverlay.IPHONE_MASK) {
                    if (decoded != null) {
                        blackCornerOverlay?.setIphoneMask(decoded)
                        appendLog("iPhone corner mask applied: ${decoded.size}px for width $displayWidth")
                    } else {
                        blackCornerOverlay?.setIphoneMask(null)
                        appendLog("iPhone corner mask rejected; using local radius fallback")
                    }
                } else decoded?.blackPixels?.recycle()
            }
        }, "tvplay-iphone-corner-mask").start()
    }

    private var fallbackVideoView: SurfaceView? = null
    private var fallbackVideoBounds: CarPlaySurfaceBounds? = null
    private var lastPicture: Bitmap? = null
    private var pictureHold: ImageView? = null
    private var pictureHoldBaseline = -1L
    private var pictureHoldStartMillis = 0L
    private var pictureCopyThreadStarted = false
    private val pictureCopyThread by lazy {
        pictureCopyThreadStarted = true
        android.os.HandlerThread("tvplay-picture-copy").apply { start() }
    }
    private val pictureHoldTick = object : Runnable {
        override fun run() {
            val hold = pictureHold ?: return
            val rendered = sink?.liveVideoCounters()?.rendered ?: -1L
            val elapsed = SystemClock.uptimeMillis() - pictureHoldStartMillis
            if (lastPictureDone(rendered, pictureHoldBaseline, elapsed, PICTURE_HOLD_MAX_MILLIS)) {
                appendLog("Last picture hidden after ${elapsed} ms")
                hold.animate().alpha(0f).setDuration(150L).withEndAction {
                    hold.visibility = View.GONE
                    hold.setImageDrawable(null)
                }.start()
                return
            }
            mainHandler.postDelayed(this, 50L)
        }
    }
    private val pictureRetry = Runnable {
        if (pictureHold?.visibility == View.VISIBLE) {
            appendLog("No frame yet after returning; asking for a keyframe again")
            sink?.refreshPicture(SCREEN_TYPE_MAIN)
        }
    }
    private var smoothVideo = false
    private var directVideoOutput = false
    private var fpsCounter: FpsCounterOverlay? = null
    private val retiringSinks = CopyOnWriteArraySet<AndroidMediaSink>()
    internal var sinkReleaseWaitMillis = SINK_RELEASE_WAIT_MILLIS
    private var videoSurfaceProbe: ViewTreeObserver.OnPreDrawListener? = null
    private var pictureBinding: CarPlayPicture.Binding? = null
    private var picturePanel: View? = null
    private var picturePanelGeneration = 0
    private var gestureOverlay: View? = null
    private var settingsMenu: View? = null
    private var suppressBackReleaseAfterLongPress = false
    private var stageStatusView: TextView? = null
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private val videoSurfaceOwner = CarPlayVideoSurfaceOwner<Surface>(
        detach = { surface ->
            sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
            sink?.clearSurface(SCREEN_TYPE_ALT, surface)
        },
        release = { it.release() },
    )
    private val currentSurface: Surface? get() = videoSurfaceOwner.current
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var noPictureShown = false
    private val noPictureCheck = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            val missing = SCREEN_TYPE_MAIN in activeScreenStreamTypes && sink?.mainVideoReceived == false
            if (missing && !noPictureShown) {
                appendLog("Screen stream open ${NO_PICTURE_MILLIS / 1000} s with no video frame")
            }
            noPictureShown = missing
            updateDebugOverlays()
            if (missing) mainHandler.postDelayed(this, NO_PICTURE_RECHECK_MILLIS)
        }
    }
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var sessionDisplay: CarPlaySessionDisplay? = null
    private var touchOutsideContent = false
    private var displayScalePercent = 100
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var uiScalePercent = CarPlayUiScale.DEFAULT
    private var displayDiagnosticAttempt: String? = null
    private var hevcEnabled = true
    private var hevcSoftwareDecoderEnabled = false
    private val hevcDecoders by lazy { HevcSupport.decoders() }
    private val sessionHevc: Boolean
        get() = HevcSupport.usable(hevcEnabled, hevcSoftwareDecoderEnabled, hevcDecoders)
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    private var navigationStreamType = 14
    private var debugLogsEnabled = false
    private var autoStartOnBoot = false
    private var manufacturer = "Apple"
    private var model = "AppleTV"
    private var oemLabel = "TVPlay"
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var carPlayDock = CarPlayDock.AUTOMATIC
    private var pendingViewAreas: CarPlayViewAreas? = null
    private var sessionDock: CarPlayDock? = null
    private var hideTopBar = true
    private var hideBottomBar = true
    private var temporarySystemBarsEnabled = false
    private var temporarySystemBarsVisible = false
    private val hideTemporarySystemBars = Runnable {
        temporarySystemBarsVisible = false
        applyFullscreenMode()
    }
    private var safeAreaDrawOutside = true
    private var mainDisplayDisableSafeArea = false
    private var blackCornerRadiusDp = 0
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    private var wirelessEnabled = false
    private var mfiTarget = MfiTarget.LOCAL
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.EXISTING_WIFI
    private var manualHotspotSsid = ""
    private var existingWifiSsid = ""
    private var existingWifiPassphrase = ""
    private var existingWifiInterface = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var manualHotspotInterface = ""
    private var awaitingVpnConsent = false
    private var awaitingWirelessPermissions = false
    private var wirelessPermissionsRefused = false
    private var awaitingWiredPermissions = false
    private var wiredPermissionsResolved = false
    private var vpnReady = false
    private var menuOpen = false
    private var latestStage = "正在准备 CarPlay 投屏…"
    private val englishResources: Resources by lazy {
        createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale.ENGLISH) })
            .resources
    }
    private var darkMode = true
    private var appNight = true
    private var hostAppearanceResumed = false
    private var paintWaitingScreen: () -> Unit = {}
    private var carPlayNightMode = CarPlayNightMode.NIGHT
    private var nightSchedule = CarPlayNightSchedule()
    private var ambientDelaySeconds = 2
    private var nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
    private val noOpLightSource = object : CarPlayNightModeController.LightSource {
        override val available: Boolean = false
        override fun start(onLux: (Float) -> Unit): Boolean = false
        override fun stop() = Unit
    }
    private val nightModeController by lazy {
        CarPlayNightModeController(
            light = noOpLightSource,
            scheduler = object : CarPlayNightModeController.Scheduler {
                private val handler = Handler(Looper.getMainLooper())
                override fun postDelayed(task: Runnable, delayMillis: Long) {
                    handler.postDelayed(task, delayMillis)
                }
                override fun remove(task: Runnable) {
                    handler.removeCallbacks(task)
                }
            },
            initialNight = darkMode,
            onNightChanged = { night ->
                darkMode = night
                if (hostAppearanceResumed) AppAppearanceRuntime.publishHost(this, night)
                refreshAppAppearance()
                paintWaitingScreen()
                appendLog("CarPlay switched to ${if (night) "night" else "day"} mode")
                logThemeState(nightModeDiagnosticSource, resources.configuration)
                syncAirPlayDarkMode(nightModeDiagnosticSource)
            },
        )
    }
    private val themeDiagnostics = ThemeModeDiagnostics()
    private var lastConfiguration: Configuration? = null
    private var activeAirPlaySession: AirPlaySession? = null
    private val activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var startAfterHandshakeReset = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private var pendingReconnect: Runnable? = null
    private var reconnectWaitsForBluetooth = false
    private val bluetoothOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) return
            val retry = pendingReconnect ?: return
            if (!reconnectScheduled || !reconnectWaitsForBluetooth) return
            appendLog("Bluetooth turned on; retrying now")
            mainHandler.removeCallbacks(retry)
            mainHandler.post(retry)
        }
    }
    private var controlRunningCount = 0
    private var lossSinceControlRunning = false
    private var sessionLog: SessionLogFile? = null
    private var gestureFingerCount = 3
    private var settingsGestureHint: TextView? = null
    private var gestureSequenceActive = false
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var heldTouch: MotionEvent? = null
    private var heldTouchContent: CarPlayVideoLayout? = null
    private var heldTouchX = 0f
    private var heldTouchY = 0f
    private val sendHeldTouch = Runnable { flushHeldTouch() }
    private var recoveryPendingAfterMenu = false
    private var failurePendingAfterMenu: CarPlayStatus.Failed? = null
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val pollConfiguration = object : Runnable {
        override fun run() {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.POLL)
            mainHandler.postDelayed(this, CONFIGURATION_POLL_INTERVAL_MILLIS)
        }
    }
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = currentSurface
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    videoSurfaceOwner.replace(it, releaseOnDetach = true)
                    currentSurfaceTexture = texture
                }
            }
            appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
            attachSurface(surface)
            updateVideoLayout(width, height)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            updateVideoLayout(width, height)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) return true
            currentSurface?.let(videoSurfaceOwner::clear)
            currentSurfaceTexture = null
            appendLog("Texture surface destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    private val fallbackSurfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (isDestroyed || holder !== fallbackVideoView?.holder) return
            val surface = holder.surface
            videoSurfaceOwner.replace(surface, releaseOnDetach = false)
            appendLog("SurfaceView video surface created valid=${surface.isValid}")
            attachSurface(surface)
            videoView?.let { updateVideoLayout(it.width, it.height) }
            showLastPicture()
            sink?.refreshPicture(SCREEN_TYPE_MAIN)
            mainHandler.removeCallbacks(pictureRetry)
            mainHandler.postDelayed(pictureRetry, 1_000L)
            mainHandler.postDelayed(pictureRetry, 2_000L)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (holder !== fallbackVideoView?.holder) return
            videoView?.let { updateVideoLayout(it.width, it.height) }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            val surface = holder.surface
            captureLastPicture()
            val live = sink
            val detaches = (listOfNotNull(live) + retiringSinks).distinct()
                .map { owner -> owner.beginSurfaceDetach(surface, parkMain = smoothVideo && owner === live) }
            val confirmed = detaches.map { it.await() }.all { it }
            videoSurfaceOwner.clear(surface)
            appendLog("SurfaceView video surface destroyed detachConfirmed=$confirmed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CarPlayScreen.forwarded(this)) {
            forwarded = true
            return
        }
        val bluetoothState = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothOnReceiver, bluetoothState, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothOnReceiver, bluetoothState)
        }
        if (isIphoneUsbAttachment(intent)) {
            AirPlayPersistence.saveWirelessEnabled(this, false)
        }
        if (runCatching { TvPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.isFailure) {
            OwnScreenStart.start(this, Intent(this, TvPlayWorkbenchActivity::class.java), ::appendLog)
            finish()
            return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initializeSessionLog()
        lastConfiguration = Configuration(resources.configuration)
        darkMode = savedInstanceState?.getBoolean("carplay_night_active")
            ?: nightModeOrNull(resources.configuration.uiMode) ?: true
        appNight = true
        logThemeState(ThemeModeDiagnostics.Source.CREATE, resources.configuration)
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        setContentView(buildContentView())
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (picturePanel != null) {
                        closePicturePanel()
                    } else if (menuOpen) {
                        closeQuickMenu(reconnect = false)
                    } else {
                        showDiPlayHome()
                    }
                }
            },
        )

        appendLog(
            "TVPlay Host started; MFI target=${mfiTarget.name}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}; " +
                "task=$taskId",
        )
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadPersistedSettings() {
        carPlayDock = CarPlayDock.load(this)
        carPlayNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        nightSchedule = AirPlayPersistence.loadCarPlayNightSchedule(this)
        nightModeController.configure(
            carPlayNightMode,
            nightModeOrNull(resources.configuration.uiMode) ?: true,
            15f,
            ambientDelaySeconds,
            nightSchedule,
        )
        gestureFingerCount = 3
        displayScalePercent = AirPlayPersistence.loadDisplayScalePercent(this)
        displayScaleTenths = CarPlayDisplayScale.sanitize((displayScalePercent + 5) / 10)
        uiScalePercent = AirPlayPersistence.loadUiScalePercent(this)
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        navigationStreamType = AirPlayPersistence.loadNavigationStreamType(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        temporarySystemBarsEnabled = AirPlayPersistence.loadTemporarySystemBars(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        mainDisplayDisableSafeArea = AirPlayPersistence.loadMainDisplayDisableSafeArea(this)
        blackCornerRadiusDp = AirPlayPersistence.loadBlackCornerRadiusDp(this)
        blackCornerOverlay?.radiusDp = localCornerRadius()
        if (blackCornerRadiusDp != CarPlayBlackCornerOverlay.IPHONE_MASK) clearIphoneCornerMask()
        loadConnectionSettings()
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    private fun loadConnectionSettings() {
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(this)
        existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(this)
        existingWifiInterface = AirPlayPersistence.loadExistingWifiInterface(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
        manualHotspotInterface = AirPlayPersistence.loadManualHotspotInterface(this)
    }

    private fun requestStartupPrerequisites() {
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else if (!requestWiredPermissions()) {
            requestVpnConsent()
        }
    }

    private fun requestWiredPermissions(): Boolean {
        if (wiredPermissionsResolved) return false
        val missing = WiredPermissions.required(Build.VERSION.SDK_INT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return false
        if (!awaitingWiredPermissions) {
            awaitingWiredPermissions = true
            wiredPermissions.launch(missing.toTypedArray())
        }
        return true
    }

    private fun requestVpnConsent() {
        if (awaitingVpnConsent) return
        val consent = try {
            CarPlayVpnService.prepare(this)
        } catch (error: ActivityNotFoundException) {
            onVpnConsentUnavailable("prepare", null, error)
            return
        } catch (error: SecurityException) {
            onVpnConsentUnavailable("prepare", null, error)
            return
        }
        if (consent == null) {
            vpnReady = true
            showConnectionHelp(null)
            maybeStartCarPlay()
            return
        }
        vpnReady = false
        awaitingVpnConsent = true
        try {
            vpnConsent.launch(consent)
        } catch (error: ActivityNotFoundException) {
            onVpnConsentUnavailable("launch", consent, error)
        } catch (error: SecurityException) {
            onVpnConsentUnavailable("launch", consent, error)
        }
    }

    private fun onVpnConsentResult(resultCode: Int) {
        if (!awaitingVpnConsent) return
        awaitingVpnConsent = false
        vpnReady = resultCode == RESULT_OK
        if (vpnReady) {
            maybeStartCarPlay()
        } else {
            setStatus(R.string.vpn_consent_was_denied)
        }
    }

    private fun onVpnConsentUnavailable(operation: String, consent: Intent?, error: RuntimeException) {
        awaitingVpnConsent = false
        vpnReady = false
        val diagnostic = "VPN consent unavailable operation=$operation " +
            "failureClass=${error.javaClass.simpleName} " +
            "component=${consent?.component?.flattenToString() ?: "none"}"
        Log.w(TAG, diagnostic, error)
        appendLog(diagnostic)
        setStatus(R.string.vpn_authorization_unavailable)
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun missingWirelessPermissions(): List<String> =
        requiredWirelessPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun showMissingWirelessPermission() {
        if (wirelessPermissionsReady || !wirelessEnabled) {
            wirelessPermissionsRefused = false
            return
        }
        if (!wirelessPermissionsRefused) return
        setConnectionStage(WirelessPermissions.refusalMessage(missingWirelessPermissions()))
    }

    private fun requiredWirelessPermissions(): List<String> =
        WirelessPermissions.required(wirelessHotspotMode, Build.VERSION.SDK_INT)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isIphoneUsbAttachment(intent)) {
            if (wirelessEnabled) {
                if (menuOpen) closeQuickMenu(reconnect = false)
                AirPlayPersistence.saveWirelessEnabled(this, false)
                wirelessEnabled = false
                wirelessPermissionsReady = true
                if (controller != null) {
                    restartCarPlay("switching to USB")
                }
                requestStartupPrerequisites()
            } else if (controller == null) {
                requestStartupPrerequisites()
            }
        }
    }

    private fun isIphoneUsbAttachment(intent: Intent): Boolean {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        val device = androidx.core.content.IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        return device?.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID
    }

    override fun onStart() {
        super.onStart()
        logThemeState(ThemeModeDiagnostics.Source.START, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        mainHandler.post(pollConfiguration)
        if (AirPlayPersistence.loadFpsCounter(this)) fpsCounter?.start() else fpsCounter?.stop()
    }

    override fun onResume() {
        super.onResume()
        hostAppearanceResumed = true
        val savedNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        val savedSchedule = AirPlayPersistence.loadCarPlayNightSchedule(this)
        val systemNight = nightModeOrNull(resources.configuration.uiMode) ?: true
        if (savedNightMode != carPlayNightMode || savedSchedule != nightSchedule) {
            carPlayNightMode = savedNightMode
            nightSchedule = savedSchedule
            nightModeController.configure(carPlayNightMode, systemNight, 15f, ambientDelaySeconds, nightSchedule)
        }
        nightModeController.resume(systemNight)
        AppAppearanceRuntime.publishHost(this, darkMode)
        refreshAppAppearance()
        if (!menuOpen) {
            displayScalePercent = AirPlayPersistence.loadDisplayScalePercent(this)
            displayScaleTenths = CarPlayDisplayScale.sanitize((displayScalePercent + 5) / 10)
            uiScalePercent = AirPlayPersistence.loadUiScalePercent(this)
            fps = AirPlayPersistence.loadFps(this)
            hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        }
        if (intent.getBooleanExtra("picture_controls", false)) {
            intent.removeExtra("picture_controls")
            openPicturePanel()
        }
        if (!menuOpen) loadConnectionSettings()
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        showMissingWirelessPermission()
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        connectionInstructionsView?.text = connectionInstructions(null)
        var systemBarsChanged = false
        if (!menuOpen) {
            val savedHideTopBar = AirPlayPersistence.loadHideTopBar(this)
            val savedHideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
            systemBarsChanged = hideTopBar != savedHideTopBar ||
                hideBottomBar != savedHideBottomBar
            hideTopBar = savedHideTopBar
            hideBottomBar = savedHideBottomBar
        }
        maybeStartCarPlay()
        applyFullscreenMode()
        if (systemBarsChanged) refreshDisplaySizeAfterLayout()
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) {
                scheduleDisplaySize(view.width, view.height)
            }
        }
    }

    @Suppress("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = RemoteInputDispatcher.dispatchKeyEvent(
            context = this,
            event = event,
            legacySiriPresses = legacySiriPresses,
            menuOpen = menuOpen,
            suppressBackReleaseAfterLongPress = suppressBackReleaseAfterLongPress,
            setSuppressBackRelease = { suppressBackReleaseAfterLongPress = it },
            hasActiveScreenStream = activeScreenStreamTypes.isNotEmpty(),
            sessionActive = activeAirPlaySession != null,
            controller = controller,
            onToggleQuickMenu = { if (menuOpen) closeQuickMenu(reconnect = false) else openSettingsMenu() },
            onOpenQuickMenu = ::openSettingsMenu,
            onRequestSiri = ::requestLegacySiri,
            onLog = ::appendLog,
        )
        return handled ?: super.dispatchKeyEvent(event)
    }

    private fun requestLegacySiri(keyCode: Int) {
        val sent = controller?.requestSiri() == true
        appendLog("Siri: voice key $keyCode sent=$sent")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.WINDOW_FOCUS)
            applyFullscreenMode()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("carplay_night_active", darkMode)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        cancelTemporarySystemBars()
        hostAppearanceResumed = false
        AppAppearanceRuntime.clearHost(this)
        nightModeController.pause()
        super.onPause()
    }

    override fun onStop() {
        fpsCounter?.stop()
        closePicturePanel()
        logThemeState(ThemeModeDiagnostics.Source.STOP, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshConfiguration(newConfig, ThemeModeDiagnostics.Source.CALLBACK)
        applyFullscreenMode()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (isInMultiWindowMode) cancelTemporarySystemBars()
        applyFullscreenMode()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onDestroy() {
        if (forwarded) {
            super.onDestroy()
            return
        }
        CarPlayScreen.destroyed(this)
        runCatching { unregisterReceiver(bluetoothOnReceiver) }
        mainHandler.removeCallbacks(hideTemporarySystemBars)
        dropHeldTouch()
        discardLastPicture()
        if (pictureCopyThreadStarted) pictureCopyThread.quitSafely()
        hostAppearanceResumed = false
        AppAppearanceRuntime.clearHost(this)
        nightModeController.pause()
        pictureBinding?.close()
        pictureBinding = null
        removeVideoSurfaceProbe()
        fallbackVideoView?.holder?.removeCallback(fallbackSurfaceCallback)
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(pollConfiguration)
        mainHandler.removeCallbacks(noPictureCheck)
        videoSurfaceOwner.clear()
        currentSurfaceTexture = null
        fallbackVideoView = null
        fallbackVideoBounds = null
        sessionLog?.append("Activity destroyed")
        sessionLog?.close()
        sessionLog = null
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = TextureView(this).apply {
            isOpaque = false
            surfaceTextureListener = textureListener
        }
        pictureBinding = CarPlayPicture.Binding(video)
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        blackCornerOverlay = CarPlayBlackCornerOverlay(this).also { overlay ->
            overlay.radiusDp = localCornerRadius()
            root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))

        val waitingBindings = ProjectionWaitingView.build(
            context = this,
            appNight = appNight,
            initialInstructions = connectionInstructions(null),
            onRetryClicked = {
                if (CarPlayBackgroundSession.isOwner(this@TvPlayProjectionActivity) &&
                    !shuttingDown.get() && !menuOpen && !handshakeResetInProgress
                ) {
                    startupRetryBudget.manualRetry()
                    startupRetryStopped = false
                    reconnectAttempts = 0
                    startupRetryButton?.visibility = View.GONE
                    restartCarPlay(getString(R.string.connecting_to_your_iphone))
                }
            },
            onWifiRecoveryClicked = { showDiPlayHome("wireless-recovery") },
            onBackToWorkbenchClicked = { showDiPlayHome() },
        )
        paintWaitingScreen = {
            waitingBindings.viewport.setBackgroundColor(WaitingScreenColors.of(appNight).background)
        }
        root.addView(waitingBindings.viewport, FrameLayout.LayoutParams(-1, -1))

        fpsCounter = FpsCounterOverlay(this, mainHandler) { sink?.liveVideoCounters() }.also { counter ->
            root.addView(
                counter.view,
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
                    setMargins(0, dp(16), dp(16), 0)
                },
            )
        }
        settingsMenu = buildQuickControlMenu().apply { visibility = View.GONE }
        root.addView(settingsMenu, FrameLayout.LayoutParams(-1, -1))
        videoView = video
        smoothVideo = AirPlayPersistence.loadSmoothVideo(this)
        directVideoOutput = AirPlayPersistence.loadDirectVideoOutput(this)
        observeVideoWindow(video)
        gestureOverlay = gestureLayer
        settingsGestureHint = waitingBindings.settingsGestureHint
        stageStatusView = waitingBindings.stageStatusView
        connectionInstructionsView = waitingBindings.connectionInstructionsView
        connectionHelpView = waitingBindings.connectionHelpView
        startupRetryButton = waitingBindings.startupRetryButton
        wifiRecoveryButton = waitingBindings.wifiRecoveryButton
        connectionPanel = waitingBindings.viewport
        updateDebugOverlays()
        return root
    }

    private fun buildQuickControlMenu(): View = QuickControlOverlayView.build(
        context = this,
        wirelessEnabled = wirelessEnabled,
        wirelessHotspotMode = wirelessHotspotMode,
        fps = fps,
        sessionHevc = sessionHevc,
        hevcEnabled = hevcEnabled,
        displayScalePercent = displayScalePercent,
        fpsCounterShown = AirPlayPersistence.loadFpsCounter(this),
        carPlayNightMode = carPlayNightMode,
        onResumeProjection = { closeQuickMenu(reconnect = false) },
        onReturnToWorkbench = {
            closeQuickMenu(reconnect = false)
            showDiPlayHome()
        },
        onOpenPicturePanel = {
            closeQuickMenu(reconnect = false)
            openPicturePanel()
        },
        onCycleDisplayScale = {
            displayScalePercent = when (displayScalePercent) {
                100 -> 125
                125 -> 85
                else -> 100
            }
            displayScaleTenths = CarPlayDisplayScale.sanitize((displayScalePercent + 5) / 10)
            AirPlayPersistence.saveDisplayScalePercent(this, displayScalePercent)
            AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
            closeQuickMenu(reconnect = true)
        },
        onToggleFps = {
            fps = if (fps >= 60) 30 else 60
            AirPlayPersistence.saveFps(this, fps)
            closeQuickMenu(reconnect = true)
        },
        onToggleHevc = {
            hevcEnabled = !hevcEnabled
            AirPlayPersistence.saveHevcEnabled(this, hevcEnabled)
            closeQuickMenu(reconnect = true)
        },
        onToggleFpsCounter = {
            val next = !AirPlayPersistence.loadFpsCounter(this)
            AirPlayPersistence.saveFpsCounter(this, next)
            if (next) fpsCounter?.start() else fpsCounter?.stop()
            rebuildQuickMenuInPlace()
        },
        onCycleNightMode = {
            carPlayNightMode = when (carPlayNightMode) {
                CarPlayNightMode.NIGHT -> CarPlayNightMode.SYSTEM
                CarPlayNightMode.SYSTEM -> CarPlayNightMode.DAY
                else -> CarPlayNightMode.NIGHT
            }
            AirPlayPersistence.saveCarPlayNightMode(this, carPlayNightMode)
            val systemNight = nightModeOrNull(resources.configuration.uiMode) ?: true
            nightModeController.configure(carPlayNightMode, systemNight, 15f, ambientDelaySeconds, nightSchedule)
            nightModeController.resume(systemNight)
            syncAirPlayDarkMode(ThemeModeDiagnostics.Source.CARPLAY_MODE)
            rebuildQuickMenuInPlace()
        },
        onReconnectNow = { closeQuickMenu(reconnect = true) },
        onDisconnectAndExit = {
            closeQuickMenu(reconnect = false)
            CarPlayBackgroundSession.stop {
                runOnUiThread { showDiPlayHome() }
            }
        },
    )

    private fun rebuildQuickMenuInPlace() {
        val current = settingsMenu ?: return
        val parent = current.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(current)
        val visible = current.visibility
        parent.removeView(current)
        val replacement = buildQuickControlMenu().apply { visibility = visible }
        settingsMenu = replacement
        parent.addView(replacement, index, FrameLayout.LayoutParams(-1, -1))
        replacement.post {
            findDescendants(replacement).firstOrNull { it.isShown && it.isFocusable }?.requestFocus()
        }
    }

    private val overlayPalette: TvPlayPalette get() = TvPlayPalette.of(true)

    private fun refreshAppAppearance() {
        (picturePanel as? CarPlayPicturePanel)?.applyPalette(overlayPalette)
    }

    private fun findDescendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(findDescendants(root.getChildAt(index)))
        }
    }

    private fun largerCanvasSupport(display: AirPlayDisplayConfig): ProjectionCanvasSupport =
        ProjectionSessionManager.evaluateLargerCanvasSupport(
            display = display,
            sessionHevc = sessionHevc,
            hevcSoftwareDecoderEnabled = hevcSoftwareDecoderEnabled,
        )

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        if (!menuOpen) oemLabel = AirPlayPersistence.loadOemLabel(this)
        val alignedSize = evenDisplaySize(size)
        val physical = resolvePhysicalSize(alignedSize)
        val knobPrimary = AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = alignedSize.width,
            heightPixels = alignedSize.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
            primaryInputDevice = if (knobPrimary) 3 else 1,
        )
        appendLog(
            "CarPlay primary input=${if (knobPrimary) "knob" else "touch"} " +
                "tv=${AndroidTvInputMode.isTelevision(this)} " +
                "touchscreen=${resources.configuration.touchscreen}",
        )
        val requestedResolutionPercent = displayScalePercent
        val requestedResolutionDisplay = CarPlayDisplayScale.applyPercent(baseDisplay, requestedResolutionPercent)
        var resolutionDisplay = requestedResolutionDisplay
        val requestedPercent = uiScalePercent
        var scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
        val candidate = scaledDisplay
        var support = when {
            uiScalePercent < CarPlayUiScale.DEFAULT && scaledDisplay === resolutionDisplay ->
                ProjectionCanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
            uiScalePercent < CarPlayUiScale.DEFAULT || scaledDisplay.widthPixels > baseDisplay.widthPixels ||
                scaledDisplay.heightPixels > baseDisplay.heightPixels -> largerCanvasSupport(scaledDisplay)
            else -> ProjectionCanvasSupport(true, "not_enlarging", "Decoder capability enlargement check not required")
        }
        var smallerUiFallback = false
        var resolutionFallback = false
        if (!support.supported && uiScalePercent < CarPlayUiScale.DEFAULT) {
            val failedCanvas = support
            smallerUiFallback = true
            uiScalePercent = CarPlayUiScale.DEFAULT
            AirPlayPersistence.saveUiScalePercent(this, uiScalePercent)
            scaledDisplay = resolutionDisplay
            appendLog("Larger CarPlay canvas unavailable reason=${failedCanvas.reason}; using Default icon and text size")
            if (displayScalePercent > 100) {
                val resolutionSupport = largerCanvasSupport(scaledDisplay)
                support = resolutionSupport.copy(
                    reason = if (resolutionSupport.supported) failedCanvas.reason else resolutionSupport.reason,
                    details = "${failedCanvas.details}\n${resolutionSupport.details}",
                )
            }
        }
        if (!support.supported && displayScalePercent > 100) {
            resolutionFallback = true
            displayScalePercent = 100
            displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
            AirPlayPersistence.saveDisplayScalePercent(this, displayScalePercent)
            AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
            resolutionDisplay = baseDisplay
            scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
            appendLog("Enlarged CarPlay resolution unavailable reason=${support.reason}; using 100%")
        }
        if (smallerUiFallback || resolutionFallback) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(
                        if (resolutionFallback) R.string.resolution_canvas_unsupported
                        else R.string.this_head_unit_cannot_use_the_smaller_size_at_this_resolut,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        val nativeMasks = blackCornerRadiusDp == CarPlayBlackCornerOverlay.IPHONE_MASK
        val disableSafeArea = mainDisplayDisableSafeArea || nativeMasks
        val display = scaledDisplay.copy(
            safeArea = if (disableSafeArea) null else AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = if (nativeMasks) false else safeAreaDrawOutside,
            mainDisplayCornerMasks = if (disableSafeArea) true else null,
            mainDisplayDisableSafeArea = disableSafeArea,
            useIphoneCornerMask = nativeMasks,
        )
        val dock = CarPlayDock.load(this).also { sessionDock = it }
        val viewAreas = CarPlayViewAreas.build(
            display.widthPixels,
            display.heightPixels,
            listOf(
                CarPlayViewAreas.Screen(
                    display.widthPixels,
                    display.heightPixels,
                    portrait = display.heightPixels > display.widthPixels,
                ),
            ),
            dock,
            splitWindow = { null },
            startPortrait = display.heightPixels > display.widthPixels,
            sidePanel = false,
            rightHandDrive = rightHandDrive,
        )
        pendingViewAreas = viewAreas
        val declared = if (viewAreas == null) display else display.copy(viewAreas = viewAreas.areas, initialViewArea = viewAreas.current)
        val requestSummary = "Display request selected=${CarPlayUiScale.label(requestedPercent)} percent=$requestedPercent " +
            "surface=${size.width}x${size.height} resolution=${requestedResolutionPercent}% " +
            "base=${requestedResolutionDisplay.widthPixels}x${requestedResolutionDisplay.heightPixels} " +
            "candidate=${candidate.widthPixels}x${candidate.heightPixels} fps=$fps " +
            "codec=${if (sessionHevc) "HEVC" else "H.264"} softwareHevc=$hevcSoftwareDecoderEnabled"
        val effectiveSummary = "Display effective percent=$uiScalePercent resolution=${displayScalePercent}% " +
            "canvas=${display.widthPixels}x${display.heightPixels} decision=${support.reason} " +
            "physical=${physical.widthMm}x${physical.heightMm}mm"
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.begin(this, requestSummary, support.details, effectiveSummary)
        appendLog(requestSummary)
        appendLog(effectiveSummary)
        val carBluetoothAudio = AirPlayPersistence.loadCarBluetoothAudio(this)
        return AirPlayConfig(
            deviceName = "TVPlay",
            deviceId = TvPlayBootstrap.deviceId(airPlayIdentity),
            btMac = TvPlayBluetooth.localAddress(this) ?: TvPlayBootstrap.deviceId(airPlayIdentity),
            sourceVersion = "950.7.1",
            main = declared,
            cluster = null,
            rightHandDrive = rightHandDrive,
            hevc = sessionHevc,
            microphone = microphoneAvailable,
            microphoneOpus = OpusEncoderSupport.isAvailable(),
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon()),
            videoInCar = true,
            mainBufferedAudio = AirPlayPersistence.loadMainBufferedAudio(this),
            speechWithout8And16Khz = AirPlayPersistence.loadSpeechWithout8And16Khz(this),
            callAtCallRate = AirPlayPersistence.loadCallAtCallRate(this),
            disableAudioOutput = carBluetoothAudio,
        )
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val (size, bytes) = AirPlayIconSizing.fit(encoded) ?: return null
        return AirPlayIcon(size, size, bytes)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun refreshDisplaySizeAfterLayout() {
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { "Apple" }

    private fun normalizedModel(): String =
        model.trim().ifBlank { "AppleTV" }

    private fun createMediaSink(
        videoWidth: Int,
        videoHeight: Int,
        controllerGeneration: Int,
    ): AndroidMediaSink {
        val diagnosticLog = sessionLog
        return AndroidMediaSink(
            surface = null,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(this),
            audioFocusAutoYield = AirPlayPersistence.loadAudioFocusAutoYield(this),
            mediaChannel = AirPlayPersistence.loadMediaAudioChannel(this),
            navigationChannel = AirPlayPersistence.loadNavigationAudioChannel(this),
            navigationAttenuationDb = AirPlayPersistence.loadNavigationAudioAttenuationDb(this),
            context = this,
            navigationStreamType = navigationStreamType,
            onScreenStreamActiveChanged = { type, active ->
                onScreenStreamStateChanged(controllerGeneration, type, active)
            },
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(this, wireless = wirelessEnabled),
            onAudioDiagnostic = { message ->
                if (message.startsWith("Microphone: ")) {
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                } else {
                    diagnosticLog?.append(formattedLogLine(message, System.currentTimeMillis()))
                }
            },
            onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,
            callEchoCancellation = AirPlayPersistence.loadCallEchoCancellation(this),
            callVoiceFilter = AirPlayPersistence.loadCallVoiceFilter(this),
            callSpeakerphone = { AirPlayPersistence.loadCallSpeakerphone(this) },
            videoPacingDelayMillis = if (smoothVideo) smoothVideoDelayMillis(fps) else 0,
            mainVideoFrameRate = fps,
            vendorLowLatencyDecoder = AirPlayPersistence.loadLowLatencyDecoder(this),
            mtkDecoderTuningEnabled = AirPlayPersistence.loadMtkDecoderTuning(this),
            navigationPromptAudioFocus = AirPlayPersistence.loadNavigationPromptAudioFocus(this),
        )
    }

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
        )

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            private val diagnosticLog = sessionLog

            override fun onCornerMask(session: AirPlaySession, png: ByteArray, displayWidth: Int) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || shuttingDown.get() ||
                        blackCornerRadiusDp != CarPlayBlackCornerOverlay.IPHONE_MASK) return@runOnUiThread
                    val active = activeAirPlaySession
                    if (active != null && active !== session) return@runOnUiThread
                    pendingIphoneCornerMask = session to (png to displayWidth)
                    if (active === session) installIphoneCornerMask(session, png, displayWidth)
                }
            }

            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) return@runOnUiThread
                    activeAirPlaySession = session
                    val pending = pendingIphoneCornerMask
                    if (pending?.first === session) {
                        installIphoneCornerMask(session, pending.second.first, pending.second.second)
                    } else clearIphoneCornerMask()
                    CarPlayBackgroundSession.active = true
                    LastConnectionProblem.clear(this@TvPlayProjectionActivity)
                    showConnectionHelp(null)
                    reconnectAttempts = 0
                    logThemeState(ThemeModeDiagnostics.Source.SESSION_ACTIVE, resources.configuration)
                    syncAirPlayDarkMode(ThemeModeDiagnostics.Source.SESSION_ACTIVE)
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                }
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || activeAirPlaySession !== session || shuttingDown.get()) return@runOnUiThread
                    val firstFrame = startupRetryBudget.firstFrame(session, SystemClock.elapsedRealtime())
                    if (!firstFrame) return@runOnUiThread
                    mainHandler.postDelayed({
                        if (controllerGeneration == restartGeneration && activeAirPlaySession === session &&
                            !shuttingDown.get() && CarPlayBackgroundSession.isOwner(this@TvPlayProjectionActivity) &&
                            startupRetryBudget.resetIfStable(session, SystemClock.elapsedRealtime())) {
                            appendLog("wireless startup retry budget reset after stable video session")
                        }
                    }, WirelessStartupPolicy.STABLE_SESSION_MILLIS)
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    val active = activeAirPlaySession
                    if (active != null && active !== session) {
                        appendLog("Non-active AirPlay connection ended; keeping the live session")
                        return@runOnUiThread
                    }
                    if (controllerGeneration != restartGeneration || startupRetryStopped) return@runOnUiThread
                    startupRetryBudget.disconnected()
                    activeAirPlaySession = null
                    clearIphoneCornerMask()
                    CarPlayBackgroundSession.active = false
                    if (menuOpen) {
                        recoveryPendingAfterMenu = true
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    setConnectionStage(R.string.carplay_session_ended_reconnecting)
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || startupRetryStopped) return@runOnUiThread
                    startupRetryBudget.disconnected()
                    if (menuOpen) {
                        recoveryPendingAfterMenu = true
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    setConnectionStage(R.string.transport_error_reconnecting)
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override val wantsTrace: Boolean get() = false

            override fun onDebugLog(message: String) {
                if (DiagnosticRedactor.redact(message) == null) return
                if (message.startsWith(CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX + " ")) {
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                    return
                }
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) return@runOnUiThread
                    DisplayDiagnosticSnapshot.record(this@TvPlayProjectionActivity, displayDiagnosticAttempt, message)
                    if (menuOpen) return@runOnUiThread
                    appendLog(message)
                }
            }
        }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = report@{ status ->
        if (controllerGeneration != restartGeneration) return@report
        if (status is CarPlayStatus.RunningControl || status is CarPlayStatus.RunningWireless) {
            controlRunningCount++
            lossSinceControlRunning = false
        } else if (status is CarPlayStatus.Failed || status is CarPlayStatus.ControlEnded) {
            lossSinceControlRunning = true
        }
        if (menuOpen) {
            if (status is CarPlayStatus.Failed) failurePendingAfterMenu = status
            return@report
        }
        when (status) {
            CarPlayStatus.StartingHotspot -> CarPlayBackgroundSession.hotspotReady = null
            is CarPlayStatus.HotspotReady -> CarPlayBackgroundSession.hotspotReady = status
            else -> Unit
        }
        val description = status.describe()
        val englishDescription = status.describe(englishResources)
        val knownCause = (status as? CarPlayStatus.Failed)?.knownCause
        if (!startupRetryStopped || status !is CarPlayStatus.Failed) {
            if (knownCause != null) setConnectionStage(ConnectionFailureGuidance.stage(knownCause))
            else setConnectionStage(description, englishDescription)
            LastConnectionProblem.follow(this, status)
        }
        connectionInstructionsView?.text = connectionInstructions(status)
        if (status is CarPlayStatus.Failed) showConnectionHelp(status)
        when (status) {
            is CarPlayStatus.Failed -> if (status.wifiResetRequired) {
                wifiRecoveryButton?.visibility = View.VISIBLE
            } else {
                wifiRecoveryButton?.visibility = View.GONE
                if (status.startupFailure != null) {
                    if (startupFailureGeneration == controllerGeneration) return@report
                    startupFailureGeneration = controllerGeneration
                }
                reconnectAfterLoss(description, status.startupFailure, englishDescription, status.knownCause)
            }
            else -> Unit
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        if (!backgroundSessionMatchesView(snapshot.sink.videoPacingEnabled, smoothVideo)) {
            appendLog("Background session smooth video differs from this view; stopping it")
            CarPlayBackgroundSession.stop { mainHandler.post { if (!isDestroyed) maybeStartCarPlay() } }
            return false
        }
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.currentAttempt(this)
        controller = snapshot.controller
        sink = snapshot.sink
        sessionDisplay = snapshot.display
        CarPlayBackgroundSession.store(
            snapshot.controller,
            snapshot.sink,
            snapshot.width,
            snapshot.height,
            this,
            snapshot.display,
        ) { completion ->
            runOnUiThread {
                shutdown(false, "TVPlay disconnect", completion)
                finish()
            }
        }
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
        }
        videoView?.let { updateVideoLayout(it.width, it.height) }
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) {
                scheduleDisplaySize(view.width, view.height)
            }
        }

        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        activeAirPlaySession = snapshot.controller.activeAirPlaySession()
        if (blackCornerRadiusDp == CarPlayBlackCornerOverlay.IPHONE_MASK) {
            snapshot.controller.activeIphoneCornerMask()?.let { (session, png, width) ->
                activeAirPlaySession = session
                installIphoneCornerMask(session, png, width)
            }
        }
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(restartGeneration, type, active)
        }
        currentSurface?.let(::attachSurface)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        setConnectionStage(
            if (serviceReused) R.string.carplay_service_already_running
            else R.string.carplay_session_already_running,
        )
        updateDebugOverlays()
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || controller != null) return
        val controllerGeneration = restartGeneration
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        runCatching { TvPlayBootstrap.ensure(this, mfiTarget) }
        val config = createRuntimeConfig()
        val effectiveSize = if (isMultiWindowActive() && !AirPlayPersistence.loadAdaptPipResolution(this) &&
            maximumDetectedWidthPixels >= size.width && maximumDetectedHeightPixels >= size.height &&
            (maximumDetectedWidthPixels > size.width || maximumDetectedHeightPixels > size.height)) {
            DisplaySize(maximumDetectedWidthPixels, maximumDetectedHeightPixels)
        } else {
            size
        }
        val airPlayConfig = createAirPlayConfig(effectiveSize)
        appendLog(
            "Starting TVPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${displayScalePercent}%) " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "microphone=${airPlayConfig.microphone}",
        )
        val renderer = createMediaSink(
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            controllerGeneration = controllerGeneration,
        )
        sink = renderer
        currentSurface?.let(::attachSurface)
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
        )
        controller = next
        CarPlayMediaKeys.attach(this, next)
        if (airPlayConfig.videoInCar) CarPlayVideo.attach(this, next)
        val display = CarPlaySessionDisplay(
            airPlayConfig.main.widthPixels,
            airPlayConfig.main.heightPixels,
            displayRotation(),
            hideTopBar,
            hideBottomBar,
            effectiveSize.width,
            effectiveSize.height,
            viewAreas = pendingViewAreas,
            statusBarShown = statusBarShown(),
        )
        sessionDisplay = display
        videoView?.let { updateVideoLayout(it.width, it.height) }
        CarPlayBackgroundSession.store(next, renderer, size.width, size.height, this, display) { completion ->
            runOnUiThread {
                shutdown(terminateProcess = false, reason = "TVPlay disconnect", completion = completion)
                finish()
            }
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, TvPlaySessionService::class.java))
            next.start()
        } catch (error: RuntimeException) {
            appendLog("Connection could not start: ${error.javaClass.simpleName}")
            shutdown(false, "foreground service could not start")
            setConnectionStage(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per)
        }
    }

    private fun refreshConfiguration(
        newConfig: Configuration = resources.configuration,
        source: ThemeModeDiagnostics.Source,
    ) {
        if (lastConfiguration == newConfig) {
            logThemeState(source, newConfig)
            return
        }
        lastConfiguration = Configuration(newConfig)
        nightModeDiagnosticSource = source
        try {
            nightModeOrNull(newConfig.uiMode)?.let { nightModeController.systemChanged(it) }
        } finally {
            nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
        }
        logThemeState(source, newConfig)
    }

    private fun logThemeState(source: ThemeModeDiagnostics.Source, configuration: Configuration) {
        themeDiagnostics.observe(
            source, configuration.uiMode, darkMode, activeAirPlaySession != null, SystemClock.elapsedRealtime(),
        )?.let(::appendLog)
    }

    private fun syncAirPlayDarkMode(source: ThemeModeDiagnostics.Source) {
        val session = activeAirPlaySession
        val night = darkMode
        if (session == null) {
            appendLog("THEME_DIAGNOSTIC request source=${source.label} applied=${if (night) "dark" else "light"} sessionActive=false")
            return
        }
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                appendLog(
                    "THEME_DIAGNOSTIC send source=${source.label} applied=${if (night) "dark" else "light"} commandWritten=$sent",
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        if (!File(filesDir, AUDIO_CAPTURE_MARKER).isFile) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == pendingDisplaySize) return
        mainHandler.removeCallbacks(applyDisplaySize)
        if (size == activeDisplaySize && !displayLayoutChanged()) {
            pendingDisplaySize = null
            maybeStartCarPlay()
            return
        }
        pendingDisplaySize = size
        mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun isMultiWindowActive(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode

    private fun statusBarShown(): Boolean? =
        ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars())

    private fun applyDisplaySize(size: DisplaySize) {
        val display = sessionDisplay
        val layoutChanged = displayLayoutChanged(size)
        if (shuttingDown.get()) return
        if (size == activeDisplaySize && !layoutChanged) {
            maybeStartCarPlay()
            return
        }
        val previous = activeDisplaySize
        activeDisplaySize = size
        recordDetectedMaximum(size)
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || handshakeResetInProgress) {
            appendLog("Display updated while handshake is reset: ${previous.width}x${previous.height} -> ${size.width}x${size.height}")
        } else if (controller == null && display == null) {
            maybeStartCarPlay()
        } else if (display != null && !layoutChanged &&
            statusBarHiddenByAnotherWindow(display, size.width, size.height, statusBarShown())) {
            appendLog("Status bar hidden ${previous.width}x${previous.height} -> ${size.width}x${size.height}; keeping CarPlay session")
            videoView?.let { updateVideoLayout(it.width, it.height) }
        } else if (display != null && !layoutChanged &&
            size.width <= display.windowWidth && size.height <= display.windowHeight) {
            appendLog("Display resized ${previous.width}x${previous.height} -> ${size.width}x${size.height}; keeping CarPlay session")
            videoView?.let { updateVideoLayout(it.width, it.height) }
        } else {
            restartCarPlay("Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}")
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = videoView?.display?.rotation ?: windowManager.defaultDisplay.rotation

    private fun displayLayoutChanged(newSize: DisplaySize? = activeDisplaySize): Boolean {
        val display = sessionDisplay ?: return false
        if (display.rotation != displayRotation()) return true
        if (display.hideTopBar != hideTopBar || display.hideBottomBar != hideBottomBar) return true
        if (newSize != null && newSize.width > 0 && newSize.height > 0 &&
            display.windowWidth > 0 && display.windowHeight > 0) {
            val baseAspect = display.windowWidth.toDouble() / display.windowHeight
            val currentAspect = newSize.width.toDouble() / newSize.height
            val aspectDiff = abs(currentAspect / baseAspect - 1.0)
            if (aspectDiff > 0.08 && AirPlayPersistence.loadAdaptPipResolution(this)) {
                return true
            }
        }
        return false
    }

    private fun contentRect(viewWidth: Int, viewHeight: Int): CarPlayVideoLayout =
        VideoSurfaceController.computeContentRect(sessionDisplay, viewWidth, viewHeight)

    private fun updateVideoLayout(viewWidth: Int, viewHeight: Int) {
        fallbackVideoBounds = VideoSurfaceController.applyVideoLayout(
            videoView = videoView,
            fallbackVideoView = fallbackVideoView,
            currentFallbackBounds = fallbackVideoBounds,
            blackCornerOverlay = blackCornerOverlay,
            display = sessionDisplay,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
        )
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (shuttingDown.get()) return
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) {
            if (!adoptBackgroundSession()) mainHandler.postDelayed({ maybeStartCarPlay() }, 500)
            return
        }
        if (controller == null && adoptBackgroundSession()) return
        if (controller == null && (AirPlayPersistence.loadSmoothVideo(this) != smoothVideo ||
                AirPlayPersistence.loadDirectVideoOutput(this) != directVideoOutput)) {
            if (CarPlayBackgroundSession.isOwner(this)) CarPlayBackgroundSession.clear()
            recreate()
            return
        }
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        if (
            !transportReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            menuOpen ||
            handshakeResetInProgress ||
            pendingDisplaySize != null ||
            controller != null
        ) {
            return
        }
        startCarPlay(size)
    }

    private fun reconnectAfterLoss(
        reason: String,
        startupFailure: WirelessStartupFailure? = null,
        englishReason: String = reason,
        knownCause: ConnectionFailureCause? = null,
    ) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        lossSinceControlRunning = true
        if (menuOpen) recoveryPendingAfterMenu = true
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || startupRetryStopped) return
        if (reconnectScheduled) return
        val startupDelay = if (startupFailure != null && startupFailure.retryable)
            startupRetryBudget.nextDelayMillis() else null
        if (startupFailure != null && startupDelay == null) {
            startupRetryStopped = true
            startupRetryButton?.visibility = View.VISIBLE
            appendLog("$englishReason; wireless startup recovery stopped retries=${startupRetryBudget.retries} generation=$restartGeneration startupFailure=${startupFailure.name}")
            if (knownCause != null) {
                setConnectionStage(
                    "${getString(ConnectionFailureGuidance.stage(knownCause))}\n" +
                        getString(R.string.wireless_startup_retries_exhausted),
                )
            } else if (startupFailure == WirelessStartupFailure.HOTSPOT_CONFIGURATION) {
                setConnectionStage(
                    "${getString(R.string.hotspot_network_not_ready)}\n" +
                        getString(R.string.wireless_startup_retries_exhausted),
                )
            } else if (!startupFailure.retryable) {
                setConnectionStage(reason, englishReason)
            } else {
                setConnectionStage(
                    "$reason\n${getString(R.string.wireless_startup_retries_exhausted)}",
                    "$englishReason\n${englishResources.getString(R.string.wireless_startup_retries_exhausted)}",
                )
            }
            return
        }
        reconnectScheduled = true
        val generation = restartGeneration
        val delayMillis = if (startupDelay != null) {
            startupDelay
        } else if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            (RECONNECT_DELAY_MILLIS * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
        }
        reconnectAttempts += 1
        appendLog("$englishReason; retrying in ${delayMillis}ms generation=$generation startupFailure=$startupFailure")
        val controlRunsAtFailure = controlRunningCount
        val retry = Runnable {
            reconnectScheduled = false
            pendingReconnect = null
            reconnectWaitsForBluetooth = false
            if (menuOpen && generation == restartGeneration) recoveryPendingAfterMenu = true
            if (
                shuttingDown.get() ||
                menuOpen ||
                handshakeResetInProgress || startupRetryStopped ||
                generation != restartGeneration
            ) {
                return@Runnable
            }
            if (controlRunningCount != controlRunsAtFailure && !lossSinceControlRunning) {
                return@Runnable
            }
            restartCarPlay("Reconnecting after $reason")
        }
        pendingReconnect = retry
        reconnectWaitsForBluetooth = englishReason.contains(BluetoothRadioCheck.RADIO_OFF_MARK, true)
        mainHandler.postDelayed(retry, delayMillis)
    }

    private fun restartCarPlay(reason: String) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        startupRetryBudget.disconnected()
        startupRetryButton?.visibility = View.GONE
        appendLog(reason)
        activeScreenStreamTypes.clear()
        setConnectionStage(reason)
        val generation = ++restartGeneration
        handshakeResetInProgress = true
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController, keepOwner = true)
        controller = null
        oldSink?.let(retiringSinks::add)
        sink = null
        sessionDisplay = null
        clearIphoneCornerMask()
        discardLastPicture()
        val diagnosticLog = sessionLog
        teardownExecutor.execute {
            val started = System.nanoTime()
            oldController?.close()
            val completed = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            AsyncDiagnosticLog.append(
                diagnosticLog,
                "${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} generation=$generation " +
                    "restart teardownWaitCompleted=$completed " +
                    "elapsedMs=${((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0)}",
            )
            oldSink?.let(::closeRetiringSink)
            runOnUiThread {
                if (!shuttingDown.get() && generation == restartGeneration) {
                    handshakeResetInProgress = false
                    maybeStartCarPlay()
                }
            }
        }
    }

    private fun showDiPlayHome(page: String = "home") {
        releaseCarPlayTouches()
        OwnScreenStart.start(
            this,
            Intent(this, TvPlayWorkbenchActivity::class.java)
                .putExtra("page", page)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            ::appendLog,
        )
    }

    private fun closeRetiringSink(owner: AndroidMediaSink) {
        owner.close()
        val owners = retiringSinks
        owner.whenVideoReleased { owners.remove(owner) }
        owner.awaitVideoReleased(sinkReleaseWaitMillis)
    }

    private fun openSettingsMenu() {
        cancelTemporarySystemBars()
        if (menuOpen) return
        releaseCarPlayTouches()
        loadPersistedSettings()
        rebuildQuickMenuInPlace()
        menuOpen = true
        gestureOverlay?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        updateDebugOverlays()
        settingsMenu?.post {
            val menu = settingsMenu ?: return@post
            if (menu.findFocus() == null) {
                findDescendants(menu).firstOrNull { it.isShown && it.isFocusable }?.requestFocus()
            }
        }
    }

    private fun closeQuickMenu(reconnect: Boolean) {
        if (!menuOpen) return
        menuOpen = false
        settingsMenu?.visibility = View.GONE
        gestureOverlay?.visibility = View.VISIBLE
        updateDebugOverlays()
        val failure = failurePendingAfterMenu
        failurePendingAfterMenu = null
        val recoveryPending = recoveryPendingAfterMenu || failure != null
        recoveryPendingAfterMenu = false
        if (reconnect) {
            startupRetryBudget.manualRetry()
            startupRetryStopped = false
        }
        if (failure?.startupFailure != null && !reconnect) {
            createStatusReporter(restartGeneration)(failure)
        } else if (failure?.wifiResetRequired == true && (!reconnect || wirelessEnabled)) {
            createStatusReporter(restartGeneration)(failure)
        } else if (handshakeResetInProgress) {
            startAfterHandshakeReset = true
        } else if ((reconnect || recoveryPending) && controller != null) {
            restartCarPlay(if (reconnect) "Settings saved; reconnecting" else "Connection lost while menu open; reconnecting")
        } else {
            maybeStartCarPlay()
        }
    }

    private fun openPicturePanel() {
        closePicturePanel()
        val generation = picturePanelGeneration
        val root = videoView?.parent as? FrameLayout ?: return
        releaseCarPlayTouches()
        root.post {
            if (generation != picturePanelGeneration || isFinishing || isDestroyed) return@post
            val panel = CarPlayPicturePanel(
                this,
                adjustmentsAvailable = fallbackVideoView == null,
                initialPalette = overlayPalette,
                close = ::closePicturePanel,
            )
            val availableWidth = (root.width - dp(24)).coerceAtLeast(1)
            val width = minOf(dp(420), if (root.width < dp(800)) availableWidth else (root.width * 0.42f).toInt())
            val height = minOf(dp(540), root.height - dp(24)).coerceAtLeast(1)
            root.addView(panel, FrameLayout.LayoutParams(width, height, Gravity.END or Gravity.TOP).apply {
                topMargin = dp(12); marginEnd = dp(12)
            })
            picturePanel = panel
        }
    }

    private fun closePicturePanel() {
        ++picturePanelGeneration
        picturePanel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        picturePanel = null
        CarPlayPicture.showOriginal(false)
    }

    private fun shutdown(terminateProcess: Boolean, reason: String, completion: () -> Unit = {}) {
        if (!shuttingDown.compareAndSet(false, true)) { completion(); return }
        startupRetryBudget.disconnected()
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        oldSink?.let(retiringSinks::add)
        sink = null
        sessionDisplay = null
        discardLastPicture()
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            oldController?.close()
            oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            oldSink?.let(::closeRetiringSink)
            airPlayCommandExecutor.shutdown()
            if (terminateProcess) {
                applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java))
            }
            applicationContext.stopService(Intent(applicationContext, TvPlaySessionService::class.java))
            teardownExecutor.shutdown()
            mainHandler.post { completion() }
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun observeVideoWindow(texture: TextureView) {
        val probe = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!texture.isAttachedToWindow) return true
                removeVideoSurfaceProbe()
                if (isDestroyed || videoView !== texture) return true
                val unsafeDecoder = textureUnsafeVideoDecoder()
                val mode = carPlayVideoSurfaceMode(
                    texture.isHardwareAccelerated,
                    smoothVideo,
                    directVideoOutput,
                    textureUnsafeDecoder = unsafeDecoder != null,
                )
                if (mode == CarPlayVideoSurfaceMode.TEXTURE) return true
                useFallbackVideoSurface(texture, unsafeDecoder)
                return false
            }
        }
        videoSurfaceProbe = probe
        texture.viewTreeObserver.addOnPreDrawListener(probe)
    }

    private fun textureUnsafeVideoDecoder(): String? =
        VideoSurfaceController.findTextureUnsafeVideoDecoder(hevcEnabled)

    private fun removeVideoSurfaceProbe() {
        val probe = videoSurfaceProbe ?: return
        videoView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(probe)
        videoSurfaceProbe = null
    }

    private fun useFallbackVideoSurface(texture: TextureView, unsafeDecoder: String?) {
        val root = texture.parent as? FrameLayout ?: return
        val index = root.indexOfChild(texture)
        val viewport = FrameLayout(this).apply { clipChildren = true }
        val surfaceView = SurfaceView(this)
        pictureBinding?.close()
        pictureBinding = null
        videoSurfaceOwner.clear()
        currentSurfaceTexture = null
        texture.surfaceTextureListener = null
        root.removeView(texture)
        videoView = viewport
        fallbackVideoView = surfaceView
        surfaceView.holder.addCallback(fallbackSurfaceCallback)
        viewport.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        viewport.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            updateVideoLayout(width, height)
            if (width != oldRight - oldLeft || height != oldBottom - oldTop) {
                scheduleDisplaySize(width, height)
            }
        }
        root.addView(viewport, index, texture.layoutParams)
        appendLog("Using SurfaceView video output unsafeDecoder=$unsafeDecoder")
    }

    private fun captureLastPicture() {
        val surfaceView = fallbackVideoView ?: return
        val bounds = fallbackVideoBounds ?: return
        if (!surfaceView.holder.surface.isValid) return
        VideoSurfaceController.captureLastSurfacePicture(
            surfaceView = surfaceView,
            bounds = bounds,
            copyLooper = pictureCopyThread.looper,
            waitMillis = PICTURE_COPY_WAIT_MILLIS,
        )?.let { lastPicture = it }
    }

    private fun showLastPicture() {
        if (sink == null) return
        val picture = lastPicture ?: return
        val surfaceView = fallbackVideoView ?: return
        val viewport = surfaceView.parent as? FrameLayout ?: return
        val hold = pictureHold?.takeIf { it.parent === viewport } ?: ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
            isFocusable = false
        }.also {
            pictureHold?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }
            pictureHold = it
            viewport.addView(it)
        }
        (surfaceView.layoutParams as? FrameLayout.LayoutParams)?.let { hold.layoutParams = FrameLayout.LayoutParams(it) }
        hold.animate().cancel()
        hold.setImageBitmap(picture)
        hold.alpha = 1f
        hold.visibility = View.VISIBLE
        pictureHoldBaseline = sink?.liveVideoCounters()?.rendered ?: -1L
        pictureHoldStartMillis = SystemClock.uptimeMillis()
        mainHandler.removeCallbacks(pictureHoldTick)
        mainHandler.postDelayed(pictureHoldTick, 50L)
    }

    private fun discardLastPicture() {
        mainHandler.removeCallbacks(pictureHoldTick)
        mainHandler.removeCallbacks(pictureRetry)
        pictureHold?.let { hold ->
            hold.animate().cancel()
            hold.visibility = View.GONE
            hold.setImageDrawable(null)
        }
        lastPicture = null
    }

    private fun attachSurface(surface: Surface) {
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        sink?.setSurface(SCREEN_TYPE_ALT, surface)
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (gestureFingerCount > 0 && event.pointerCount == gestureFingerCount && !gestureSequenceActive) {
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    releaseCarPlayTouches()
                    return true
                }
            }
        }

        if (gestureSequenceActive) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.pointerCount != gestureFingerCount) {
                gestureTracking = false
            }
            if (!gestureTracking || event.pointerCount != gestureFingerCount) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                if (
                    abs(deltaY) >= dp(SETTINGS_SWIPE_DISTANCE_DP) &&
                    abs(deltaY) >= deltaX * SETTINGS_SWIPE_DIRECTION_RATIO
                ) {
                    gestureTracking = false
                    if (deltaY > 0) {
                        gestureSequenceActive = false
                        openSettingsMenu()
                    }
                    return true
                }
            }
            return true
        }

        val content = contentRect(view.width, view.height)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOutsideContent = !content.contains(event.x, event.y)
        }
        if (touchOutsideContent) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchOutsideContent = false
            }
            return true
        }
        if (gestureFingerCount > 0 && holdTouch(event, content)) return true
        flushHeldTouch()
        val contacts = CarPlayTouchMapper.contacts(event, content)
        controller?.sendTouch(contacts)
        return true
    }

    private fun releaseCarPlayTouches() {
        dropHeldTouch()
        controller?.sendTouch(emptyList())
    }

    private fun holdTouch(event: MotionEvent, content: CarPlayVideoLayout): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dropHeldTouch()
                heldTouch = MotionEvent.obtain(event)
                heldTouchContent = content
                heldTouchX = event.x
                heldTouchY = event.y
                mainHandler.postDelayed(sendHeldTouch, SETTINGS_SWIPE_HOLD_MILLIS)
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (heldTouch != null) {
                dropHeldTouch()
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                val held = heldTouch ?: return false
                val moved = hypot((event.getX(0) - heldTouchX).toDouble(), (event.getY(0) - heldTouchY).toDouble())
                if (moved > ViewConfiguration.get(this).scaledTouchSlop) return false
                held.recycle()
                heldTouch = MotionEvent.obtain(event)
                return true
            }
        }
        return false
    }

    private fun flushHeldTouch() {
        mainHandler.removeCallbacks(sendHeldTouch)
        val held = heldTouch ?: return
        val content = heldTouchContent
        heldTouch = null
        heldTouchContent = null
        if (content != null) controller?.sendTouch(CarPlayTouchMapper.contacts(held, content))
        held.recycle()
    }

    private fun dropHeldTouch() {
        mainHandler.removeCallbacks(sendHeldTouch)
        heldTouch?.recycle()
        heldTouch = null
        heldTouchContent = null
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (type == SCREEN_TYPE_MAIN && !active) clearIphoneCornerMask()
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
            }
            if (type == SCREEN_TYPE_MAIN) {
                noPictureShown = false
                mainHandler.removeCallbacks(noPictureCheck)
                if (active) mainHandler.postDelayed(noPictureCheck, NO_PICTURE_MILLIS)
            }
            updateDebugOverlays()
        }
    }

    private fun setStatus(stringId: Int) {
        val message = getString(stringId)
        val englishMessage = englishResources.getString(stringId)
        runOnUiThread {
            setConnectionStage(message, englishMessage)
            appendLog(message)
        }
    }

    private fun setConnectionStage(stringId: Int) =
        setConnectionStage(getString(stringId), englishResources.getString(stringId))

    private fun connectionInstructions(status: CarPlayStatus?): String =
        ProjectionWaitingView.connectionInstructions(
            context = this,
            status = status,
            wirelessEnabled = wirelessEnabled,
            wirelessHotspotMode = wirelessHotspotMode,
            existingWifiSsid = existingWifiSsid,
        )

    private fun showConnectionHelp(failure: CarPlayStatus.Failed?) {
        connectionInstructionsView?.visibility = if (failure == null) View.VISIBLE else View.GONE
        connectionHelpView?.apply {
            visibility = if (failure == null) View.GONE else View.VISIBLE
            if (failure != null) text = getString(ConnectionFailureGuidance.message(failure, wirelessEnabled, wirelessHotspotMode))
        }
    }

    private fun setConnectionStage(message: String, englishMessage: String = message) {
        latestStage = message
        stageStatusView?.text = friendlyStage(message, englishMessage)
        updateDebugOverlays()
    }

    private fun updateDebugOverlays() {
        val noPicture = noPictureShown && SCREEN_TYPE_MAIN in activeScreenStreamTypes
        if (noPicture) stageStatusView?.text = getString(R.string.iphone_sent_no_picture)
        val waitingVisible = activeScreenStreamTypes.isEmpty() || noPicture
        connectionPanel?.visibility = if (waitingVisible) View.VISIBLE else View.GONE
        if (waitingVisible && !menuOpen) {
            connectionPanel?.post {
                val panel = connectionPanel ?: return@post
                if (panel.findFocus() == null) {
                    findDescendants(panel).firstOrNull { it.isShown && it.isFocusable }?.requestFocus()
                }
            }
        }
    }

    private fun friendlyStage(message: String, englishMessage: String = message): String =
        ProjectionWaitingView.friendlyStage(this, message, englishMessage)

    private fun appendLog(message: String) {
        Log.i(TAG, message)
        appendFileLog(message)
    }

    private fun appendFileLog(message: String) {
        sessionLog?.append(formattedLogLine(message, System.currentTimeMillis()))
    }

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val logFile = File(File(filesDir, "logs"), "diplay.log")
        val activeLog = SessionLogFile(logFile)
        runCatching {
            activeLog.reset(
                "TVPlay log started " +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "pid=${Process.myPid()} path=${logFile.absolutePath}",
            )
        }
        sessionLog = activeLog
    }

    private fun applyFullscreenMode() {
        val multiWindow = isMultiWindowActive()
        val hideTop = hideTopBar && !multiWindow
        val hideBottom = hideBottomBar && !multiWindow
        WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val peek = temporarySystemBarsVisible && !multiWindow
        if (hideTop && !peek) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom && !peek) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showTemporarySystemBars(): Boolean {
        if (!temporarySystemBarsEnabled || (!hideTopBar && !hideBottomBar) ||
            gestureFingerCount <= 0 || isMultiWindowActive()) {
            return false
        }
        mainHandler.removeCallbacks(hideTemporarySystemBars)
        temporarySystemBarsVisible = true
        applyFullscreenMode()
        mainHandler.postDelayed(hideTemporarySystemBars, 5_000L)
        return true
    }

    private fun cancelTemporarySystemBars() {
        mainHandler.removeCallbacks(hideTemporarySystemBars)
        if (temporarySystemBarsVisible) {
            temporarySystemBarsVisible = false
            applyFullscreenMode()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun CarPlayStatus.describe(res: Resources = resources): String =
        ProjectionWaitingView.describeStatus(
            status = this,
            res = res,
            wirelessEnabled = wirelessEnabled,
            wirelessHotspotMode = wirelessHotspotMode,
        )

    private companion object {
        const val PICTURE_HOLD_MAX_MILLIS = 30_000L
        const val PICTURE_COPY_WAIT_MILLIS = 150L
        const val TAG = "xcertplay-usb"
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val SINK_RELEASE_WAIT_MILLIS = 2_000L
        private const val NO_PICTURE_MILLIS = 10_000L
        private const val NO_PICTURE_RECHECK_MILLIS = 250L
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val CONFIGURATION_POLL_INTERVAL_MILLIS = 2_000L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val AUDIO_CAPTURE_MARKER = "audio-capture.enabled"
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val SETTINGS_SWIPE_DISTANCE_DP = 72
        const val SETTINGS_SWIPE_DIRECTION_RATIO = 1.15f
        const val SETTINGS_SWIPE_HOLD_MILLIS = 80L
    }

    private data class DisplaySize(val width: Int, val height: Int)

    private fun evenDisplaySize(size: DisplaySize) =
        DisplaySize((size.width / 2 * 2).coerceAtLeast(2), (size.height / 2 * 2).coerceAtLeast(2))
}

internal data class CarPlaySessionDisplay(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hideTopBar: Boolean,
    val hideBottomBar: Boolean,
    val windowWidth: Int,
    val windowHeight: Int,
    val viewAreas: CarPlayViewAreas? = null,
    val statusBarShown: Boolean? = null,
)

internal fun statusBarHiddenByAnotherWindow(
    display: CarPlaySessionDisplay,
    width: Int,
    height: Int,
    statusBarShown: Boolean?,
): Boolean =
    !display.hideTopBar && display.statusBarShown == true && statusBarShown == false &&
        width == display.windowWidth && height > display.windowHeight

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
internal object CarPlayBackgroundSession {
    @Volatile var active = false
    @Volatile var callPopupReady = false
    @Volatile var hotspotReady: CarPlayStatus.HotspotReady? = null
    private var stopAction: (((() -> Unit)) -> Unit)? = null
    private var stopping = false
    private var owner: Any? = null
    @Synchronized fun isOwner(candidate: Any): Boolean = owner === candidate
    @Synchronized fun hasSession(): Boolean = stopAction != null || stopping
    @Synchronized fun currentController(): CarPlayController? = controller
    private val stopWaiters = mutableListOf<() -> Unit>()

    fun stop(completion: () -> Unit = {}) {
        val action: (((() -> Unit)) -> Unit)?
        synchronized(this) {
            if (stopping) { stopWaiters.add(completion); return }
            action = stopAction
            if (action != null) { stopping = true; stopWaiters.add(completion) }
        }
        if (action == null) { completion(); return }
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
        if (this.controller !== controller) {
            callPopupReady = false
            hotspotReady = null
        }
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
        if (!keepOwner) { stopAction = null; owner = null }
        active = false
        callPopupReady = false
        hotspotReady = null
        width = 0
        height = 0
        display = null
    }
}
