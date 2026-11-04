package app.tvplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsControllerCompat
import app.tvplay.orchestration.WirelessHotspotMode
import app.tvplay.settings.TvPlaySettingsRepository
import app.tvplay.ui.workbench.WorkbenchSettingsPane
import app.tvplay.ui.workbench.WorkbenchStagePane
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class SettingsCategory(val title: String) {
    CONNECTION("连接设置"),
    DISPLAY("显示与画质"),
    AUDIO("音频与语音"),
    ABOUT("关于与诊断"),
}

/**
 * TVPlay 10-Foot Dual-Pane Workbench Activity for Android TV, TV Boxes, Projectors, and Large Screens.
 *
 * Left pane: Live CarPlay connection hero stage, 3-step TV connection guide, and primary action buttons ([WorkbenchStagePane]).
 * Right pane: D-pad friendly 4-tab settings workbench ([WorkbenchSettingsPane]).
 */
class TvPlayWorkbenchActivity : ComponentActivity(), AppAppearanceOwner {
    override var currentAppNight: Boolean = true
        private set

    private var palette: TvPlayPalette = TvPlayPalette.DARK
    private val BG get() = palette.background
    private val TEXT get() = palette.primaryText
    private val MUTED get() = palette.secondaryText

    private var page: String = "home"
    private var selectedCategory: SettingsCategory = SettingsCategory.CONNECTION
    private var connectionSettingsReturnCategory: SettingsCategory? = null
    private var setupError: String? = null
    private var backupServer: Any? = null
    private var removeHostAppearanceObserver: (() -> Unit)? = null

    private val settingsRepository by lazy { TvPlaySettingsRepository(this) }
    private val stagePane by lazy { WorkbenchStagePane(this, settingsRepository) }
    private val settingsPane by lazy { WorkbenchSettingsPane(this, settingsRepository) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            TvPlayBootstrap.ensure(applicationContext, settingsRepository.identity.mfiTarget)
            setupError = null
        } catch (error: Exception) {
            setupError = error.message ?: "无法加载内置离线 MFi 认证证书"
        }

