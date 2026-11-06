package app.tvplay.ui.workbench

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import app.tvplay.R
import app.tvplay.config.TvPlayBluetooth
import app.tvplay.config.TvSettingsRepository
import app.tvplay.config.WiredPermissions
import app.tvplay.config.WirelessPermissions
import app.tvplay.engine.network.BluetoothRadioCheck
import app.tvplay.engine.network.LanNetworkLink
import app.tvplay.engine.network.UsbTunnelVpnService
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.runtime.CarPlayBackgroundSession
import app.tvplay.runtime.DiagnosticExportStore
import app.tvplay.runtime.LanOtaCheckResult
import app.tvplay.runtime.LanOtaProtocol
import app.tvplay.runtime.PendingReconnect
import app.tvplay.runtime.ThemeModeDiagnostics
import app.tvplay.runtime.TvDiagnosticsLogger
import app.tvplay.runtime.TvLanOtaManager
import app.tvplay.ui.stage.CarPlayScreen
import app.tvplay.ui.theme.AppAppearanceOwner
import app.tvplay.ui.theme.AppAppearanceRuntime
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.ui.theme.appDialogBuilder
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
    private var activeDialog: android.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = TvSettingsRepository(this)
        appNight = resolveAppNightNow()
        AppAppearanceRuntime.publishHost(this, appNight)
        setTheme(if (appNight) R.style.Theme_TVPlay else R.style.Theme_TVPlay_Light)
        super.onCreate(savedInstanceState)

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
        observeAppearance(ThemeModeDiagnostics.Source.RESUME)
        TvPlayBluetooth.sanitizeSavedPhone(this)
        TvLanOtaManager.resumePendingInstallIfReady(this)
        refreshWorkbenchStatus()
        settingsPane.refresh()
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
        runCatching { activeDialog?.dismiss() }
        activeDialog = null
        super.onDestroy()
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
            clipChildren = false
            clipToPadding = false
            background = app.tvplay.ui.theme.AmbientMeshBackgroundDrawable(palette)
            setPadding((24 * density).toInt())
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
            onOpenBluetoothSettings = {
                openSystemBluetoothSettings()
            }
        }

        settingsPane = WorkbenchSettingsTabs(this, settings).apply {
            onChooseIphoneClicked = { showChooseIphoneDialog(launchAfterSelect = false) }
            onPairBluetoothClicked = { openSystemBluetoothSettings() }
            onResetWirelessHotspotClicked = { resetWirelessHotspotGroup() }
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
            LinearLayout.LayoutParams(0, MATCH_PARENT, 0.42f).apply {
                marginEnd = (20 * density).toInt()
            },
        )
        rootRow.addView(
            settingsPane.root,
            LinearLayout.LayoutParams(0, MATCH_PARENT, 0.58f),
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
                    addAll(
                        WirelessPermissions.missing(
                            context = this@TvWorkbenchActivity,
                            wirelessMode = settings.connection.wirelessHotspotMode,
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
            if (settings.connection.pairedPhoneAddress == null) {
                val phoneCandidates = allBonded.filter { !it.isAccessory }
                val likelyIphones = phoneCandidates.filter { it.isLikelyIphone }
                when {
                    likelyIphones.size == 1 -> {
                        val single = likelyIphones.single()
                        settings.connection.savePairedPhone(single.address, single.name)
                    }
                    phoneCandidates.size == 1 -> {
                        val single = phoneCandidates.single()
                        settings.connection.savePairedPhone(single.address, single.name)
                    }
                    else -> {
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
        if (!BluetoothRadioCheck.isOn(this)) {
            appDialogBuilder(this)
                .setTitle("请先开启电视蓝牙")
                .setMessage(
                    "无线 CarPlay 需要通过电视蓝牙与 iPhone 完成初始握手。请先打开电视系统蓝牙设置并开启蓝牙开关。",
                )
                .setPositiveButton("打开电视蓝牙设置") { _, _ ->
                    openSystemBluetoothSettings()
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val allBonded = TvPlayBluetooth.bondedDevices(this)
        TvPlayBluetooth.sanitizeSavedPhone(this, allBonded)
        val devices = if (includeAccessories) allBonded else allBonded.filter { !it.isAccessory }
        if (devices.isEmpty()) {
            val onlyAccessoriesPaired = allBonded.isNotEmpty()
            val btName = TvPlayBluetooth.localName(this) ?: "本机电视蓝牙"
            val builder = appDialogBuilder(this)
                .setTitle("尚未配对 iPhone")
                .setMessage(
                    (if (onlyAccessoriesPaired) {
                        "当前仅检测到电视蓝牙遥控器或音频外设，尚未发现已配对的 iPhone。\n\n"
                    } else {
                        "尚未检测到已配对的 iPhone。\n\n"
                    }) +
                        "推荐配对方式：\n" +
                        "1. 点击下方「开启蓝牙可见并等待连接」并允许可见\n" +
                        "2. 在 iPhone 上打开「设置 → 蓝牙」，在底部「其他设备」中点击「$btName」\n" +
                        "3. 配对完成后将自动建立无线 CarPlay 投屏",
                )
                .setPositiveButton("开启蓝牙可见并等待连接（推荐）") { _, _ ->
                    requestBluetoothDiscoverable(launchStageAfter = launchAfterSelect)
                }
                .setNegativeButton("系统蓝牙设置") { _, _ ->
                    launchSystemBluetoothSettings()
                }
            if (onlyAccessoriesPaired) {
                builder.setNeutralButton("显示全部蓝牙设备") { _, _ ->
                    doShowChooseIphoneDialog(
                        launchAfterSelect = launchAfterSelect,
                        includeAccessories = true,
                    )
                }
            } else if (launchAfterSelect) {
                builder.setNeutralButton("直接进入等待画面") { _, _ ->
                    openStageActivity(wireless = true)
                }
            }
            builder.show()
            return
        }

        val labels = devices.map { device ->
            val tag = when {
                device.isLikelyIphone -> " · iPhone"
                device.isAccessory -> " · 外设"
                else -> ""
            }
            "${device.name}$tag (${device.address})"
        }.toTypedArray()
        val currentAddress = settings.connection.pairedPhoneAddress
        val checkedIndex = devices.indexOfFirst { it.address.equals(currentAddress, ignoreCase = true) }

        appDialogBuilder(this)
            .setTitle("选择已配对的 iPhone")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val chosen = devices[which]
                settings.connection.savePairedPhone(chosen.address, chosen.name)
                if (CarPlayBackgroundSession.hasSession()) {
                    PendingReconnect.mark()
                }
                refreshWorkbenchStatus()
                settingsPane.refresh()
                dialog.dismiss()
                if (launchAfterSelect) {
                    launchProjectionStage(wireless = true, skipPermissionPrompt = true)
                }
            }
            .setNeutralButton("去配对新 iPhone") { _, _ ->
                openSystemBluetoothSettings()
            }
            .setNegativeButton("取消", null)
            .create()
            .also(::showTrackedDialog)
    }

    private fun showLanWifiConfigDialog(launchAfterSave: Boolean) {
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
            setText(settings.connection.existingWifiSsid.ifBlank { detectedSsid })
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            isSingleLine = true
        }
        val passLabel = TextView(this).apply {
            text = "家庭路由器 Wi-Fi 密码（若无密码可留空）："
            setTextColor(palette.textSecondary)
            setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt())
        }
        val passInput = EditText(this).apply {
            hint = "WPA2 Wi-Fi 密码"
            setText(settings.connection.existingWifiPassphrase)
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(ssidInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        container.addView(passLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        container.addView(passInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        appDialogBuilder(this)
            .setTitle("配置家庭局域网 Wi-Fi 或改用免配置直连")
            .setMessage(
                "无线 CarPlay 蓝牙握手时需将路由器 Wi-Fi 名称告知 iPhone。\n" +
                    "• 推荐使用「Wi-Fi Direct 5GHz 直连」，无需填写任何路由器信息即可自动建联。\n" +
                    "• 若使用「家庭局域网模式」，请在下方填写 iPhone 所连的路由器 Wi-Fi 名称与密码。",
            )
            .setView(container)
            .setPositiveButton("保存并启动局域网投屏") { _, _ ->
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
            }
            .setNeutralButton("改用 Wi-Fi Direct 直连（推荐）") { _, _ ->
                settings.connection.wirelessHotspotMode = TvWirelessMode.WIFI_DIRECT
                refreshWorkbenchStatus()
                settingsPane.refresh()
                if (launchAfterSave) {
                    launchProjectionStage(wireless = true)
                }
            }
            .setNegativeButton("取消", null)
            .create()
            .also(::showTrackedDialog)
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
        val options = arrayOf(
            "开启本机蓝牙可见（推荐：让 iPhone 在蓝牙设置中直接搜到「$btName」）",
            "打开电视系统蓝牙设置（进入系统蓝牙界面配对）",
        )
        appDialogBuilder(this)
            .setTitle("电视与 iPhone 蓝牙配对")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> requestBluetoothDiscoverable(launchStageAfter = false)
                    1 -> launchSystemBluetoothSettings()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
        val changelogText = release.changelog.ifBlank { "性能优化与稳定性改进" }
        val mandatoryHint = if (checkResult.forceUpdate) "\n\n【重要提示】该版本为强制更新版本，请立即完成升级。" else ""
        val message = buildString {
            append("发现新版本：v${release.versionName} (Build ${release.versionCode})\n")
            append("当前版本：v${installed.versionName} (Build ${installed.versionCode})\n")
            append("更新通道：${release.channel} · 安装包大小：$sizeText\n")
            if (!release.publishedAt.isNullOrBlank()) {
                append("发布时间：${release.publishedAt}\n")
            }
            append("\n更新说明：\n$changelogText")
            append(mandatoryHint)
        }

        val builder = appDialogBuilder(this)
            .setTitle("发现 TVPlay 内网 OTA 更新")
            .setMessage(message)
            .setCancelable(!checkResult.forceUpdate)
            .setPositiveButton("立即下载并升级") { _, _ ->
                startOtaDownloadAndInstall(checkResult)
            }
        if (!checkResult.forceUpdate) {
            builder.setNegativeButton("稍后提醒", null)
        }
        showTrackedDialog(builder.create())
    }

    private fun startOtaDownloadAndInstall(checkResult: LanOtaCheckResult) {
        val release = checkResult.release ?: return
        val density = resources.displayMetrics.density
        val palette = TvColorScheme.resolve(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = (24 * density).toInt()
            val padV = (16 * density).toInt()
            setPadding(padH, padV, padH, padV)
        }
        val statusText = TextView(this).apply {
            text = "正在连接内网 OTA 服务器下载 v${release.versionName}..."
            setTextColor(palette.textPrimary)
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }
        val detailText = TextView(this).apply {
            text = "0% · 支持 HTTP Range 断点续传与 SHA-256 完整性校验"
            setTextColor(palette.textSecondary)
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        container.addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        container.addView(progressBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        container.addView(detailText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val progressDialog = appDialogBuilder(this)
            .setTitle("正在下载 TVPlay v${release.versionName}")
            .setView(container)
            .setCancelable(false)
            .create()
        showTrackedDialog(progressDialog)

        val appContext = applicationContext
        TvLanOtaManager.runInBackground {
            runCatching {
                TvLanOtaManager.downloadRelease(appContext, checkResult) { progress ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed || !progressDialog.isShowing) return@runOnUiThread
                        progressBar.progress = progress.percent
                        val downloadedStr = LanOtaProtocol.formatFileSize(progress.downloadedBytes)
                        val totalStr = if (progress.totalBytes > 0L) {
                            LanOtaProtocol.formatFileSize(progress.totalBytes)
                        } else {
                            "未知大小"
                        }
                        val resumeTag = if (progress.resumedFromBytes > 0L) "（已断点续传）" else ""
                        statusText.text = "正在下载安装包 $resumeTag..."
                        detailText.text = "${progress.percent}% ($downloadedStr / $totalStr)"
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
                    appDialogBuilder(this)
                        .setTitle("内网 OTA 更新下载失败")
                        .setMessage("原因：${error.message ?: error.javaClass.simpleName}\n\n已保留已下载分片，可点击重试继续断点续传。")
                        .setPositiveButton("断点续传重试") { _, _ ->
                            startOtaDownloadAndInstall(checkResult)
                        }
                        .setNegativeButton("取消", null)
                        .create()
                        .also(::showTrackedDialog)
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
