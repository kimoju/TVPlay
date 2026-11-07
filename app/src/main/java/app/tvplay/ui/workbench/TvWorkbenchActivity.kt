package app.tvplay.ui.workbench

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.setPadding
import app.tvplay.R
import app.tvplay.config.TvPlayBluetooth
import app.tvplay.config.TvSettingsRepository
import app.tvplay.config.WiredPermissions
import app.tvplay.config.WirelessPermissions
import app.tvplay.engine.network.BluetoothRadioCheck
import app.tvplay.engine.network.LanNetworkLink
import app.tvplay.engine.network.UsbTunnelVpnService
import app.tvplay.engine.network.WifiDirectGroupLink
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.runtime.CarPlayBackgroundSession
import app.tvplay.runtime.DiagnosticExportStore
import app.tvplay.runtime.LanOtaCheckResult
import app.tvplay.runtime.LanOtaProtocol
import app.tvplay.runtime.LastConnectionProblem
import app.tvplay.runtime.PendingReconnect
import app.tvplay.runtime.ThemeModeDiagnostics
import app.tvplay.runtime.TvBluetoothSinkGuard
import app.tvplay.runtime.TvDiagnosticsLogger
import app.tvplay.runtime.TvLanOtaManager
import app.tvplay.ui.stage.CarPlayScreen
import app.tvplay.ui.theme.AppAppearanceOwner
import app.tvplay.ui.theme.AppAppearanceRuntime
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.ui.theme.TvGlassProgressBar
import app.tvplay.ui.theme.TvIconGlyph
import app.tvplay.ui.theme.TvModalDialog
import app.tvplay.ui.theme.TvPlayButtonKind
import app.tvplay.ui.theme.resolveAppNightNow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

typealias TvPlayWorkbenchActivity = TvWorkbenchActivity

/**
 * Primary 10-foot Android TV setup & control workbench for TVPlay.
 *
 * Uses an Apple TV / Android TV dual-pane layout:
 * - Left pane ([WorkbenchHeroStage]): Live receiver stage, wireless/USB mode switcher, 3-step connection guide, and primary Launch CTA.
 * - Right pane ([WorkbenchSettingsTabs]): Tabbed D-pad-friendly settings (`CONNECTION`, `DISPLAY`, `AUDIO`, `ABOUT`).
 */
open class TvWorkbenchActivity : Activity(), AppAppearanceOwner {
    var appNight: Boolean = true
        private set

    override val currentAppNight: Boolean
        get() = appNight

