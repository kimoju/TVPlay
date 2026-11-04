package app.tvplay

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Capability-based detection for devices whose primary interaction model is a TV remote/D-pad,
 * plus explicit TVPlay product mode configuration.
 *
 * No manufacturer/model checks are used. Android TV feature flags and UI mode are preferred,
 * with a no-touchscreen fallback for other non-touch displays.
 */
internal object AndroidTvInputMode {
    @Volatile
    internal var forceTvPlayModeForTest: Boolean? = null

    fun isTvPlayConfigEnabled(context: Context): Boolean {
        forceTvPlayModeForTest?.let { return it }
        return runCatching { context.resources.getBoolean(R.bool.config_tvplay_mode) }.getOrDefault(true)
    }

    fun isTelevision(context: Context): Boolean {
        val packageManager = context.packageManager
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) return true

        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true

        return (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun shouldUseKnobAsPrimaryInput(context: Context): Boolean {
        if (isTvPlayConfigEnabled(context)) return true
        if (isTelevision(context)) return true

        return context.resources.configuration.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH
    }

    fun isTvPlayMode(context: Context): Boolean = shouldUseKnobAsPrimaryInput(context)
}
