package app.tvplay.ui.theme

import android.content.Context
import android.os.Looper
import app.tvplay.config.*
import app.tvplay.remote.CarPlayNightMode
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet

typealias TvPlayPalette = TvColorScheme
typealias AppAppearanceMode = AppAppearance

/**
 * Colours for TVPlay's screens, dialogs and CarPlay overlays.
 *
 * Dark values match the existing palette byte-for-byte. Light values use warm neutral paper surfaces so
 * daytime screens do not look like a dimmed terminal, while keeping the blue primary action and red
 * destructive action recognizable.
 */
data class TvColorScheme(
    val night: Boolean,
    val background: Int,
    val surface: Int,
    val card: Int,
    val subtleSurface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val accent: Int,
    val onAccent: Int,
    val accentTrack: Int,
    val trackOff: Int,
    val button: Int,
    val buttonText: Int,
    val danger: Int,
    val onDanger: Int,
    val tonalDanger: Int,
    val onTonalDanger: Int,
    val tonalDangerOutline: Int,
    val outline: Int,
    val ripple: Int,
    /** High-contrast focus ring drawn around focused controls when navigating with a TV remote D-pad. */
    val focusRing: Int,
    val warningSurface: Int,
    val warningOutline: Int,
    val warningText: Int,
    val overlayScrim: Int,
    val overlayBackground: Int,
    val overlaySurface: Int,
    val overlayPrimaryText: Int,
    val overlaySecondaryText: Int,
    val overlayAccent: Int,
    val overlayAccentTrack: Int,
    val overlayTrackOff: Int,
    val overlayButton: Int,
    val overlayButtonText: Int,
    val overlayDanger: Int,
    val overlayOutline: Int,
) {
    val surfaceContainer: Int get() = surface
    val surfaceCard: Int get() = card
    val surfaceCardElevated: Int get() = button
    val textPrimary: Int get() = primaryText
    val textSecondary: Int get() = secondaryText

    companion object {
        fun of(night: Boolean): TvColorScheme = if (night) DARK else LIGHT

        fun resolve(context: Context): TvColorScheme =
            of((context as? AppAppearanceOwner)?.currentAppNight ?: context.resolveAppNightNow())

        val DARK = TvColorScheme(
            night = true,
            background = 0xFF0A0D12.toInt(),
            surface = 0xFF141922.toInt(),
            card = 0xFF1A2030.toInt(),
            subtleSurface = 0xFF0E131B.toInt(),
            primaryText = 0xFFF3F5F8.toInt(),
            secondaryText = 0xFF9AA4B2.toInt(),
            accent = 0xFF2B6EF2.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            accentTrack = 0x662B6EF2,
            trackOff = 0xFF39424E.toInt(),
            button = 0xFF232B3A.toInt(),
            buttonText = 0xFFF3F5F8.toInt(),
            danger = 0xFF9B2C2C.toInt(),
            onDanger = 0xFFFFFFFF.toInt(),
            tonalDanger = 0xFF28191D.toInt(),
            onTonalDanger = 0xFFF2B8B5.toInt(),
            tonalDangerOutline = 0xFF5B2C31.toInt(),
            outline = 0xFF2A3346.toInt(),
            ripple = 0x33FFFFFF,
            focusRing = 0xFF60A5FA.toInt(),
            warningSurface = 0xFF2B2112.toInt(),
            warningOutline = 0xFF7A5618.toInt(),
            warningText = 0xFFF5D48F.toInt(),
            overlayScrim = 0xCC07090D.toInt(),
            overlayBackground = 0xEE0D0F14.toInt(),
            overlaySurface = 0xFF141820.toInt(),
            overlayPrimaryText = 0xFFF2F4F8.toInt(),
            overlaySecondaryText = 0xFFB9C0CC.toInt(),
            overlayAccent = 0xFF4C8DFF.toInt(),
            overlayAccentTrack = 0x664C8DFF,
            overlayTrackOff = 0xFF39424E.toInt(),
            overlayButton = 0xFF232A36.toInt(),
            overlayButtonText = 0xFFF2F4F8.toInt(),
            overlayDanger = 0xFF8B2635.toInt(),
            overlayOutline = 0xFF2B3342.toInt(),
        )

        val LIGHT = TvColorScheme(
            night = false,
            background = 0xFFF3F5F8.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            card = 0xFFE9EEF5.toInt(),
            subtleSurface = 0xFFEDF1F7.toInt(),
            primaryText = 0xFF121821.toInt(),
            secondaryText = 0xFF566173.toInt(),
            accent = 0xFF1E5EFF.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            accentTrack = 0x551E5EFF,
            trackOff = 0xFFC5CEDB.toInt(),
            button = 0xFFDCE3EE.toInt(),
            buttonText = 0xFF121821.toInt(),
            danger = 0xFFC62828.toInt(),
            onDanger = 0xFFFFFFFF.toInt(),
            tonalDanger = 0xFFFBE9E7.toInt(),
            onTonalDanger = 0xFF8C1D18.toInt(),
            tonalDangerOutline = 0xFFE6B8B5.toInt(),
            outline = 0xFFD0D8E4.toInt(),
            ripple = 0x22121821,
            focusRing = 0xFF1D4ED8.toInt(),
            warningSurface = 0xFFFFF4DC.toInt(),
            warningOutline = 0xFFD8A33A.toInt(),
            warningText = 0xFF5B3F00.toInt(),
            overlayScrim = 0xB3121821.toInt(),
            overlayBackground = 0xF5F6F8FB.toInt(),
            overlaySurface = 0xFFFFFFFF.toInt(),
            overlayPrimaryText = 0xFF121821.toInt(),
            overlaySecondaryText = 0xFF566173.toInt(),
            overlayAccent = 0xFF1E5EFF.toInt(),
            overlayAccentTrack = 0x551E5EFF,
            overlayTrackOff = 0xFFC5CEDB.toInt(),
            overlayButton = 0xFFE2E8F2.toInt(),
            overlayButtonText = 0xFF121821.toInt(),
            overlayDanger = 0xFFC62828.toInt(),
            overlayOutline = 0xFFD0D8E4.toInt(),
        )
    }
}

