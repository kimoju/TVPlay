package app.tvplay.projection

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import app.tvplay.AirPlayPersistence
import app.tvplay.AndroidTvInputMode
import app.tvplay.CarPlayRemoteKeys
import app.tvplay.airplay.AirPlayKnobState
import app.tvplay.airplay.CarPlayMediaButton
import app.tvplay.orchestration.CarPlayController
import kotlin.math.abs

/**
 * Handles TV remote D-pad -> CarPlay knob translation, media keys, Siri voice key,
 * and multi-finger swipe gesture detection for [app.tvplay.TvPlayProjectionActivity].
 */
internal object RemoteInputDispatcher {
    fun dispatchKeyEvent(
        context: Context,
        event: KeyEvent,
        legacySiriPresses: MutableSet<Triple<Int, Int, Int>>,
        menuOpen: Boolean,
        suppressBackReleaseAfterLongPress: Boolean,
        setSuppressBackRelease: (Boolean) -> Unit,
        hasActiveScreenStream: Boolean,
        sessionActive: Boolean,
        controller: CarPlayController?,
        onToggleQuickMenu: () -> Unit,
        onOpenQuickMenu: () -> Unit,
        onRequestSiri: (Int) -> Unit,
        onLog: (String) -> Unit,
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
            CarPlayRemoteKeys.fromExternalController(event, AirPlayPersistence.loadExternalController(context))
        if (knobActive) {
            if (CarPlayRemoteKeys.isHostMenuKey(event.keyCode)) {
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
            if (!menuOpen && CarPlayRemoteKeys.isLongPressBack(event)) {
                setSuppressBackRelease(true)
                controller?.sendKnob(AirPlayKnobState(), false)
                onOpenQuickMenu()
                return true
            }
        }
        if (!menuOpen &&
            knobActive &&
            hasActiveScreenStream &&
            CarPlayRemoteKeys.dispatch(event, controller)
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
                val sent = controller?.sendMediaButton(mediaPress) == true
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