        if (savedInstanceState != null) {
            page = sanitizePage(savedInstanceState.getString("page"))
            savedInstanceState.getString("category")?.let { saved ->
                SettingsCategory.entries.firstOrNull { it.name == saved }?.let { selectedCategory = it }
            }
        } else if (intent?.getStringExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT) == PhoneBluetoothReceiver.launchToken) {
            page = "home"
            openCarPlayScreen(wireless = true)
        } else if (isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession()) {
            openCarPlayScreen()
            finish()
            return
        } else {
            intent?.getStringExtra("page")?.let { page = sanitizePage(it) }
        }

        removeHostAppearanceObserver = AppAppearanceRuntime.observeHost { hostNight ->
            val resolved = resolveAppNightNow(hostNight)
            if (resolved != currentAppNight) {
                render()
            }
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page)
        outState.putString("category", selectedCategory.name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT) == PhoneBluetoothReceiver.launchToken) {
            page = "home"
            openCarPlayScreen(wireless = true)
            render()
            return
        }
        if (isLauncherIntent(intent)) {
            page = "home"
            connectionSettingsReturnCategory = null
            if (CarPlayBackgroundSession.hasSession()) {
                openCarPlayScreen()
                return
            }
            render()
            return
        }
        if (intent.extras?.containsKey("page") == true) {
            page = sanitizePage(intent.getStringExtra("page"))
            render()
        }
    }

    private fun sanitizePage(raw: String?): String = when (raw) {
        "home", "settings", "connection", "wireless-recovery" -> raw
        else -> "home"
    }

    override fun onResume() {
        super.onResume()
        render()
        maybeAutoConnectOnHome()
    }

    override fun onDestroy() {
        removeHostAppearanceObserver?.invoke()
        removeHostAppearanceObserver = null
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openCarPlayScreen()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun maybeAutoConnectOnHome() {
        if (page != "home" || setupError != null || isFinishing) return
        if (!settingsRepository.connection.autoConnect) return
        if (CarPlayBackgroundSession.hasSession()) {
            openCarPlayScreen()
            return
        }
        val wireless = settingsRepository.connection.autoConnectWireless
        if (wireless) {
            val mode = settingsRepository.connection.wirelessHotspotMode
            val hasPhone = !settingsRepository.connection.pairedPhoneAddress.isNullOrBlank()
            if (hasPhone || mode == WirelessHotspotMode.EXISTING_WIFI || mode == WirelessHotspotMode.WIFI_P2P) {
                openCarPlayScreen(wireless = true)
            }
        } else {
            openCarPlayScreen(wireless = false)
        }
    }

    private fun openCarPlayScreen(wireless: Boolean? = null, pictureControls: Boolean = false) {
        val targetWireless = wireless ?: settingsRepository.connection.autoConnectWireless
        settingsRepository.connection.wirelessEnabled = targetWireless
        val launch = CarPlayScreen.intent(this).apply {
            putExtra("wireless", targetWireless)
            if (pictureControls) {
                putExtra("picture_controls", true)
            }
        }
        startActivity(launch)
    }

    private fun markReconnectNeeded() {
        CarPlayBackgroundSession.currentController()?.let(PendingReconnect::mark)
        render()
    }

    private fun reconnectIfRunning() {
        PendingReconnect.clear()
        if (CarPlayBackgroundSession.hasSession()) {
            CarPlayBackgroundSession.stop {
                openCarPlayScreen()
            }
        } else {
            openCarPlayScreen()
        }
    }

    private fun applyPalette() {
        currentAppNight = resolveAppNightNow()
        palette = TvPlayPalette.of(currentAppNight)
        window?.let { win ->
            win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            win.statusBarColor = palette.systemBar
            @Suppress("DEPRECATION")
            win.navigationBarColor = palette.systemBar
            WindowInsetsControllerCompat(win, win.decorView).apply {
                isAppearanceLightStatusBars = palette.systemBarIconsAreDark
                isAppearanceLightNavigationBars = palette.systemBarIconsAreDark
            }
        }
    }

    private fun render() {
        applyPalette()
        val widthDp = resources.configuration.screenWidthDp
        val dualPane = widthDp >= 800

        val root = FrameLayout(this).apply {
            setBackgroundColor(BG)
        }

        val leftStageView = stagePane.build(
            palette = palette,
            setupError = setupError,
            onOpenCarPlay = { openCarPlayScreen() },
            onReconnectIfRunning = { reconnectIfRunning() },
            onMarkReconnectNeeded = { markReconnectNeeded() },
            onOpenSystemIntent = { openSystem(it) },
            onSessionStopped = { render() },
        )

        val tabBarView = settingsPane.buildTabBar(selectedCategory, palette, settingsCallbacks)
        val tabContentView = settingsPane.buildContent(selectedCategory, palette, settingsCallbacks)

        if (dualPane) {
            val mainRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(28), dp(24), dp(28), dp(24))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }

            val leftScroll = ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f).apply {
                    marginEnd = dp(24)
                }
                addView(leftStageView)
            }

            val rightColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f)
            }
            rightColumn.addView(tabBarView)
            val rightScroll = ScrollView(this).apply {
                isFillViewport = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ).apply {
                    topMargin = dp(16)
                }
                addView(tabContentView)
            }
            rightColumn.addView(rightScroll)

            mainRow.addView(leftScroll)
            mainRow.addView(rightColumn)
            root.addView(mainRow)
        } else {
            val singleScroll = ScrollView(this).apply {
                isFillViewport = true
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            val column = column().apply {
                setPadding(dp(20), dp(20), dp(20), dp(24))
            }
            column.addView(leftStageView)
            column.addView(space(dp(20)))
            column.addView(tabBarView)
            column.addView(space(dp(16)))
            column.addView(tabContentView)
            singleScroll.addView(column)
            root.addView(singleScroll)
        }

        setContentView(root)
    }

    private val settingsCallbacks = object : WorkbenchSettingsPane.Callbacks {
        override fun onCategorySelected(category: SettingsCategory) {
            selectedCategory = category
            render()
        }

        override fun onRenderRequested() {
            render()
        }

        override fun onMarkReconnectNeeded() {
            markReconnectNeeded()
        }

        override fun onShowExistingWifiDialog() {
            showExistingWifiDialog()
        }

        override fun onShowPairedPhonePickerDialog() {
            showPairedPhonePickerDialog()
        }

        override fun onRequestPermissions(permissions: Array<String>) {
            permissionLauncher.launch(permissions)
        }

        override fun onOpenPictureControls() {
            openCarPlayScreen(pictureControls = true)
        }

        override fun onShowDiagnosticReport() {
            showDiagnosticReport(buildDiagnosticReportText())
        }

        override fun onExportDiagnosticReport() {
            exportDiagnosticReport()
        }

        override fun onToast(message: String) {
            toast(message)
        }
    }

    // --- Dialogs & Diagnostics ---

    private fun showExistingWifiDialog() {
        val currentSsid = settingsRepository.connection.existingWifiSsid
        val currentPass = settingsRepository.connection.existingWifiPassphrase

        val body = column().apply {
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        body.addView(
            label(
                "当电视通过网线连接路由器、或系统未自动向应用报告 Wi-Fi 名称时，可手动填写家庭路由器的 Wi-Fi 名称 (SSID)，以便引导 iPhone 在同一局域网握手。",
                14,
                MUTED,
            ),
        )
        body.addView(space(dp(12)))

        val ssidInput = EditText(appDialogContext()).apply {
            hint = "Wi-Fi 名称 (SSID)，留空则自动检测"
            setText(currentSsid)
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val passInput = EditText(appDialogContext()).apply {
            hint = "Wi-Fi 密码（可选）"
            setText(currentPass)
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        body.addView(ssidInput)
        body.addView(space(dp(8)))
        body.addView(passInput)

        appDialogBuilder()
            .setTitle("家庭局域网 Wi-Fi 设置")
            .setView(body)
            .setPositiveButton("保存") { _, _ ->
                settingsRepository.connection.saveExistingWifiCredentials(
                    ssidInput.text?.toString()?.trim().orEmpty(),
                    passInput.text?.toString().orEmpty(),
                )
                markReconnectNeeded()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun showPairedPhonePickerDialog() {
        val bonded = runCatching {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            adapter?.bondedDevices?.mapNotNull { device ->
                val addr = device.address ?: return@mapNotNull null
                val name = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: addr
                name to addr
            }.orEmpty()
        }.getOrDefault(emptyList())

        val labels = mutableListOf("自动连接任意已配对 iPhone")
        bonded.forEach { (name, addr) -> labels.add("$name ($addr)") }

        appDialogBuilder()
            .setTitle("选择目标配对 iPhone")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    settingsRepository.connection.savePairedPhone("", "Your iPhone")
                } else {
                    val (name, addr) = bonded[which - 1]
                    settingsRepository.connection.savePairedPhone(addr, name)
                }
                render()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun buildDiagnosticReportText(): String = buildString {
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "1.0"
        appendLine("TVPlay $versionName · diagnostic report")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Screen: ${CarPlayScreen.report(this@TvPlayWorkbenchActivity)}")
        appendLine(LastConnectionProblem.report(this@TvPlayWorkbenchActivity))
        appendLine()
        appendLine("--- Last display negotiation ---")
        appendLine(DisplayDiagnosticSnapshot.report(applicationContext))
        appendLine()
        appendLine("--- Last boot & launch snapshot ---")
        appendLine(StartupDiagnosticSnapshot.report(applicationContext))
        appendLine()
        appendLine("--- Process exit diagnostics ---")
        appendLine(ProcessExitDiagnostics.report(applicationContext))
        appendLine()
        appendLine("--- Last crash ---")
        appendLine(CrashRecord.report(applicationContext))
        appendLine()
        for (name in SessionLogFile.REPORT_NAMES) {
            val file = File(filesDir, "logs/$name")
            if (file.isFile) {
                appendLine("--- $name ---")
                file.useLines { lines ->
                    lines.forEach { line ->
                        DiagnosticRedactor.redact(line)?.let { appendLine(it) }
                    }
                }
            }
        }
    }

    private fun exportDiagnosticReport() {
        val report = buildDiagnosticReportText()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "TVPlay-report-$stamp.txt"
        runCatching {
            val saved = DiagnosticExportStore.saveWithoutPicker(this, fileName, report)
            val pathInfo = saved.savedPath ?: saved.uri.toString()
            toast("诊断报告已保存：$pathInfo")
        }.onFailure { error ->
            toast("导出诊断报告失败：${error.message}")
        }
    }

    /**
     * A selectable TextView cannot lay out a multi-megabyte report; the saved file keeps the full text.
     * The view keeps the head (everything above the first log section: version, head unit, settings, snapshots)
     * and the newest part, with a "…" line between them.
     */
    private fun reportTail(report: String): String {
        if (report.length <= REPORT_VIEW_MAX_CHARS) return report
        val cut = report.length - REPORT_VIEW_MAX_CHARS
        val lineStart = report.indexOf('\n', cut - 1) + 1
        // A line start that leaves little text (a long final line) is worse than a mid-line cut.
        val start = if (lineStart > 0 && report.length - lineStart >= REPORT_VIEW_MAX_CHARS / 2) lineStart
        else if (Character.isLowSurrogate(report[cut])) cut + 1 else cut
        val head = reportHead(report)
        if (head.length >= start) return report
        return head + "…\n" + report.substring(start)
    }

    private fun reportHead(report: String): String {
        val firstLog = SessionLogFile.REPORT_NAMES
            .map { name -> "--- $name ---" }
            .mapNotNull { marker ->
                if (report.startsWith(marker)) 0 else report.indexOf("\n$marker").takeIf { it >= 0 }?.plus(1)
            }
            .minOrNull() ?: return ""
        if (firstLog <= REPORT_HEAD_MAX_CHARS) return report.substring(0, firstLog)
        val lineEnd = report.lastIndexOf('\n', REPORT_HEAD_MAX_CHARS - 1) + 1
        val end = when {
            lineEnd > 0 -> lineEnd
            Character.isHighSurrogate(report[REPORT_HEAD_MAX_CHARS - 1]) -> REPORT_HEAD_MAX_CHARS - 1
            else -> REPORT_HEAD_MAX_CHARS
        }
        return report.substring(0, end)
    }

    private fun showDiagnosticReport(report: String) {
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.diagnostic_report_copy_hint), 14, MUTED))
        body.addView(label(reportTail(report), 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        appDialogBuilder().setTitle(getString(R.string.view_diagnostic_report))
            .setView(ScrollView(appDialogContext()).apply { addView(body) })
            .setPositiveButton(getString(R.string.close), null).show()
    }

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun label(text: String, sizeSp: Int, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
            if (bold) {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
        }

    private fun space(heightPx: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun openSystem(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            toast("无法直接打开该系统设置页面，请在电视系统设置中手动打开")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REPORT_VIEW_MAX_CHARS = 200_000
        private const val REPORT_HEAD_MAX_CHARS = 20_000

        internal fun isLauncherIntent(intent: Intent?): Boolean {
            if (intent == null || intent.action != Intent.ACTION_MAIN) return false
            val categories = intent.categories ?: return false
            val isLauncher = Intent.CATEGORY_LAUNCHER in categories ||
                Intent.CATEGORY_LEANBACK_LAUNCHER in categories
            if (!isLauncher) return false
            val extras = intent.extras
            return extras == null || !extras.containsKey("page")
        }
    }
}