/** Stable preference values for TVPlay's own UI theme; never persist enum ordinals. */
enum class AppAppearance(val key: String) {
    DARK("dark"),
    LIGHT("light"),
    AUTO("auto");

    companion object {
        val SYSTEM: AppAppearance get() = AUTO

        fun fromKey(key: String?): AppAppearance = entries.firstOrNull { it.key == key } ?: DARK
    }
}

/**
 * Resolves whether TVPlay's own screens should use their dark palette.
 *
 * [AppAppearance.AUTO] follows the live CarPlay host night state when one is running, then the
 * configured CarPlay night mode, with Android's UI mode used when that mode is [CarPlayNightMode.SYSTEM].
 */
fun resolveAppNight(
    appearance: AppAppearance,
    carPlayMode: CarPlayNightMode,
    systemNight: Boolean,
    hostNight: Boolean? = null,
): Boolean = when (appearance) {
    AppAppearance.DARK -> true
    AppAppearance.LIGHT -> false
    AppAppearance.AUTO -> hostNight ?: when (carPlayMode) {
        CarPlayNightMode.NIGHT -> true
        CarPlayNightMode.DAY -> false
        CarPlayNightMode.SYSTEM -> systemNight
    }
}

fun Context.resolveAppNightNow(hostNight: Boolean? = AppAppearanceRuntime.hostNight()): Boolean =
    resolveAppNight(
        appearance = AirPlayPersistence.loadAppAppearance(this),
        carPlayMode = AirPlayPersistence.loadCarPlayNightMode(this),
        systemNight = nightModeOrNull(resources.configuration.uiMode) ?: true,
        hostNight = hostNight,
    )

/**
 * Publishes the CarPlay host activity's resolved night state to other TVPlay windows in the same process.
 *
 * Only the current owner may update or clear the state, so an older host instance being torn down cannot
 * erase a newer host's state.
 */
object AppAppearanceRuntime {
    private var ownerRef: WeakReference<Any>? = null
    @Volatile private var currentHostNight: Boolean? = null
    private val observers = CopyOnWriteArraySet<(Boolean?) -> Unit>()

    @Synchronized
    fun hostNight(): Boolean? {
        if (ownerRef?.get() == null) {
            ownerRef = null
            currentHostNight = null
        }
        return currentHostNight
    }

    fun publishHost(owner: Any, night: Boolean) {
        checkMainThread()
        val changed = synchronized(this) {
            ownerRef = WeakReference(owner)
            val previous = currentHostNight
            currentHostNight = night
            previous != night
        }
        if (changed) observers.forEach { it(night) }
    }

    fun clearHost(owner: Any) {
        checkMainThread()
        val changed = synchronized(this) {
            val currentOwner = ownerRef?.get()
            if (currentOwner != null && currentOwner !== owner) return
            val hadValue = currentHostNight != null
            ownerRef = null
            currentHostNight = null
            hadValue
        }
        if (changed) observers.forEach { it(null) }
    }

    fun observeHost(observer: (Boolean?) -> Unit): AutoCloseable {
        observers += observer
        return AutoCloseable { observers -= observer }
    }

    @Synchronized
    internal fun resetForTest() {
        ownerRef = null
        currentHostNight = null
        observers.clear()
    }

    private fun checkMainThread() {
        val mainLooper = Looper.getMainLooper() ?: return
        check(Looper.myLooper() == mainLooper) { "AppAppearanceRuntime must be mutated on the main thread" }
    }
}
