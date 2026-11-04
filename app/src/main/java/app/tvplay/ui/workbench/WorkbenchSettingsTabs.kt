package app.tvplay.ui.workbench

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.setPadding
import app.tvplay.R
import app.tvplay.config.TvPreferencesStore
import app.tvplay.config.TvSettingsRepository
import app.tvplay.config.VideoSurfaceType
import app.tvplay.config.WaitingScreenColorPreset
import app.tvplay.engine.av.CarPlayPhysicalSizePreset
import app.tvplay.engine.av.DisplayScaleMath
import app.tvplay.engine.av.MediaAudioBuffer
import app.tvplay.engine.av.UiDensityScale
import app.tvplay.engine.network.WifiP2pChannels
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.remote.CarPlayNightMode
import app.tvplay.ui.theme.AppAppearance
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.ui.theme.TvPlayButton
import app.tvplay.ui.theme.TvPlayButtonKind

enum class SettingsCategory(val title: String) {
    CONNECTION("Connection"),
    DISPLAY("Display & Video"),
    AUDIO("Audio & Mic"),
    ABOUT("System & Logs"),
}

typealias WorkbenchSettingsPane = WorkbenchSettingsTabs

/**
 * Right-pane tabbed settings panel for the 10-foot Android TV workbench.
 *
 * Designed specifically for D-pad navigation with clear focus rings, high-contrast cards,
 * and structured categories ([SettingsCategory]).
 */
