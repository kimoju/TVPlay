package app.tvplay.ui.workbench

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.usb.UsbManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.setPadding
import app.tvplay.R
import app.tvplay.config.TvPlayBluetooth
import app.tvplay.config.TvPreferencesStore
import app.tvplay.config.TvSettingsRepository
import app.tvplay.config.WiredPermissions
import app.tvplay.config.WirelessPermissions
import app.tvplay.engine.network.BluetoothRadioCheck
import app.tvplay.engine.network.IphoneUsbMatcher
import app.tvplay.engine.network.UsbTunnelVpnService
import app.tvplay.runtime.CarPlayBackgroundSession
import app.tvplay.runtime.DiagnosticExportStore
import app.tvplay.runtime.PendingReconnect
import app.tvplay.runtime.ThemeModeDiagnostics
import app.tvplay.runtime.TvDiagnosticsLogger
import app.tvplay.ui.stage.CarPlayScreen
import app.tvplay.ui.theme.AppAppearance
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
 * Replaces the legacy car-oriented scrolling form with an Apple TV / Android TV dual-pane layout:
 * - Left pane ([WorkbenchHeroStage]): Live receiver stage, 3-step connection guide, and primary Launch CTA.
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
    private val themeModeDiagnostics = ThemeModeDiagnostics()
    private var pendingLaunchWireless: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = TvSettingsRepository(this)
        appNight = resolveAppNightNow()
        AppAppearanceRuntime.publishHost(this, appNight)
        setTheme(if (appNight) R.style.Theme_TVPlay else R.style.Theme_TVPlay_Light)
        super.onCreate(savedInstanceState)

        observeAppearance(ThemeModeDiagnostics.Source.CREATE)
        buildWorkbenchLayout()

        if (savedInstanceState == null && isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession()) {
            startActivity(CarPlayScreen.intent(this))
            finish()
            return
        }

        if (savedInstanceState == null && shouldAutoLaunchProjection(intent)) {
            val wireless = intent?.getBooleanExtra(
                "wireless",
                TvPreferencesStore.loadAutoConnectWireless(this),
            ) ?: settings.connection.wirelessEnabled
            launchProjectionStage(wireless = wireless)
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
            launchProjectionStage(wireless = wireless)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
            launchProjectionStage(wireless = settings.connection.wirelessEnabled)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun buildWorkbenchLayout() {
        val density = resources.displayMetrics.density
        val palette = TvColorScheme.resolve(this)

        val rootRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.FILL
            setBackgroundColor(palette.background)
            setPadding((28 * density).toInt())
        }

        stagePane = WorkbenchHeroStage(this, settings).apply {
            onLaunchProjection = {
                launchProjectionStage(wireless = settings.connection.wirelessEnabled)
            }
            onToggleTransportMode = {
                settings.connection.wirelessEnabled = !settings.connection.wirelessEnabled
                if (CarPlayBackgroundSession.hasSession()) {
                    PendingReconnect.mark()
                }
                refreshWorkbenchStatus()
                settingsPane.refresh()
            }
        }

        settingsPane = WorkbenchSettingsTabs(this, settings).apply {
            onChooseIphoneClicked = { showChooseIphoneDialog() }
            onPairBluetoothClicked = { openSystemBluetoothSettings() }
            onSaveDiagnosticLogClicked = { exportDiagnosticReport() }
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
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            return true
        }
        if (intent.hasExtra("wireless") || intent.hasExtra("auto_connect_token")) {
            return true
        }
        return false
    }

    private fun launchProjectionStage(wireless: Boolean) {
        settings.connection.wirelessEnabled = wireless
        val missingPermissions = if (wireless) {
            WirelessPermissions.missing(
                context = this,
                wirelessMode = settings.connection.wirelessHotspotMode,
            )
        } else {
            WiredPermissions.missing(this)
        }
        if (missingPermissions.isNotEmpty()) {
            pendingLaunchWireless = wireless
            requestPermissions(missingPermissions.toTypedArray(), REQUEST_PROJECTION_PERMISSIONS)
            return
        }

        if (wireless && !BluetoothRadioCheck.isOn(this)) {
            Toast.makeText(
                this,
                getString(R.string.turn_on_bluetooth_before_starting_wireless_carplay),
                Toast.LENGTH_LONG,
            ).show()
            openSystemBluetoothSettings()
            return
        }

        if (!wireless) {
            val vpnPrepare = runCatching { VpnService.prepare(this) }.getOrNull()
            if (vpnPrepare != null) {
                pendingLaunchWireless = false
                startActivityForResult(vpnPrepare, REQUEST_VPN_PERMISSION)
                return
            }
        }

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
        if (requestCode == REQUEST_PROJECTION_PERMISSIONS) {
            val wireless = pendingLaunchWireless ?: settings.connection.wirelessEnabled
            pendingLaunchWireless = null
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                launchProjectionStage(wireless)
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.permissions_are_required_to_start_carplay),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_VPN_PERMISSION -> {
                val wireless = pendingLaunchWireless ?: false
                pendingLaunchWireless = null
                if (resultCode == RESULT_OK) {
                    startActivity(
                        CarPlayScreen.intent(this)
                            .putExtra("wireless", wireless),
                    )
                }
            }
            REQUEST_SAVE_DIAGNOSTIC_LOG -> {
                val uri = data?.data ?: return
                val report = TvDiagnosticsLogger.buildReport(this)
                runCatching {
                    DiagnosticExportStore.write(contentResolver, uri, report)
                    Toast.makeText(this, "Diagnostic report saved", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        "Failed to save diagnostic report: ${error.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun showChooseIphoneDialog() {
        val bonded = TvPlayBluetooth.bondedDevices(this)
        if (bonded.isEmpty()) {
            appDialogBuilder(this)
                .setTitle("No Paired Bluetooth Devices")
                .setMessage(
                    "Pair your iPhone in Android TV Bluetooth settings first, then return here to select it.",
                )
                .setPositiveButton("Open Bluetooth Settings") { _, _ ->
                    openSystemBluetoothSettings()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val labels = bonded.map { "${it.name} (${it.address})" }.toTypedArray()
        val currentAddress = settings.connection.pairedPhoneAddress
        val checkedIndex = bonded.indexOfFirst { it.address.equals(currentAddress, ignoreCase = true) }

        appDialogBuilder(this)
            .setTitle("Choose Paired iPhone")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val chosen = bonded[which]
                settings.connection.savePairedPhone(chosen.address, chosen.name)
                if (CarPlayBackgroundSession.hasSession()) {
                    PendingReconnect.mark()
                }
                refreshWorkbenchStatus()
                settingsPane.refresh()
                dialog.dismiss()
            }
            .setNeutralButton("Bluetooth Settings") { _, _ ->
                openSystemBluetoothSettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openSystemBluetoothSettings() {
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
                    "Diagnostic report saved: ${saved.displayPath}",
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure { error ->
                Toast.makeText(
                    this,
                    "Could not export diagnostic report: ${error.message}",
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

    @Suppress("unused")
    private fun hasConnectedUsbIphone(): Boolean {
        val usb = getSystemService(UsbManager::class.java) ?: return false
        val matcher = IphoneUsbMatcher.appleVendor()
        return usb.deviceList.values.any { device ->
            matcher.matches(device.vendorId, device.productId)
        }
    }

    companion object {
        private const val REQUEST_PROJECTION_PERMISSIONS = 1001
        private const val REQUEST_VPN_PERMISSION = 1002
        private const val REQUEST_SAVE_DIAGNOSTIC_LOG = 1003

        fun isLauncherIntent(intent: Intent?): Boolean =
            intent?.action == Intent.ACTION_MAIN &&
                (
                    intent.hasCategory(Intent.CATEGORY_LAUNCHER) ||
                        intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    ) &&
                intent.extras?.containsKey("page") != true
    }
}
