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
 * Semantic design tokens for TVPlay's 10-foot cinema screens, Bento cards, and CarPlay overlays.
 *
 * Engineered with `ui-ux-pro-max` Dark Mode (OLED) + Glassmorphism guidelines:
 * - >= 7:1 primary text contrast and >= 4.5:1 secondary text contrast in both Dark and Light modes.
 * - Distinct layered Z-depth tokens (background -> surface -> card -> elevated button -> focus halo).
 * - Specular 1dp glass borders and ambient radial glow tokens.
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

    val backgroundGradientTop: Int
        get() = if (night) 0xFF0C1426.toInt() else 0xFFF8FAFC.toInt()
    val backgroundGradientBottom: Int
        get() = if (night) 0xFF04070D.toInt() else 0xFFE8EFF8.toInt()
    val ambientGlowPrimary: Int
        get() = if (night) 0x382563EB else 0x182563EB
    val ambientGlowSecondary: Int
        get() = if (night) 0x2406B6D4 else 0x1206B6D4
    val heroGradientStart: Int
        get() = if (night) 0xFF162647.toInt() else 0xFFEBF3FF.toInt()
    val heroGradientEnd: Int
        get() = if (night) 0xFF0D1424.toInt() else 0xFFFFFFFF.toInt()
    val heroBorder: Int
        get() = if (night) 0xFF2E4775.toInt() else 0xFFBFDBFE.toInt()
    val glassHighlightBorder: Int
        get() = if (night) 0x26FFFFFF else 0x80FFFFFF.toInt()
    val badgeSurface: Int
        get() = if (night) 0xFF172236.toInt() else 0xFFE2E8F0.toInt()
    val badgeOutline: Int
        get() = if (night) 0xFF293852.toInt() else 0xFFCBD5E1.toInt()
    val iconWellSurface: Int
        get() = if (night) 0xFF1A2740.toInt() else 0xFFDBEAFE.toInt()
    val iconWellOutline: Int
        get() = if (night) 0xFF2D426B.toInt() else 0xFF93C5FD.toInt()
    val success: Int
        get() = if (night) 0xFF10B981.toInt() else 0xFF059669.toInt()
    val successSurface: Int
        get() = if (night) 0x2910B981 else 0x1F059669
    val cyanAccent: Int
        get() = if (night) 0xFF38BDF8.toInt() else 0xFF0284C7.toInt()

    companion object {
        fun of(night: Boolean): TvColorScheme = if (night) DARK else LIGHT

        fun resolve(context: Context): TvColorScheme =
            of((context as? AppAppearanceOwner)?.currentAppNight ?: context.resolveAppNightNow())

        val DARK = TvColorScheme(
            night = true,
            background = 0xFF060911.toInt(),
            surface = 0xFF0E1523.toInt(),
            card = 0xFF141E30.toInt(),
            subtleSurface = 0xFF090E18.toInt(),
            primaryText = 0xFFF8FAFC.toInt(),
            secondaryText = 0xFF94A3B8.toInt(),
            accent = 0xFF3B82F6.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            accentTrack = 0x773B82F6,
            trackOff = 0xFF2A364B.toInt(),
            button = 0xFF1D2940.toInt(),
            buttonText = 0xFFF8FAFC.toInt(),
            danger = 0xFFDC2626.toInt(),
            onDanger = 0xFFFFFFFF.toInt(),
            tonalDanger = 0xFF2A1419.toInt(),
            onTonalDanger = 0xFFFCA5A5.toInt(),
            tonalDangerOutline = 0xFF6B212A.toInt(),
            outline = 0xFF212E45.toInt(),
            ripple = 0x33FFFFFF,
            focusRing = 0xFF38BDF8.toInt(),
            warningSurface = 0xFF291F11.toInt(),
            warningOutline = 0xFF855B1A.toInt(),
            warningText = 0xFFFDE68A.toInt(),
            overlayScrim = 0xD904070D.toInt(),
            overlayBackground = 0xF20C121E.toInt(),
            overlaySurface = 0xFF141E30.toInt(),
            overlayPrimaryText = 0xFFF8FAFC.toInt(),
            overlaySecondaryText = 0xFF94A3B8.toInt(),
            overlayAccent = 0xFF3B82F6.toInt(),
            overlayAccentTrack = 0x773B82F6,
            overlayTrackOff = 0xFF2A364B.toInt(),
            overlayButton = 0xFF1E2B42.toInt(),
            overlayButtonText = 0xFFF8FAFC.toInt(),
            overlayDanger = 0xFFB91C1C.toInt(),
            overlayOutline = 0xFF293852.toInt(),
        )

        val LIGHT = TvColorScheme(
            night = false,
            background = 0xFFF1F5F9.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            card = 0xFFF8FAFC.toInt(),
            subtleSurface = 0xFFE2E8F0.toInt(),
            primaryText = 0xFF0F172A.toInt(),
            secondaryText = 0xFF475569.toInt(),
            accent = 0xFF2563EB.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            accentTrack = 0x662563EB,
            trackOff = 0xFFCBD5E1.toInt(),
            button = 0xFFE2E8F0.toInt(),
            buttonText = 0xFF0F172A.toInt(),
            danger = 0xFFDC2626.toInt(),
            onDanger = 0xFFFFFFFF.toInt(),
            tonalDanger = 0xFFFEF2F2.toInt(),
            onTonalDanger = 0xFF991B1B.toInt(),
            tonalDangerOutline = 0xFFFECACA.toInt(),
            outline = 0xFFCBD5E1.toInt(),
            ripple = 0x220F172A,
            focusRing = 0xFF2563EB.toInt(),
            warningSurface = 0xFFFFFBEB.toInt(),
            warningOutline = 0xFFF59E0B.toInt(),
            warningText = 0xFF92400E.toInt(),
            overlayScrim = 0xB30F172A.toInt(),
            overlayBackground = 0xF7FFFFFF.toInt(),
            overlaySurface = 0xFFF1F5F9.toInt(),
            overlayPrimaryText = 0xFF0F172A.toInt(),
            overlaySecondaryText = 0xFF475569.toInt(),
            overlayAccent = 0xFF2563EB.toInt(),
            overlayAccentTrack = 0x662563EB,
            overlayTrackOff = 0xFFCBD5E1.toInt(),
            overlayButton = 0xFFE2E8F0.toInt(),
            overlayButtonText = 0xFF0F172A.toInt(),
            overlayDanger = 0xFFDC2626.toInt(),
            overlayOutline = 0xFFCBD5E1.toInt(),
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