class WorkbenchSettingsTabs(
    private val context: Context,
    private val settings: TvSettingsRepository,
) {
    private val density = context.resources.displayMetrics.density
    private val palette = TvColorScheme.resolve(context)

    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * density
            setColor(palette.surfaceContainer)
            setStroke((1f * density).toInt().coerceAtLeast(1), palette.outline)
        }
        setPadding(dp(22), dp(20), dp(22), dp(20))
    }

    private val tabStripRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val tabButtons = LinkedHashMap<SettingsCategory, TextView>()
    private val contentContainer = FrameLayout(context)
    private var selectedCategory: SettingsCategory = SettingsCategory.CONNECTION

    var onChooseIphoneClicked: (() -> Unit)? = null
    var onPairBluetoothClicked: (() -> Unit)? = null
    var onSaveDiagnosticLogClicked: (() -> Unit)? = null
    var onOpenPictureAdjustmentClicked: (() -> Unit)? = null
    var onSettingChanged: (() -> Unit)? = null
    var onAppearanceChanged: (() -> Unit)? = null

    init {
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerTitle = TextView(context).apply {
            text = "TV Receiver Settings"
            setTextColor(palette.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val headerHint = TextView(context).apply {
            text = "Use D-pad ▲▼◀▶ and OK to configure"
            setTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.END
        }
        headerRow.addView(headerTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        headerRow.addView(headerHint, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(headerRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        SettingsCategory.entries.forEachIndexed { index, category ->
            val tab = createTabChip(category)
            tabButtons[category] = tab
            tabStripRow.addView(
                tab,
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    if (index > 0) marginStart = dp(8)
                },
            )
        }
        tabScroll.addView(tabStripRow, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(
            tabScroll,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(14)
                bottomMargin = dp(14)
            },
        )

        root.addView(
            contentContainer,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )

        selectCategory(SettingsCategory.CONNECTION)
    }

    fun selectCategory(category: SettingsCategory) {
        selectedCategory = category
        tabButtons.forEach { (cat, chip) ->
            styleTabChip(chip, selected = cat == category, focused = chip.isFocused)
        }
        contentContainer.removeAllViews()
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = true
            clipToPadding = false
        }
        val page = when (category) {
            SettingsCategory.CONNECTION -> buildConnectionPage()
            SettingsCategory.DISPLAY -> buildDisplayPage()
            SettingsCategory.AUDIO -> buildAudioPage()
            SettingsCategory.ABOUT -> buildAboutPage()
        }
        scroll.addView(page, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        contentContainer.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun refresh() {
        selectCategory(selectedCategory)
    }

    private fun createTabChip(category: SettingsCategory): TextView =
        TextView(context).apply {
            text = category.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            setOnClickListener { selectCategory(category) }
            setOnFocusChangeListener { _, hasFocus ->
                styleTabChip(this, selected = selectedCategory == category, focused = hasFocus)
                if (hasFocus && selectedCategory != category) {
                    selectCategory(category)
                }
            }
            styleTabChip(this, selected = selectedCategory == category, focused = false)
        }

    private fun styleTabChip(view: TextView, selected: Boolean, focused: Boolean) {
        val fillColor = when {
            selected -> palette.accent
            focused -> palette.surfaceCardElevated
            else -> palette.surfaceCard
        }
        val textColor = if (selected) 0xFFFFFFFF.toInt() else palette.textPrimary
        val strokeColor = if (focused) 0xFFFFFFFF.toInt() else if (selected) palette.accent else palette.outline
        val strokeWidth = if (focused) dp(2) else dp(1)
        view.setTextColor(textColor)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f * density
            setColor(fillColor)
            setStroke(strokeWidth, strokeColor)
        }
        view.animate()
            .scaleX(if (focused) 1.04f else 1.0f)
            .scaleY(if (focused) 1.04f else 1.0f)
            .setDuration(110L)
            .start()
    }

    private fun buildConnectionPage(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(4), dp(4), dp(16))

        addView(sectionHeader("Connection Transport"))
        addView(
            cycleCard(
                title = "CarPlay Transport Mode",
                subtitle = "Choose Wireless (Bluetooth + Wi-Fi) or USB Cable connection",
                options = listOf(true to "Wireless (Wi-Fi + Bluetooth)", false to "USB Cable"),
                current = settings.connection.wirelessEnabled,
            ) { enabled ->
                settings.connection.wirelessEnabled = enabled
                onSettingChanged?.invoke()
                refresh()
            },
            cardLayoutParams(),
        )

        val phoneAddress = settings.connection.pairedPhoneAddress
        val phoneSubtitle = if (phoneAddress != null) {
            "Selected: ${settings.connection.pairedPhoneName} ($phoneAddress)"
        } else {
            "No iPhone selected yet — pair in Android TV Bluetooth settings first"
        }
        addView(
            actionCard(
                title = "Paired iPhone",
                subtitle = phoneSubtitle,
                buttonLabel = "Choose iPhone",
                onClick = { onChooseIphoneClicked?.invoke() },
            ),
            cardLayoutParams(),
        )

        addView(
            actionCard(
                title = "System Bluetooth Pairing",
                subtitle = "Open Android TV Bluetooth settings to pair a new iPhone or remote",
                buttonLabel = "Bluetooth Settings",
                onClick = { onPairBluetoothClicked?.invoke() },
            ),
            cardLayoutParams(),
        )

        addView(sectionHeader("Wireless Link Mode (TV Optimized)"))
        val wirelessModes = listOf(
            TvWirelessMode.LAN_WIFI to "Home LAN / Shared Wi-Fi (Recommended for TV)",
            TvWirelessMode.WIFI_DIRECT to "Wi-Fi Direct P2P Group",
        )
        addView(
            cycleCard(
                title = "Wireless Network Path",
                subtitle = "Home LAN uses your existing router/Ethernet; Wi-Fi Direct creates a P2P link",
                options = wirelessModes,
                current = settings.connection.wirelessHotspotMode,
            ) { mode ->
                settings.connection.wirelessHotspotMode = mode
                onSettingChanged?.invoke()
                refresh()
            },
            cardLayoutParams(),
        )

        when (settings.connection.wirelessHotspotMode) {
            TvWirelessMode.LAN_WIFI -> {
                addView(
                    textFieldCard(
                        title = "Home Wi-Fi SSID (for iPhone handoff)",
                        subtitle = "The Wi-Fi network your iPhone is connected to on the same LAN",
                        initialValue = settings.connection.existingWifiSsid,
                        hint = "Home Wi-Fi network name",
                        secret = false,
                    ) { value ->
                        settings.connection.existingWifiSsid = value.trim()
                        onSettingChanged?.invoke()
                    },
                    cardLayoutParams(),
                )
                addView(
                    textFieldCard(
                        title = "Home Wi-Fi Password (Optional)",
                        subtitle = "Sent to iPhone during Bluetooth iAP2 wireless handoff",
                        initialValue = settings.connection.existingWifiPassphrase,
                        hint = "Leave empty if iPhone already joined",
                        secret = true,
                    ) { value ->
                        settings.connection.existingWifiPassphrase = value
                        onSettingChanged?.invoke()
                    },
                    cardLayoutParams(),
                )
                addView(
                    textFieldCard(
                        title = "Network Interface Override (Advanced)",
                        subtitle = "Optional interface name (e.g. wlan0 or eth0). Leave blank for automatic.",
                        initialValue = settings.connection.existingWifiInterface,
                        hint = "Auto (wlan0 / eth0)",
                        secret = false,
                    ) { value ->
                        settings.connection.existingWifiInterface = value.trim()
                        onSettingChanged?.invoke()
                    },
                    cardLayoutParams(),
                )
            }

            TvWirelessMode.WIFI_DIRECT -> {
                val selectableChannels = listOf(WifiP2pChannels.AUTO) +
                    WifiP2pChannels.bandChoices +
                    WifiP2pChannels.channels
                val channelOptions = selectableChannels.map { channel ->
                    channel to when (channel) {
                        WifiP2pChannels.AUTO -> "Auto (5 GHz preferred)"
                        WifiP2pChannels.AUTO_5_GHZ -> "Auto · 5 GHz only"
                        WifiP2pChannels.AUTO_2_4_GHZ -> "Auto · 2.4 GHz only"
                        else -> "Channel $channel (${WifiP2pChannels.frequencyMhz(channel)} MHz)"
                    }
                }
                addView(
                    cycleCard(
                        title = "Wi-Fi Direct Preferred Channel",
                        subtitle = "Select a clean 5 GHz channel for low-latency CarPlay projection",
                        options = channelOptions,
                        current = settings.connection.wifiP2pPreferredChannel,
                    ) { channel ->
                        settings.connection.wifiP2pPreferredChannel = channel
                        onSettingChanged?.invoke()
                    },
                    cardLayoutParams(),
                )
            }
        }

        addView(sectionHeader("Startup & Auto-Connect"))
        addView(
            toggleCard(
                title = "Auto-Start TVPlay on Boot",
                subtitle = "Launch TVPlay automatically when Android TV powers on",
                checked = settings.connection.autoConnectOnBoot,
            ) { enabled ->
                settings.connection.autoConnectOnBoot = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "Auto-Connect When iPhone Bluetooth Connects",
                subtitle = "Wake CarPlay session automatically when your paired iPhone links over Bluetooth",
                checked = settings.connection.connectOnPhoneBluetooth,
            ) { enabled ->
                settings.connection.connectOnPhoneBluetooth = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
    }

    private fun buildDisplayPage(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(4), dp(4), dp(16))

        addView(sectionHeader("Video Decoder & Rendering"))
        addView(
            toggleCard(
                title = "HEVC (H.265) Video Stream",
                subtitle = "Higher visual sharpness at lower bitrate on 4K Android TV hardware decoders",
                checked = settings.display.hevcEnabled,
            ) { enabled ->
                settings.display.hevcEnabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "Allow Software HEVC Decoder",
                subtitle = "Fallback to CPU decoder if hardware HEVC decoder is unavailable",
                checked = settings.display.hevcSoftwareDecoderEnabled,
            ) { enabled ->
                settings.display.hevcSoftwareDecoderEnabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            cycleCard(
                title = "Video Surface Mode",
                subtitle = "Auto selects hardware SurfaceView unless picture color filter requires TextureView",
                options = listOf(
                    VideoSurfaceType.AUTO to "Auto (Recommended)",
                    VideoSurfaceType.SURFACE_VIEW to "SurfaceView (Lowest Latency)",
                    VideoSurfaceType.TEXTURE_VIEW to "TextureView (Color Filter Support)",
                ),
                current = settings.display.videoSurfaceType,
            ) { mode ->
                settings.display.videoSurfaceType = mode
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "Smooth Frame Pacing",
                subtitle = "Adds a 1-frame jitter buffer for smoother motion on 60 Hz TV panels",
                checked = settings.display.smoothVideoPlayback,
            ) { enabled ->
                settings.display.smoothVideoPlayback = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "60 FPS Projection",
                subtitle = "Request 60 fps CarPlay stream (disable for 30 fps on older TV sticks)",
                checked = settings.display.frameRate60Enabled,
            ) { enabled ->
                settings.display.frameRate60Enabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )

        addView(sectionHeader("TV Canvas & Layout Scaling"))
        addView(
            cycleCard(
                title = "Resolution Scale",
                subtitle = "Adjust CarPlay render canvas resolution relative to your TV screen",
                options = listOf(75, 85, 100, 115, 130).map { it to "$it%" },
                current = settings.display.displayScalePercent,
            ) { percent ->
                settings.display.displayScalePercent = percent
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            cycleCard(
                title = "UI Icon & Text Density",
                subtitle = "Scale CarPlay sidebar, icons, and labels for 10-foot viewing distance",
                options = UiDensityScale.presets.map { it to "$it%" },
                current = settings.display.uiDensityPercent,
            ) { percent ->
                settings.display.uiDensityPercent = percent
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            cycleCard(
                title = "CarPlay Screen Layout Preset",
                subtitle = "Emulate physical screen dimensions to control CarPlay widget & split-screen layout",
                options = CarPlayPhysicalSizePreset.entries.map { preset ->
                    preset to when (preset) {
                        CarPlayPhysicalSizePreset.LARGE -> "Large Controls (${preset.widthMillimeters} mm)"
                        CarPlayPhysicalSizePreset.MEDIUM -> "Balanced 10-Foot TV Default (${preset.widthMillimeters} mm)"
                        CarPlayPhysicalSizePreset.SMALL -> "Compact / Widescreen Dashboard (${preset.widthMillimeters} mm)"
                    }
                },
                current = settings.display.physicalSizePreset,
            ) { preset ->
                settings.display.physicalSizePreset = preset
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )

        addView(sectionHeader("TV Overscan / Safe Area"))
        addView(
            toggleCard(
                title = "Disable TV Safe-Area Insets",
                subtitle = "Use 100% edge-to-edge HDMI pixels without safe-area margin",
                checked = settings.display.mainDisplayDisableSafeArea,
            ) { disabled ->
                settings.display.mainDisplayDisableSafeArea = disabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "Draw CarPlay Wallpaper Outside Safe Area",
                subtitle = "Allow CarPlay background artwork to extend into overscan margins",
                checked = settings.display.safeAreaDrawOutside,
            ) { drawOutside ->
                settings.display.safeAreaDrawOutside = drawOutside
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )

        addView(sectionHeader("Appearance & Diagnostics"))
        addView(
            cycleCard(
                title = "CarPlay Appearance (Day / Night)",
                subtitle = "Control whether CarPlay maps and UI use Dark or Light mode",
                options = listOf(
                    CarPlayNightMode.NIGHT to "Always Dark (TV Default)",
                    CarPlayNightMode.SYSTEM to "Follow System / Time",
                    CarPlayNightMode.DAY to "Always Light",
                ),
                current = settings.display.carPlayNightMode,
            ) { mode ->
                settings.display.carPlayNightMode = mode
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            cycleCard(
                title = "TVPlay Workbench Theme",
                subtitle = "Appearance of the TVPlay setup workbench and dialogs",
                options = listOf(
                    AppAppearance.DARK to "Dark Slate (Recommended)",
                    AppAppearance.AUTO to "Follow Android TV",
                    AppAppearance.LIGHT to "Light",
                ),
                current = settings.display.appAppearance,
            ) { mode ->
                settings.display.appAppearance = mode
                onAppearanceChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            cycleCard(
                title = "Waiting Screen Background",
                subtitle = "Backdrop color while waiting for iPhone projection",
                options = listOf(
                    WaitingScreenColorPreset.DARK to "Theme Default (Dark Slate)",
                    WaitingScreenColorPreset.OLED_BLACK to "Pure OLED Black",
                    WaitingScreenColorPreset.MIDNIGHT_BLUE to "Midnight Blue",
                    WaitingScreenColorPreset.LIGHT to "Light Slate",
                ),
                current = settings.display.waitingScreenColorPreset,
            ) { preset ->
                settings.display.waitingScreenColorPreset = preset
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "On-Screen FPS Counter",
                subtitle = "Display real-time decoded frames per second in the top-right corner",
                checked = settings.display.fpsCounterEnabled,
            ) { enabled ->
                settings.display.fpsCounterEnabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
    }

    private fun buildAudioPage(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(4), dp(4), dp(16))

        addView(sectionHeader("TV Audio Output & Buffering"))
        val bufferOptions = MediaAudioBuffer.presets.map { ms ->
            ms to if (ms == MediaAudioBuffer.DEFAULT_MILLIS) {
                "$ms ms (Default)"
            } else {
                "$ms ms"
            }
        }
        addView(
            cycleCard(
                title = "Media Audio Buffer Size",
                subtitle = "Increase if wireless music stutters on congested Wi-Fi; decrease for tighter latency",
                options = bufferOptions,
                current = settings.audio.mediaBufferMillis,
            ) { ms ->
                settings.audio.mediaBufferMillis = ms
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "Manage Android Audio Focus",
                subtitle = "Request system audio focus when CarPlay plays music, Siri, or navigation prompts",
                checked = settings.audio.audioFocusEnabled,
            ) { enabled ->
                settings.audio.audioFocusEnabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "Auto-Yield Audio Focus When Idle",
                subtitle = "Release audio focus after CarPlay audio stops so background TV apps can resume",
                checked = settings.audio.audioFocusAutoYield,
            ) { enabled ->
                settings.audio.audioFocusAutoYield = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "Keep Media Keys Without Taking Audio Focus",
                subtitle = "Useful when mixing CarPlay navigation with another Android TV music app",
                checked = settings.audio.disableMediaKeyAudioFocus,
            ) { enabled ->
                settings.audio.disableMediaKeyAudioFocus = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )

        addView(sectionHeader("Voice Assistant & Microphone"))
        addView(
            toggleCard(
                title = "Enable Voice / Siri Microphone",
                subtitle = "Route Android TV / USB / Bluetooth remote microphone to CarPlay Siri & calls",
                checked = settings.audio.microphoneEnabled,
            ) { enabled ->
                settings.audio.microphoneEnabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "Buffered Main Audio Stream",
                subtitle = "Enable AirPlay buffered main audio stream support",
                checked = settings.audio.mainBufferedAudio,
            ) { enabled ->
                settings.audio.mainBufferedAudio = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            toggleCard(
                title = "Keep Audio on iPhone / External Bluetooth Speaker",
                subtitle = "Disable TV audio output so sound stays on iPhone or Bluetooth soundbar",
                checked = settings.audio.disableAudioOutput,
            ) { disabled ->
                settings.audio.disableAudioOutput = disabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
    }

    private fun buildAboutPage(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(4), dp(4), dp(16))

        addView(sectionHeader("Accessory Identity"))
        addView(
            textFieldCard(
                title = "Reported Manufacturer",
                subtitle = "Accessory manufacturer name presented during iAP2 identification",
                initialValue = settings.identity.manufacturer,
                hint = "Apple",
                secret = false,
            ) { value ->
                settings.identity.manufacturer = value.ifBlank { "Apple" }
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            textFieldCard(
                title = "Reported Model",
                subtitle = "Accessory model identifier presented during iAP2 identification",
                initialValue = settings.identity.model,
                hint = "AppleTV",
                secret = false,
            ) { value ->
                settings.identity.model = value.ifBlank { "AppleTV" }
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )

        addView(sectionHeader("Diagnostics & Troubleshooting"))
        addView(
            toggleCard(
                title = "Capture Verbose Session Diagnostics",
                subtitle = "Record redacted RTSP/iAP2/decoder diagnostic events for troubleshooting",
                checked = settings.identity.debugLogsEnabled,
            ) { enabled ->
                settings.identity.debugLogsEnabled = enabled
                onSettingChanged?.invoke()
            },
            cardLayoutParams(),
        )
        addView(
            actionCard(
                title = "Export Diagnostic Report",
                subtitle = "Save a redacted diagnostic log bundle to storage or share it",
                buttonLabel = "Export Log",
                onClick = { onSaveDiagnosticLogClicked?.invoke() },
            ),
            cardLayoutParams(),
        )
    }

    private fun sectionHeader(title: String): TextView =
        TextView(context).apply {
            text = title.uppercase()
            setTextColor(palette.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(dp(4), dp(12), dp(4), dp(6))
        }

    private fun cardLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        }

    private fun toggleCard(
        title: String,
        subtitle: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
    ): LinearLayout {
        var current = checked
        val switchView = Switch(context).apply {
            isFocusable = false
            isClickable = false
            isChecked = current
        }
        return focusableCard(
            onClick = {
                current = !current
                switchView.isChecked = current
                onToggle(current)
            },
        ).apply {
            addView(
                cardTitleColumn(title, subtitle),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(14) },
            )
            addView(switchView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    private fun <T> cycleCard(
        title: String,
        subtitle: String,
        options: List<Pair<T, String>>,
        current: T,
        onSelected: (T) -> Unit,
    ): LinearLayout {
        var index = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
        val valueBadge = TextView(context).apply {
            text = "◀  ${options.getOrNull(index)?.second.orEmpty()}  ▶"
            setTextColor(palette.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * density
                setColor(palette.surfaceCardElevated)
                setStroke((1f * density).toInt().coerceAtLeast(1), palette.outline)
            }
        }

        fun step(delta: Int) {
            if (options.isEmpty()) return
            index = (index + delta + options.size) % options.size
            val chosen = options[index]
            valueBadge.text = "◀  ${chosen.second}  ▶"
            onSelected(chosen.first)
        }

        return focusableCard(
            onClick = { step(1) },
            onHorizontalKey = { keyCode ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        step(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        step(1)
                        true
                    }
                    else -> false
                }
            },
        ).apply {
            addView(
                cardTitleColumn(title, subtitle),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(14) },
            )
            addView(valueBadge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    private fun actionCard(
        title: String,
        subtitle: String,
        buttonLabel: String,
        onClick: () -> Unit,
    ): LinearLayout {
        val pill = TvPlayButton(context, TvPlayButtonKind.SECONDARY).apply {
            text = buttonLabel
            isFocusable = false
            isClickable = false
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        return focusableCard(onClick = onClick).apply {
            addView(
                cardTitleColumn(title, subtitle),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(14) },
            )
            addView(pill, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

    private fun textFieldCard(
        title: String,
        subtitle: String,
        initialValue: String,
        hint: String,
        secret: Boolean,
        onChanged: (String) -> Unit,
    ): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = cardBackground(focused = false)
        }
        card.addView(cardTitleColumn(title, subtitle), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val editText = EditText(context).apply {
            setText(initialValue)
            this.hint = hint
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine(true)
            inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(palette.surfaceCardElevated)
                setStroke((1f * density).toInt().coerceAtLeast(1), palette.outline)
            }
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setOnFocusChangeListener { _, hasFocus ->
                card.background = cardBackground(focused = hasFocus)
                if (!hasFocus) {
                    onChanged(text?.toString().orEmpty())
                }
            }
        }
        card.addView(
            editText,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) },
        )
        return card
    }

    private fun cardTitleColumn(title: String, subtitle: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(context).apply {
                    text = title
                    setTextColor(palette.textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            addView(
                TextView(context).apply {
                    text = subtitle
                    setTextColor(palette.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setLineSpacing(0f, 1.15f)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(3) },
            )
        }

    private fun focusableCard(
        onClick: () -> Unit,
        onHorizontalKey: ((Int) -> Boolean)? = null,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            background = cardBackground(focused = false)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, hasFocus ->
                background = cardBackground(focused = hasFocus)
                animate()
                    .scaleX(if (hasFocus) 1.015f else 1.0f)
                    .scaleY(if (hasFocus) 1.015f else 1.0f)
                    .setDuration(110L)
                    .start()
            }
            if (onHorizontalKey != null) {
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        onHorizontalKey(keyCode)
                    } else {
                        false
                    }
                }
            }
        }

    private fun cardBackground(focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(if (focused) palette.surfaceCardElevated else palette.surfaceCard)
            setStroke(
                if (focused) dp(2) else dp(1),
                if (focused) palette.accent else palette.outline,
            )
        }

    private fun dp(value: Int): Int = (value * density).toInt()
}
