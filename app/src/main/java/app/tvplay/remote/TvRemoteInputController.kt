package app.tvplay.remote

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import app.tvplay.config.AndroidTvInputMode
import app.tvplay.config.TvPreferencesStore
import app.tvplay.engine.protocol.rtsp.AirPlayKnobState
import app.tvplay.engine.protocol.rtsp.CarPlayMediaButton
import app.tvplay.engine.protocol.rtsp.RtspControlSession
import kotlin.math.abs

typealias RemoteInputDispatcher = TvRemoteInputController

/** Stable preference values; never persist enum ordinals. */
enum class CarPlayNightMode(val key: String) {
    SYSTEM("system"),
    DAY("day"),
    NIGHT("night");

    companion object {
        fun fromKey(key: String?): CarPlayNightMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** Main-thread controller. Only its output is sent to CarPlay; Android theme is never changed. */
class CarPlayNightModeController(
    initialNight: Boolean,
    private val onNightChanged: (Boolean) -> Unit,
) {
    var night: Boolean = initialNight
        private set
    private var mode = CarPlayNightMode.SYSTEM
    private var systemNight = initialNight

    fun configure(
        mode: CarPlayNightMode,
        systemNight: Boolean,
    ) {
        this.mode = mode
        this.systemNight = systemNight
        applyMode()
    }

    fun resume(systemNight: Boolean) {
        this.systemNight = systemNight
        applyMode()
    }

    fun pause() = Unit

    fun systemChanged(night: Boolean) {
        systemNight = night
        if (mode == CarPlayNightMode.SYSTEM) applyNight(night)
    }

    private fun applyMode() {
        when (mode) {
            CarPlayNightMode.SYSTEM -> applyNight(systemNight)
            CarPlayNightMode.DAY -> applyNight(false)
            CarPlayNightMode.NIGHT -> applyNight(true)
        }
    }

    private fun applyNight(value: Boolean) {
        if (value == night) return
        night = value
        onNightChanged(value)
    }
}

/**
 * Handles TV remote D-pad -> CarPlay knob translation, media keys, Siri voice key,
 * and multi-finger swipe gesture detection for [app.tvplay.TvPlayProjectionActivity].
 */
object TvRemoteInputController {
    fun dispatchKeyEvent(
        context: Context,
        event: KeyEvent,
        legacySiriPresses: MutableSet<Triple<Int, Int, Int>>,
        menuOpen: Boolean,
        suppressBackReleaseAfterLongPress: Boolean,
        setSuppressBackRelease: (Boolean) -> Unit,
        hasActiveScreenStream: Boolean,
        sessionActive: Boolean,
        sendKnob: (AirPlayKnobState, Boolean) -> Boolean,
        sendMediaButton: (Int) -> Boolean,
        onToggleQuickMenu: () -> Unit,
        onOpenQuickMenu: () -> Unit,
        onRequestSiri: (Int) -> Unit,
        onLog: (String) -> Unit = {},
    ): Boolean? {
        val physicalKey = Triple(event.deviceId, event.keyCode, event.scanCode)
        val downOrUp = event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
        if (downOrUp && physicalKey in legacySiriPresses) {
            if (event.action == KeyEvent.ACTION_UP) {
                legacySiriPresses.remove(physicalKey)
                onRequestSiri(event.keyCode)
            }
            return true
        }

        val knobActive = AndroidTvInputMode.shouldUseKnobAsPrimaryInput(context) ||
            TvRemoteKeyMap.fromExternalController(event, TvPreferencesStore.loadExternalController(context))
        if (knobActive) {
            if (TvRemoteKeyMap.isHostMenuKey(event.keyCode)) {
                if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                    onToggleQuickMenu()
                }
                return downOrUp
            }
            if (suppressBackReleaseAfterLongPress &&
                (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_BUTTON_B)
            ) {
                if (event.action == KeyEvent.ACTION_UP) {
                    setSuppressBackRelease(false)
                }
                return downOrUp
            }
            if (!menuOpen && TvRemoteKeyMap.isLongPressBack(event)) {
                setSuppressBackRelease(true)
                sendKnob(AirPlayKnobState(), false)
                onOpenQuickMenu()
                return true
            }
        }
        if (!menuOpen &&
            knobActive &&
            hasActiveScreenStream &&
            TvRemoteKeyMap.dispatchToKnob(event, sendKnob)
        ) {
            return true
        }

        val mediaPress = CarPlayMediaButton.forCarPlayWindow(
            event.keyCode,
            sessionActive = sessionActive,
            inCall = false,
        )
        if (downOrUp && mediaPress != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val sent = sendMediaButton(mediaPress)
                onLog("Media: key ${event.keyCode} -> CarPlay $mediaPress sent=$sent")
            }
            return true
        }

        if (!CarPlayMediaButton.opensSiri(event.keyCode)) return null
        if (event.action == KeyEvent.ACTION_DOWN) legacySiriPresses.add(physicalKey)
        if (event.action == KeyEvent.ACTION_UP) {
            onRequestSiri(event.keyCode)
        }
        return true
    }

    fun dispatchKeyEvent(
        context: Context,
        event: KeyEvent,
        legacySiriPresses: MutableSet<Triple<Int, Int, Int>>,
        menuOpen: Boolean,
        suppressBackReleaseAfterLongPress: Boolean,
        setSuppressBackRelease: (Boolean) -> Unit,
        hasActiveScreenStream: Boolean,
        sessionActive: Boolean,
        session: RtspControlSession?,
        onToggleQuickMenu: () -> Unit,
        onOpenQuickMenu: () -> Unit,
        onRequestSiri: (Int) -> Unit,
        onLog: (String) -> Unit = {},
    ): Boolean? = dispatchKeyEvent(
        context = context,
        event = event,
        legacySiriPresses = legacySiriPresses,
        menuOpen = menuOpen,
        suppressBackReleaseAfterLongPress = suppressBackReleaseAfterLongPress,
        setSuppressBackRelease = setSuppressBackRelease,
        hasActiveScreenStream = hasActiveScreenStream,
        sessionActive = sessionActive,
        sendKnob = { state, momentary ->
            if (session == null) {
                false
            } else {
                session.sendKnob(state, momentary)
                true
            }
        },
        sendMediaButton = { mediaPress ->
            if (session == null) {
                false
            } else {
                session.sendMedia(mediaPress)
                true
            }
        },
        onToggleQuickMenu = onToggleQuickMenu,
        onOpenQuickMenu = onOpenQuickMenu,
        onRequestSiri = onRequestSiri,
        onLog = onLog,
    )

    fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    fun isDownwardMenuSwipe(
        event: MotionEvent,
        startX: Float,
        startY: Float,
        minDistancePx: Int,
        directionRatio: Float,
    ): Pair<Boolean, Boolean> {
        val deltaX = abs(pointerCentroid(event, horizontal = true) - startX)
        val deltaY = pointerCentroid(event, horizontal = false) - startY
        val thresholdReached = abs(deltaY) >= minDistancePx && abs(deltaY) >= deltaX * directionRatio
        return thresholdReached to (deltaY > 0)
    }
}