    private lateinit var settings: TvSettingsRepository
    private lateinit var stagePane: WorkbenchHeroStage
    private lateinit var settingsPane: WorkbenchSettingsTabs
    private var pendingWirelessLaunch: Boolean = true
    private var pendingChoosePhoneLaunchAfterSelect: Boolean = false
    private val themeModeDiagnostics = ThemeModeDiagnostics()
    private var activeDialog: Dialog? = null
    private var bluetoothSinkReceiverRegistered: Boolean = false
    private val bluetoothSinkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            if (TvBluetoothSinkGuard.isSinkProfileAction(action) ||
                action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                action == BluetoothDevice.ACTION_BOND_STATE_CHANGED
            ) {
                TvBluetoothSinkGuard.onBluetoothEvent(this@TvWorkbenchActivity, device, action, intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = TvSettingsRepository(this)
        appNight = resolveAppNightNow()
        AppAppearanceRuntime.publishHost(this, appNight)
        setTheme(if (appNight) R.style.Theme_TVPlay else R.style.Theme_TVPlay_Light)
        super.onCreate(savedInstanceState)

        runCatching {
            registerReceiver(bluetoothSinkReceiver, TvBluetoothSinkGuard.buildIntentFilter())
            bluetoothSinkReceiverRegistered = true
        }
        observeAppearance(ThemeModeDiagnostics.Source.CREATE)
        buildWorkbenchLayout()
        TvLanOtaManager.reportPendingUpgradeSuccessAsync(this)

        if (savedInstanceState == null && isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession()) {
            startActivity(CarPlayScreen.intent(this))
            finish()
            return
        }

        if (savedInstanceState == null && shouldAutoLaunchProjection(intent)) {
            val wireless = intent.getBooleanExtra("wireless", settings.connection.wirelessEnabled)
            intent.removeExtra("wireless")
            intent.removeExtra("auto_connect_token")
            launchProjectionStage(wireless)
            return
        }

        if (savedInstanceState == null && settings.identity.otaAutoCheck) {
            checkOtaUpdate(manual = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession()) {
            startActivity(CarPlayScreen.intent(this))
            return
        }
        if (shouldAutoLaunchProjection(intent)) {
            val wireless = intent.getBooleanExtra("wireless", settings.connection.wirelessEnabled)
            intent.removeExtra("wireless")
            intent.removeExtra("auto_connect_token")
            launchProjectionStage(wireless)
        }
    }

    override fun onResume() {
        super.onResume()
        val resolvedNight = resolveAppNightNow()
        if (resolvedNight != appNight) {
            recreate()
            return
        }
        TvBluetoothSinkGuard.onActivityResumed(this)
        observeAppearance(ThemeModeDiagnostics.Source.RESUME)
        TvPlayBluetooth.sanitizeSavedPhone(this)
        TvLanOtaManager.resumePendingInstallIfReady(this)
        refreshWorkbenchStatus()
        settingsPane.refresh()
    }

    override fun onPause() {
        TvBluetoothSinkGuard.onActivityPaused(this)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        observeAppearance(ThemeModeDiagnostics.Source.CONFIGURATION_CHANGED, newConfig.uiMode)
        val resolvedNight = resolveAppNightNow()
        if (resolvedNight != appNight) {
            recreate()
        }
    }

    override fun onDestroy() {
        if (bluetoothSinkReceiverRegistered) {
            runCatching { unregisterReceiver(bluetoothSinkReceiver) }
            bluetoothSinkReceiverRegistered = false
        }
        runCatching { activeDialog?.dismiss() }
        activeDialog = null
        super.onDestroy()
    }

    private fun showTrackedDialog(dialog: Dialog) {
        runCatching { activeDialog?.dismiss() }
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
            }
        }
        dialog.show()
    }

    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                if (handleBackNavigation()) {
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_DOWN) {
                val focused = currentFocus
                if (focused is EditText || (::settingsPane.isInitialized && settingsPane.root.hasFocus())) {
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
            launchProjectionStage(settings.connection.wirelessEnabled)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && handleBackNavigation()) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (handleBackNavigation()) {
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun handleBackNavigation(): Boolean {
        val focused = currentFocus
        if (focused is EditText) {
            runCatching {
                val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(focused.windowToken, 0)
            }
            focused.clearFocus()
            if (::stagePane.isInitialized) {
                stagePane.primaryLaunchButton.requestFocus()
            }
            return true
        }
        if (::settingsPane.isInitialized && settingsPane.root.hasFocus()) {
            if (::stagePane.isInitialized) {
                stagePane.primaryLaunchButton.requestFocus()
                return true
            }
        }
        return false
    }

    private fun buildWorkbenchLayout() {
        val density = resources.displayMetrics.density
        val palette = TvColorScheme.resolve(this)

        val rootRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.FILL
            clipChildren = true
            clipToPadding = true
            background = app.tvplay.ui.theme.AmbientMeshBackgroundDrawable(palette)
            val padH = (18 * density).toInt()
            val padV = (14 * density).toInt()
            setPadding(padH, padV, padH, padV)
        }

        stagePane = WorkbenchHeroStage(this, settings).apply {
            onLaunchProjection = { wireless ->
                launchProjectionStage(wireless)
            }
            onSelectTransportMode = { wireless ->
                val changed = settings.connection.wirelessEnabled != wireless
                settings.connection.wirelessEnabled = wireless
                if (changed && CarPlayBackgroundSession.hasSession()) {
                    PendingReconnect.mark()
                }
                refreshWorkbenchStatus()
                settingsPane.refresh()
            }
            onSelectWirelessMode = { mode ->
                val changed = !settings.connection.wirelessEnabled || settings.connection.wirelessHotspotMode != mode
                settings.connection.wirelessEnabled = true
                settings.connection.wirelessHotspotMode = mode
                if (changed && CarPlayBackgroundSession.hasSession()) {
                    PendingReconnect.mark()
                }
                refreshWorkbenchStatus()
                settingsPane.refresh()
            }
            onSelectProjectionProtocol = { _ ->
                if (CarPlayBackgroundSession.hasSession()) {
                    PendingReconnect.mark()
                }
                refreshWorkbenchStatus()
                settingsPane.refresh()
            }
            onOpenBluetoothSettings = {
                openSystemBluetoothSettings()
            }
        }

        settingsPane = WorkbenchSettingsTabs(this, settings).apply {
            onChooseIphoneClicked = { showChooseIphoneDialog(launchAfterSelect = false) }
            onPairBluetoothClicked = { openSystemBluetoothSettings() }
            onResetWirelessHotspotClicked = { resetWirelessHotspotGroup() }
            onFullResetClicked = { confirmFullReset() }
            onSaveDiagnosticLogClicked = { exportDiagnosticReport() }
            onCheckOtaUpdateClicked = { checkOtaUpdate(manual = true) }
            onDiscoverOtaServerClicked = { discoverOtaServerManually() }
            onSettingChanged = {
                if (CarPlayBackgroundSession.hasSession()) {
                    PendingReconnect.mark()
                }
                refreshWorkbenchStatus()
            }
            onAppearanceChanged = {
                recreate()
            }
        }

        rootRow.addView(
            stagePane.root,
            LinearLayout.LayoutParams(0, MATCH_PARENT, 0.43f).apply {
                marginEnd = (14 * density).toInt()
            },
        )
        rootRow.addView(
            settingsPane.root,
            LinearLayout.LayoutParams(0, MATCH_PARENT, 0.57f),
        )

        setContentView(rootRow)
        refreshWorkbenchStatus()
        stagePane.primaryLaunchButton.requestFocus()
    }

    private fun refreshWorkbenchStatus() {
        val hasActive = CarPlayBackgroundSession.hasSession()
        stagePane.updateStatus(
            hasActiveSession = hasActive,
            pendingReconnect = hasActive && PendingReconnect.isPending(),
            onDisconnectSession = {
                CarPlayBackgroundSession.stop {
                    PendingReconnect.clear()
                    refreshWorkbenchStatus()
                }
            },
        )
    }

    private fun shouldAutoLaunchProjection(intent: Intent?): Boolean {
        if (intent == null) return false
        return intent.hasExtra("wireless") || intent.hasExtra("auto_connect_token")
    }

    private fun launchProjectionStage(
        wireless: Boolean = settings.connection.wirelessEnabled,
        skipPermissionPrompt: Boolean = false,
    ) {
        val modeChanged = settings.connection.wirelessEnabled != wireless
        pendingWirelessLaunch = wireless
        settings.connection.wirelessEnabled = wireless
        if (!modeChanged && CarPlayBackgroundSession.hasSession() && !PendingReconnect.isPending()) {
            openStageActivity(wireless)
            return
        }

        if (!skipPermissionPrompt) {
            val missingPermissions = buildList {
                if (wireless) {
                    val effectiveWirelessMode = if (settings.connection.projectionProtocol.enablesCarLink) {
                        TvWirelessMode.WIFI_DIRECT
                    } else {
                        settings.connection.wirelessHotspotMode
                    }
                    addAll(
                        WirelessPermissions.missing(
                            context = this@TvWorkbenchActivity,
                            wirelessMode = effectiveWirelessMode,
                        ),
                    )
                } else {
                    addAll(WiredPermissions.missing(this@TvWorkbenchActivity))
                }
                if (
                    settings.audio.microphoneEnabled &&
                    checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    add(android.Manifest.permission.RECORD_AUDIO)
                }
            }.distinct()
            if (missingPermissions.isNotEmpty()) {
                val requestStarted = runCatching {
                    requestPermissions(missingPermissions.toTypedArray(), REQUEST_PROJECTION_PERMISSIONS)
                    true
                }.getOrDefault(false)
                if (requestStarted) return
            }
            if (!wireless) {
                val vpnIntent = runCatching { android.net.VpnService.prepare(this) }.getOrNull()
                if (vpnIntent != null) {
                    val requested = runCatching {
                        @Suppress("DEPRECATION")
                        startActivityForResult(vpnIntent, REQUEST_VPN_PERMISSION)
                        true
                    }.getOrDefault(false)
                    if (requested) return
                }
            }
        }

        if (wireless) {
            if (!BluetoothRadioCheck.isOn(this)) {
                Toast.makeText(
                    this,
                    getString(R.string.turn_on_bluetooth_before_starting_wireless_carplay),
                    Toast.LENGTH_LONG,
                ).show()
                openSystemBluetoothSettings()
                return
            }

            val allBonded = TvPlayBluetooth.bondedDevices(this)
            TvPlayBluetooth.sanitizeSavedPhone(this, allBonded)
            if (settings.connection.projectionProtocol.enablesCarPlay && settings.connection.pairedPhoneAddress == null) {
                val phoneCandidates = allBonded.filter { !it.isAccessory }
                val likelyIphones = phoneCandidates.filter { it.isLikelyIphone }
                when {
                    likelyIphones.size == 1 -> {
                        val single = likelyIphones.single()
                        settings.connection.savePairedPhone(single.address, single.name)
                    }
                    !settings.connection.projectionProtocol.enablesCarLink -> {
                        showChooseIphoneDialog(launchAfterSelect = true)
                        return
                    }
                }
            }

            if (settings.connection.wirelessHotspotMode == TvWirelessMode.LAN_WIFI) {
                if (settings.connection.existingWifiSsid.isBlank()) {
                    LanNetworkLink.detectConnectedWifiSsid(this)?.let { detected ->
                        settings.connection.existingWifiSsid = detected
                    }
                }
                val missingSsid = settings.connection.existingWifiSsid.isBlank()
                val missingPassphrase = settings.connection.existingWifiPassphrase.isBlank() &&
                    !LanNetworkLink.isConnectedWifiOpen(this)
                if (missingSsid || missingPassphrase) {
                    showLanWifiConfigDialog(launchAfterSave = true)
                    return
                }
            }
        }

        openStageActivity(wireless)
    }

    private fun openStageActivity(wireless: Boolean) {
        startActivity(
            CarPlayScreen.intent(this)
                .putExtra("wireless", wireless),
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_PROJECTION_PERMISSIONS -> {
                launchProjectionStage(pendingWirelessLaunch, skipPermissionPrompt = true)
            }
            REQUEST_BLUETOOTH_CONNECT_FOR_DIALOG -> {
                doShowChooseIphoneDialog(
                    launchAfterSelect = pendingChoosePhoneLaunchAfterSelect,
                    includeAccessories = false,
                )
            }
            REQUEST_BLUETOOTH_DISCOVERABLE -> {
                if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                    requestBluetoothDiscoverable(launchStageAfter = pendingChoosePhoneLaunchAfterSelect)
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_VPN_PERMISSION -> {
                openStageActivity(pendingWirelessLaunch)
            }
            REQUEST_OTA_UNKNOWN_SOURCES -> {
                TvLanOtaManager.resumePendingInstallIfReady(this)
            }
            REQUEST_SAVE_DIAGNOSTIC_LOG -> {
                val uri = data?.data ?: return
                val report = TvDiagnosticsLogger.buildReport(this)
                runCatching {
                    DiagnosticExportStore.write(contentResolver, uri, report)
                    Toast.makeText(this, "诊断日志报告已保存", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        "保存诊断日志失败：${error.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun showChooseIphoneDialog(launchAfterSelect: Boolean = false) {
        pendingChoosePhoneLaunchAfterSelect = launchAfterSelect
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
            TvPlayBluetooth.bondedDevices(this).isEmpty()
        ) {
            val requestStarted = runCatching {
                requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    REQUEST_BLUETOOTH_CONNECT_FOR_DIALOG,
                )
                true
            }.getOrDefault(false)
            if (requestStarted) {
                doShowChooseIphoneDialog(launchAfterSelect = launchAfterSelect, includeAccessories = false)
                return
            }
        }
        doShowChooseIphoneDialog(launchAfterSelect = launchAfterSelect, includeAccessories = false)
    }

    private fun doShowChooseIphoneDialog(
        launchAfterSelect: Boolean,
        includeAccessories: Boolean,
    ) {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt().coerceAtLeast(1)
        val palette = TvColorScheme.resolve(this)

        if (!BluetoothRadioCheck.isOn(this)) {
            val bodyCard = TvModalDialog.createSubCard(this, palette, isWarning = true).apply {
                addView(
                    TextView(this@TvWorkbenchActivity).apply {
                        text = "无线 CarPlay 需要通过电视蓝牙与 iPhone 完成初始握手与 Wi-Fi 参数下发。\n请先打开电视系统蓝牙设置并开启蓝牙开关。"
                        setTextColor(palette.textPrimary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                        setLineSpacing(0f, 1.32f)
                    },
                )
            }
            val handle = TvModalDialog.create(
                context = this,
                title = "请先开启电视蓝牙",
                subtitle = "蓝牙无线握手 · CarPlay 自动建联前置条件",
                icon = TvIconGlyph.BLUETOOTH,
                badgeText = "蓝牙未开启",
                badgeIsWarning = true,
                bodyView = bodyCard,
                actions = listOf(
                    TvModalDialog.ActionSpec(
                        label = "取消",
                        kind = TvPlayButtonKind.SECONDARY,
                    ),
                    TvModalDialog.ActionSpec(
                        label = "打开电视蓝牙设置",
                        kind = TvPlayButtonKind.PRIMARY,
                        icon = TvIconGlyph.BLUETOOTH,
                    ) {
                        openSystemBluetoothSettings()
                    },
                ),
            )
            showTrackedDialog(handle.dialog)
            return
        }

        val allBonded = TvPlayBluetooth.bondedDevices(this)
        TvPlayBluetooth.sanitizeSavedPhone(this, allBonded)
        val devices = if (includeAccessories) allBonded else allBonded.filter { !it.isAccessory }
        if (devices.isEmpty()) {
            val onlyAccessoriesPaired = allBonded.isNotEmpty()
            val btName = TvPlayBluetooth.localName(this) ?: "本机电视蓝牙"
            val guideCard = TvModalDialog.createSubCard(this, palette).apply {
                addView(
                    TextView(this@TvWorkbenchActivity).apply {
                        text = if (onlyAccessoriesPaired) {
                            "当前仅检测到电视蓝牙遥控器或音频外设，尚未发现已配对的 iPhone。"
                        } else {
                            "尚未在电视蓝牙列表中检测到已配对的 iPhone。"
                        }
                        setTextColor(palette.textSecondary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        setPadding(0, 0, 0, dp(10))
                    },
                )
                val steps = listOf(
                    "1. 点击下方「开启蓝牙可见并等待连接」并允许可见",
                    "2. 在 iPhone 上打开「设置 → 蓝牙」，在底部「其他设备」中点击「$btName」",
                    "3. 配对完成后将自动建立无线 CarPlay 投屏",
                )
                steps.forEachIndexed { idx, stepText ->
                    addView(
                        TextView(this@TvWorkbenchActivity).apply {
                            text = stepText
                            setTextColor(palette.textPrimary)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            if (idx < steps.lastIndex) {
                                setPadding(0, 0, 0, dp(6))
                            }
                        },
                    )
                }
            }
            val actions = buildList {
                add(
                    TvModalDialog.ActionSpec(
                        label = "系统蓝牙设置",
                        kind = TvPlayButtonKind.SECONDARY,
                    ) {
                        launchSystemBluetoothSettings()
                    },
                )
                if (onlyAccessoriesPaired) {
                    add(
                        TvModalDialog.ActionSpec(
                            label = "显示全部蓝牙设备",
                            kind = TvPlayButtonKind.SECONDARY,
                        ) {
                            doShowChooseIphoneDialog(
                                launchAfterSelect = launchAfterSelect,
                                includeAccessories = true,
                            )
                        },
                    )
                }
                add(
                    TvModalDialog.ActionSpec(
                        label = "直接进入等待画面（安卓 CarLink 免配对）",
                        kind = TvPlayButtonKind.SECONDARY,
                    ) {
                        openStageActivity(wireless = true)
                    },
                )
                add(
                    TvModalDialog.ActionSpec(
                        label = "开启蓝牙可见并等待连接（推荐）",
                        kind = TvPlayButtonKind.PRIMARY,
                        icon = TvIconGlyph.BLUETOOTH,
                    ) {
                        requestBluetoothDiscoverable(launchStageAfter = launchAfterSelect)
                    },
                )
            }
            val handle = TvModalDialog.create(
                context = this,
                title = "尚未配对 iPhone",
                subtitle = "本机蓝牙名称：$btName · 支持一键开启蓝牙广播可见",
                icon = TvIconGlyph.PHONE,
                badgeText = "待配对",
                bodyView = guideCard,
                actions = actions,
            )
            showTrackedDialog(handle.dialog)
            return
        }

        val currentAddress = settings.connection.pairedPhoneAddress
        val optionItems = devices.map { device ->
            val selected = device.address.equals(currentAddress, ignoreCase = true)
            val roleLabel = when {
                device.isLikelyIphone -> "iPhone 设备"
                device.isAccessory -> "蓝牙外设"
                else -> "蓝牙设备"
            }
            TvModalDialog.OptionItem(
                title = device.name,
                subtitle = "$roleLabel  ·  MAC ${device.address}",
                badgeText = if (selected) "当前绑定" else "点击绑定",
                selected = selected,
                icon = if (device.isAccessory) TvIconGlyph.BLUETOOTH else TvIconGlyph.PHONE,
            )
        }

        var dialogRef: Dialog? = null
        val listView = TvModalDialog.createOptionListView(
            context = this,
            items = optionItems,
            palette = palette,
        ) { which ->
            val chosen = devices[which]
            settings.connection.savePairedPhone(chosen.address, chosen.name)
            if (CarPlayBackgroundSession.hasSession()) {
                PendingReconnect.mark()
            }
            refreshWorkbenchStatus()
            settingsPane.refresh()
            runCatching { dialogRef?.dismiss() }
            if (launchAfterSelect) {
                launchProjectionStage(wireless = true, skipPermissionPrompt = true)
            }
        }

        val handle = TvModalDialog.create(
            context = this,
            title = "选择已配对的 iPhone",
            subtitle = "使用遥控器上下键选择目标 iPhone 并按确认键绑定",
            icon = TvIconGlyph.PHONE,
            badgeText = "${devices.size} 台设备",
            bodyView = listView,
            actions = listOf(
                TvModalDialog.ActionSpec(
                    label = "取消",
                    kind = TvPlayButtonKind.SECONDARY,
                ),
                TvModalDialog.ActionSpec(
                    label = "去配对新 iPhone",
                    kind = TvPlayButtonKind.SECONDARY,
                    icon = TvIconGlyph.BLUETOOTH,
                ) {
                    openSystemBluetoothSettings()
                },
            ),
        )
        dialogRef = handle.dialog
        showTrackedDialog(handle.dialog)
    }

    private fun showLanWifiConfigDialog(launchAfterSave: Boolean) {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt().coerceAtLeast(1)
        val palette = TvColorScheme.resolve(this)

        val bodyColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val tipCard = TvModalDialog.createSubCard(this, palette).apply {
            addView(
                TextView(this@TvWorkbenchActivity).apply {
                    text = "无线 CarPlay 蓝牙握手时需将路由器 Wi-Fi 信息告知 iPhone：\n" +
                        "• 推荐使用「Wi-Fi Direct 5GHz 直连」，无需填写任何路由器密码即可自动建联。\n" +
                        "• 若使用「家庭局域网模式」，请在下方填写 iPhone 所连的路由器名称与密码。"
                    setTextColor(palette.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setLineSpacing(0f, 1.3f)
                },
            )
        }
        bodyColumn.addView(
            tipCard,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) },
        )

        fun createStyledInput(
            initial: String,
            hintText: String,
            password: Boolean,
        ): EditText {
            fun boxBg(focused: Boolean) = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(if (focused) palette.surfaceCardElevated else palette.subtleSurface)
                setStroke(
                    if (focused) dp(2) else dp(1),
                    if (focused) palette.focusRing else palette.outline,
                )
            }
            return EditText(this).apply {
                setText(initial)
                hint = hintText
                setTextColor(palette.textPrimary)
                setHintTextColor(palette.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                inputType = if (password) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                background = boxBg(false)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnFocusChangeListener { _, hasFocus ->
                    background = boxBg(hasFocus)
                }
            }
        }

        val detectedSsid = LanNetworkLink.detectConnectedWifiSsid(this).orEmpty()
        val ssidInput = createStyledInput(
            initial = settings.connection.existingWifiSsid.ifBlank { detectedSsid },
            hintText = "家庭路由器 Wi-Fi 名称 (SSID)",
            password = false,
        )
        val passInput = createStyledInput(
            initial = settings.connection.existingWifiPassphrase,
            hintText = "WPA2 Wi-Fi 密码（若为开放网络可留空）",
            password = true,
        )

        val formCard = TvModalDialog.createSubCard(this, palette).apply {
            addView(
                TextView(this@TvWorkbenchActivity).apply {
                    text = "路由器 Wi-Fi 名称 (SSID)"
                    setTextColor(palette.textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(0, 0, 0, dp(5))
                },
            )
            addView(
                ssidInput,
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) },
            )
            addView(
                TextView(this@TvWorkbenchActivity).apply {
                    text = "路由器 Wi-Fi 密码 (WPA2)"
                    setTextColor(palette.textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(0, 0, 0, dp(5))
                },
            )
            addView(passInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        bodyColumn.addView(formCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val handle = TvModalDialog.create(
            context = this,
            title = "配置家庭局域网 Wi-Fi",
            subtitle = "填写路由器凭据或一键切换至免配置 Wi-Fi Direct 直连",
            icon = TvIconGlyph.LAN,
            badgeText = "LAN 模式",
            bodyView = bodyColumn,
            actions = listOf(
                TvModalDialog.ActionSpec(
                    label = "取消",
                    kind = TvPlayButtonKind.SECONDARY,
                ),
                TvModalDialog.ActionSpec(
                    label = "改用 Wi-Fi Direct 直连（推荐）",
                    kind = TvPlayButtonKind.SECONDARY,
                    icon = TvIconGlyph.WIFI,
                ) {
                    settings.connection.wirelessHotspotMode = TvWirelessMode.WIFI_DIRECT
                    refreshWorkbenchStatus()
                    settingsPane.refresh()
                    if (launchAfterSave) {
                        launchProjectionStage(wireless = true)
                    }
                },
                TvModalDialog.ActionSpec(
                    label = "保存并启动投屏",
                    kind = TvPlayButtonKind.PRIMARY,
                    icon = TvIconGlyph.PLAY,
                ) {
                    val ssid = ssidInput.text?.toString()?.trim().orEmpty()
                    val pass = passInput.text?.toString().orEmpty()
                    if (ssid.isNotBlank() || pass.isNotBlank()) {
                        settings.connection.saveExistingWifiCredentials(ssid, pass)
                    }
                    refreshWorkbenchStatus()
                    settingsPane.refresh()
                    if (launchAfterSave) {
                        openStageActivity(wireless = true)
                    }
                },
            ),
        )
        showTrackedDialog(handle.dialog)
    }

    @SuppressLint("MissingPermission")
    private fun resetWirelessHotspotGroup() {
        val manager = getSystemService(WifiP2pManager::class.java)
        val channel = manager?.initialize(this, Looper.getMainLooper(), null)
        if (manager == null || channel == null) {
            Toast.makeText(this, "当前设备不支持 Wi-Fi Direct 重置", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) {
                    Toast.makeText(this, "Wi-Fi Direct 投屏通道已处于干净就绪状态", Toast.LENGTH_SHORT).show()
                    return@requestGroupInfo
                }
                manager.removeGroup(
                    channel,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Toast.makeText(
                                this@TvWorkbenchActivity,
                                "已重置 Wi-Fi Direct 投屏通道，请重新启动投屏",
                                Toast.LENGTH_LONG,
                            ).show()
                        }

                        override fun onFailure(reason: Int) {
                            Toast.makeText(
                                this@TvWorkbenchActivity,
                                "未能自动移除旧通道（请在系统设置中关闭再打开 Wi-Fi）",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
            }
        }.onFailure {
            Toast.makeText(this, "重置 Wi-Fi Direct 通道时需授予附近设备权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSystemBluetoothSettings() {
        val btName = TvPlayBluetooth.localName(this) ?: "本机电视蓝牙"
        val carLinkPin = settings.connection.carLinkPinCode
        val palette = TvColorScheme.resolve(this)
        val items = listOf(
            TvModalDialog.OptionItem(
                title = "断开蓝牙音乐占用并开启安卓车联（推荐）",
                subtitle = "关闭电视自带蓝牙音箱(A2DP)抢占，立即进入投屏舞台广播（连接码：$carLinkPin）",
                badgeText = "安卓车联",
                selected = true,
                icon = TvIconGlyph.PHONE,
            ),
            TvModalDialog.OptionItem(
                title = "开启本机蓝牙可见（仅限 iPhone 配对）",
                subtitle = "让 iPhone 在「设置 → 蓝牙」底部直接搜到「$btName」并完成配对（安卓请勿在此配对）",
                badgeText = "iPhone",
                selected = false,
                icon = TvIconGlyph.RADAR,
            ),
            TvModalDialog.OptionItem(
                title = "打开电视系统蓝牙设置",
                subtitle = "进入 Android TV 系统蓝牙界面（如安卓手机已配对，建议取消配对以免误跳蓝牙音乐）",
                badgeText = "系统设置",
                selected = false,
                icon = TvIconGlyph.BLUETOOTH,
            ),
        )
        var dialogRef: Dialog? = null
        val listView = TvModalDialog.createOptionListView(
            context = this,
            items = items,
            palette = palette,
        ) { which ->
            runCatching { dialogRef?.dismiss() }
            when (which) {
                0 -> {
                    TvBluetoothSinkGuard.suppressA2dpSink(this)
                    openStageActivity(wireless = true)
                }
                1 -> requestBluetoothDiscoverable(launchStageAfter = false)
                2 -> launchSystemBluetoothSettings()
            }
        }
        val handle = TvModalDialog.create(
            context = this,
            title = "蓝牙与车联连接向导",
            subtitle = "本机蓝牙标识：$btName · 安卓连接码：$carLinkPin",
            icon = TvIconGlyph.BLUETOOTH,
            badgeText = "连接向导",
            bodyView = listView,
            actions = listOf(
                TvModalDialog.ActionSpec(
                    label = "关闭",
                    kind = TvPlayButtonKind.SECONDARY,
                ),
            ),
        )
        dialogRef = handle.dialog
        showTrackedDialog(handle.dialog)
    }

    private fun confirmFullReset() {
        val palette = TvColorScheme.resolve(this)
        val density = resources.displayMetrics.density
        val descView = TextView(this).apply {
            text = "将彻底清除所有已保存的 iPhone / 安卓 CarLink 配对记录、认证密钥、Wi-Fi Direct 残留组网、画面与音频自定义设置，并重新生成全新的车机身份标识与 6 位连接码。"
            setTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(0f, 1.3f)
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val handle = TvModalDialog.create(
            context = this,
            title = "确认完全重置所有连接与设置？",
            subtitle = "恢复至首次安装时的干净初始状态",
            icon = TvIconGlyph.SHIELD_MFI,
            badgeText = "恢复出厂",
            bodyView = descView,
            actions = listOf(
                TvModalDialog.ActionSpec(
                    label = "取消",
                    kind = TvPlayButtonKind.SECONDARY,
                ),
                TvModalDialog.ActionSpec(
                    label = "立即完全重置",
                    kind = TvPlayButtonKind.DANGER,
                    icon = TvIconGlyph.REFRESH,
                ) {
                    val performReset = {
                        val newPin = settings.resetAllToDefaults()
                        LastConnectionProblem.clear(this)
                        PendingReconnect.clear()
                        resetWirelessHotspotGroup()
                        refreshWorkbenchStatus()
                        settingsPane.refresh()
                        Toast.makeText(
                            this,
                            "已完成完全重置，新 6 位连接码：$newPin",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    if (CarPlayBackgroundSession.hasSession()) {
                        CarPlayBackgroundSession.stop {
                            performReset()
                        }
                    } else {
                        performReset()
                    }
                },
            ),
        )
        showTrackedDialog(handle.dialog)
    }

    private fun requestBluetoothDiscoverable(launchStageAfter: Boolean) {
        pendingChoosePhoneLaunchAfterSelect = launchStageAfter
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        ) {
            val started = runCatching {
                requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT),
                    REQUEST_BLUETOOTH_DISCOVERABLE,
                )
                true
            }.getOrDefault(false)
            if (started) return
        }
        if (launchStageAfter) {
            openStageActivity(wireless = true)
        }
        TvBluetoothSinkGuard.markLaunchingExternalSettings()
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
            if (!launchStageAfter) {
                launchSystemBluetoothSettings()
            }
        }
    }

    private fun launchSystemBluetoothSettings() {
        TvBluetoothSinkGuard.markLaunchingExternalSettings()
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun exportDiagnosticReport() {
        val report = TvDiagnosticsLogger.buildReport(this)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "tvplay-diagnostic-$stamp.txt"
        val createIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        val startedPicker = runCatching {
            startActivityForResult(createIntent, REQUEST_SAVE_DIAGNOSTIC_LOG)
            true
        }.getOrDefault(false)
        if (!startedPicker) {
            runCatching {
                val saved = DiagnosticExportStore.saveWithoutPicker(this, fileName, report)
                Toast.makeText(
                    this,
                    "诊断日志已导出至：${saved.displayPath}",
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure { error ->
                Toast.makeText(
                    this,
                    "无法导出诊断日志：${error.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun observeAppearance(
        source: ThemeModeDiagnostics.Source,
        uiMode: Int = resources.configuration.uiMode,
    ) {
        themeModeDiagnostics.observe(
            source = source,
            uiMode = uiMode,
            appliedNight = appNight,
            sessionActive = CarPlayBackgroundSession.hasSession(),
        )
    }

    private fun discoverOtaServerManually() {
        Toast.makeText(this, "正在通过 UDP 9988 广播探测内网 OTA 服务...", Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        TvLanOtaManager.runInBackground {
            val result = TvLanOtaManager.discoverServer(appContext, timeoutMillis = 2_000)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result != null) {
                    settingsPane.refresh()
                    val label = result.title ?: result.service
                    Toast.makeText(
                        this,
                        "已发现 $label：${result.baseUrl} (v${result.version})",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "未在局域网探测到 OTA 服务响应，当前使用地址：${settings.identity.otaServerUrl}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun checkOtaUpdate(manual: Boolean) {
        if (manual) {
            Toast.makeText(this, "正在检查内网 OTA 更新...", Toast.LENGTH_SHORT).show()
        }
        val appContext = applicationContext
        TvLanOtaManager.runInBackground {
            runCatching {
                TvLanOtaManager.checkForUpdate(appContext, tryDiscoveryFirst = true)
            }.onSuccess { result ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (::settingsPane.isInitialized) {
                        settingsPane.refresh()
                    }
                    if (result.hasUpdate && result.release != null) {
                        showOtaUpdateDialog(result)
                    } else if (manual) {
                        val installed = TvLanOtaManager.currentAppVersion(this)
                        val note = result.message ?: "当前已是最新版本"
                        Toast.makeText(
                            this,
                            "TVPlay v${installed.versionName} (${installed.versionCode})：$note",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }.onFailure { error ->
                if (manual) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        Toast.makeText(
                            this,
                            "检查内网 OTA 更新失败：${error.message ?: error.javaClass.simpleName}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun showOtaUpdateDialog(checkResult: LanOtaCheckResult) {
        val release = checkResult.release ?: return
        val installed = TvLanOtaManager.currentAppVersion(this)
        val sizeText = LanOtaProtocol.formatFileSize(release.fileSize)
        val rawChangelog = release.changelog.ifBlank { "性能优化与连接稳定性加固" }
        val formattedChangelog = formatChangelogForTv(rawChangelog)
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt().coerceAtLeast(1)
        val palette = TvColorScheme.resolve(this)

        val bodyColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 1. Version Upgrade Comparison Bento Card
        val versionCard = TvModalDialog.createSubCard(this, palette).apply {
            val row = LinearLayout(this@TvWorkbenchActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fun buildVersionBlock(
                caption: String,
                versionStr: String,
                buildStr: String,
                highlighted: Boolean,
            ): LinearLayout = LinearLayout(this@TvWorkbenchActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addView(
                    TextView(this@TvWorkbenchActivity).apply {
                        text = caption
                        setTextColor(palette.textSecondary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    },
                )
                addView(
                    TextView(this@TvWorkbenchActivity).apply {
                        text = versionStr
                        setTextColor(if (highlighted) palette.success else palette.textPrimary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                        setPadding(0, dp(2), 0, dp(1))
                    },
                )
                addView(
                    TextView(this@TvWorkbenchActivity).apply {
                        text = buildStr
                        setTextColor(if (highlighted) palette.cyanAccent else palette.textSecondary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                    },
                )
            }

            val arrowBadge = TextView(this@TvWorkbenchActivity).apply {
                text = "UPGRADE  →"
                setTextColor(palette.cyanAccent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(dp(12), dp(5), dp(12), dp(5))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 999f * density
                    setColor(ColorUtils.setAlphaComponent(palette.accent, if (palette.night) 0x26 else 0x16))
                    setStroke(dp(1), ColorUtils.setAlphaComponent(palette.cyanAccent, 0x66))
                }
            }

            row.addView(
                buildVersionBlock(
                    caption = "当前安装版本",
                    versionStr = "v${installed.versionName}",
                    buildStr = "Build ${installed.versionCode}",
                    highlighted = false,
                ),
            )
            row.addView(
                arrowBadge,
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    marginStart = dp(12)
                    marginEnd = dp(16)
                },
            )
            row.addView(
                buildVersionBlock(
                    caption = "最新可用版本",
                    versionStr = "v${release.versionName}",
                    buildStr = "Build ${release.versionCode} · ${release.channel}",
                    highlighted = true,
                ),
            )
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

            // Spec Metadata Chips Row
            val chipsRow = LinearLayout(this@TvWorkbenchActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            fun addSpecChip(textValue: String) {
                val chip = TextView(this@TvWorkbenchActivity).apply {
                    text = textValue
                    setTextColor(palette.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 999f * density
                        setColor(palette.badgeSurface)
                        setStroke(dp(1), palette.badgeOutline)
                    }
                }
                chipsRow.addView(
                    chip,
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        marginEnd = dp(8)
                    },
                )
            }
            addSpecChip("安装包 · $sizeText")
            addSpecChip("断点续传 · HTTP Range")
            if (!release.sha256.isNullOrBlank()) {
                addSpecChip("SHA-256 · ${release.sha256.take(8)}…")
            }
            if (!release.publishedAt.isNullOrBlank()) {
                addSpecChip("发布于 ${release.publishedAt}")
            }
            addView(chipsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        bodyColumn.addView(
            versionCard,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) },
        )

        // 2. Release Notes / Changelog Card
        val notesCard = TvModalDialog.createSubCard(this, palette).apply {
            val sectionHeader = LinearLayout(this@TvWorkbenchActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
                addView(
                    View(this@TvWorkbenchActivity).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(2).toFloat()
                            setColor(palette.accent)
                        }
                    },
                    LinearLayout.LayoutParams(dp(4), dp(13)).apply { marginEnd = dp(8) },
                )
                addView(
                    TextView(this@TvWorkbenchActivity).apply {
                        text = "版本更新说明 (RELEASE NOTES)"
                        setTextColor(palette.accent)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        letterSpacing = 0.04f
                    },
                )
            }
            addView(sectionHeader, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

            val scrollBox = ScrollView(this@TvWorkbenchActivity).apply {
                isVerticalScrollBarEnabled = true
                isFocusable = false
                addView(
                    TextView(this@TvWorkbenchActivity).apply {
                        text = formattedChangelog
                        setTextColor(palette.textPrimary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setLineSpacing(0f, 1.38f)
                    },
                    FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
                )
            }
            val maxNotesHeight = (resources.displayMetrics.heightPixels * 0.28f).toInt().coerceAtLeast(dp(110))
            addView(
                scrollBox,
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    if (formattedChangelog.lines().size > 5 || formattedChangelog.length > 180) {
                        height = maxNotesHeight
                    }
                },
            )
        }
        bodyColumn.addView(notesCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        if (checkResult.forceUpdate) {
            val mandatoryBanner = TvModalDialog.createSubCard(this, palette, isWarning = true).apply {
                addView(
                    TextView(this@TvWorkbenchActivity).apply {
                        text = "重要提示：该版本包含关键协议与安全升级，属于强制更新版本，请立即完成升级。"
                        setTextColor(palette.warningText)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    },
                )
            }
            bodyColumn.addView(
                mandatoryBanner,
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) },
            )
        }

        val actions = buildList {
            if (!checkResult.forceUpdate) {
                add(
                    TvModalDialog.ActionSpec(
                        label = "稍后提醒",
                        kind = TvPlayButtonKind.SECONDARY,
                    ),
                )
            }
            add(
                TvModalDialog.ActionSpec(
                    label = "立即下载并升级",
                    kind = TvPlayButtonKind.PRIMARY,
                    icon = TvIconGlyph.CLOUD_OTA,
                ) {
                    startOtaDownloadAndInstall(checkResult)
                },
            )
        }

        val handle = TvModalDialog.create(
            context = this,
            title = "发现 TVPlay 软件更新",
            subtitle = "飞牛 OS 局域网 OTA 升级中心 · 零感静默校验安装",
            icon = TvIconGlyph.CLOUD_OTA,
            badgeText = if (checkResult.forceUpdate) "强制更新" else "NEW v${release.versionName}",
            badgeIsWarning = checkResult.forceUpdate,
            cancelable = !checkResult.forceUpdate,
            bodyView = bodyColumn,
            actions = actions,
        )
        showTrackedDialog(handle.dialog)
    }

    private fun formatChangelogForTv(raw: String): String {
        val trimmed = raw.trim()
        // Split inline numbered items ("1. xxx；2. yyy") into clean bulleted lines for 10-foot TV legibility
        val parts = trimmed
            .replace(Regex("(?<=[:：])\\s*(?=1\\.)"), "\n")
            .split(Regex("[；;\\n]+\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.size > 1) {
            parts.joinToString("\n") { line ->
                if (line.matches(Regex("^\\d+\\..*"))) "• $line" else line
            }
        } else {
            trimmed
        }
    }

    private fun startOtaDownloadAndInstall(checkResult: LanOtaCheckResult) {
        val release = checkResult.release ?: return
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt().coerceAtLeast(1)
        val palette = TvColorScheme.resolve(this)

        val statusText = TextView(this).apply {
            text = "正在连接内网 OTA 服务器下载 v${release.versionName}..."
            setTextColor(palette.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }
        val progressBar = TvGlassProgressBar(this, palette)
        val detailText = TextView(this).apply {
            text = "0%  ·  支持 HTTP Range 断点续传与 SHA-256 完整性校验"
            setTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, dp(10), 0, 0)
        }

        val progressCard = TvModalDialog.createSubCard(this, palette).apply {
            addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(progressBar, LinearLayout.LayoutParams(MATCH_PARENT, dp(14)))
            addView(detailText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        val progressHandle = TvModalDialog.create(
            context = this,
            title = "正在下载 TVPlay v${release.versionName}",
            subtitle = "局域网高速传输 · 校验通过后将自动拉起系统安装",
            icon = TvIconGlyph.CLOUD_OTA,
            badgeText = "0%",
            cancelable = false,
            bodyView = progressCard,
        )
        val progressDialog = progressHandle.dialog
        showTrackedDialog(progressDialog)

        val appContext = applicationContext
        TvLanOtaManager.runInBackground {
            runCatching {
                TvLanOtaManager.downloadRelease(appContext, checkResult) { progress ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed || !progressDialog.isShowing) return@runOnUiThread
                        progressBar.progress = progress.percent
                        progressHandle.rightBadgeView.text = "${progress.percent}%"
                        val downloadedStr = LanOtaProtocol.formatFileSize(progress.downloadedBytes)
                        val totalStr = if (progress.totalBytes > 0L) {
                            LanOtaProtocol.formatFileSize(progress.totalBytes)
                        } else {
                            "未知大小"
                        }
                        val resumeTag = if (progress.resumedFromBytes > 0L) "（已断点续传）" else ""
                        statusText.text = "正在高速下载更新包 $resumeTag..."
                        detailText.text = "$downloadedStr / $totalStr  ·  SHA-256 完整性保护"
                    }
                }
            }.onSuccess { apkFile ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    runCatching { progressDialog.dismiss() }
                    when (
                        val launch = TvLanOtaManager.installApk(
                            activity = this,
                            apkFile = apkFile,
                            checkResult = checkResult,
                            requestCodeUnknownSources = REQUEST_OTA_UNKNOWN_SOURCES,
                        )
                    ) {
                        TvLanOtaManager.InstallLaunchResult.InstallerLaunched -> {
                            Toast.makeText(
                                this,
                                "安装包校验通过，请在系统安装界面确认升级",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        TvLanOtaManager.InstallLaunchResult.UnknownSourcesPermissionRequested -> {
                            Toast.makeText(
                                this,
                                "请先开启「允许安装未知应用」权限，返回后将自动启动升级",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        is TvLanOtaManager.InstallLaunchResult.Failed -> {
                            Toast.makeText(
                                this,
                                "启动系统安装程序失败：${launch.message}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    runCatching { progressDialog.dismiss() }
                    val errorCard = TvModalDialog.createSubCard(this, palette, isWarning = true).apply {
                        addView(
                            TextView(this@TvWorkbenchActivity).apply {
                                text = "中断原因：${error.message ?: error.javaClass.simpleName}\n\n" +
                                    "已自动保留当前已下载的数据分片，点击「断点续传重试」即可从断点位置继续下载。"
                                setTextColor(palette.textPrimary)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                                setLineSpacing(0f, 1.35f)
                            },
                        )
                    }
                    val retryHandle = TvModalDialog.create(
                        context = this,
                        title = "内网 OTA 更新下载中断",
                        subtitle = "已保存分片缓存 · 支持 HTTP Range 断点续传",
                        icon = TvIconGlyph.CLOUD_OTA,
                        badgeText = "可续传",
                        badgeIsWarning = true,
                        bodyView = errorCard,
                        actions = listOf(
                            TvModalDialog.ActionSpec(
                                label = "取消",
                                kind = TvPlayButtonKind.SECONDARY,
                            ),
                            TvModalDialog.ActionSpec(
                                label = "断点续传重试",
                                kind = TvPlayButtonKind.PRIMARY,
                                icon = TvIconGlyph.REFRESH,
                            ) {
                                startOtaDownloadAndInstall(checkResult)
                            },
                        ),
                    )
                    showTrackedDialog(retryHandle.dialog)
                }
            }
        }
    }

    companion object {
        private const val REQUEST_PROJECTION_PERMISSIONS = 1001
        private const val REQUEST_VPN_PERMISSION = 1002
        private const val REQUEST_SAVE_DIAGNOSTIC_LOG = 1003
        private const val REQUEST_BLUETOOTH_CONNECT_FOR_DIALOG = 1004
        private const val REQUEST_BLUETOOTH_DISCOVERABLE = 1005
        private const val REQUEST_OTA_UNKNOWN_SOURCES = 1006

        fun isLauncherIntent(intent: Intent?): Boolean =
            intent?.action == Intent.ACTION_MAIN &&
                (
                    intent.hasCategory(Intent.CATEGORY_LAUNCHER) ||
                        intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    ) &&
                intent.extras?.containsKey("page") != true
    }
}
