package app.tvplay.remote

import android.view.KeyCharacterMap
import android.view.KeyEvent
import app.tvplay.engine.protocol.rtsp.AirPlayKnobState
import app.tvplay.engine.protocol.rtsp.CarPlayMediaButton
import app.tvplay.engine.protocol.rtsp.RtspControlSession

typealias CarPlayRemoteKeys = TvRemoteKeyMap

/**
 * Resolved CarPlay HID or host action mapped from an Android TV remote / controller [KeyEvent].
 */
sealed interface CarPlayRemoteAction {
    /** Sends a rotary wheel step or Select/Back button state to CarPlay's knob HID device. */
    data class Knob(val state: AirPlayKnobState, val momentary: Boolean) : CarPlayRemoteAction

    /** Event was consumed by knob repeat/release handling without emitting an extra HID report. */
    data object ConsumedNoOp : CarPlayRemoteAction

    /** Toggles the TVPlay quick control overlay menu (`KEYCODE_MENU`, `KEYCODE_SETTINGS`, etc.). */
    data object ToggleHostMenu : CarPlayRemoteAction

    /** Opens the TVPlay quick control overlay menu on a long-press of `KEYCODE_BACK`. */
    data object OpenHostMenuLongPressBack : CarPlayRemoteAction

    /** Sends a CarPlay media HID report (`PLAY_PAUSE`, `NEXT`, `PREVIOUS`, etc.). */
    data class MediaButton(val buttonIndex: Int) : CarPlayRemoteAction

    /** Invokes Siri voice assistant for a voice/search button press. */
    data class VoiceAssist(val keyCode: Int) : CarPlayRemoteAction
}

/**
 * Android TV / hardware navigation keys -> CarPlay's native knob, media, and voice HID actions.
 *
 * CarPlay's non-touch focus model is rotary-first. A D-pad therefore maps to rotary wheel deltas
 * for normal focus traversal. Select and Back preserve real down/up transitions so long-press
 * semantics remain available to CarPlay. Touchscreen input is completely separate and unchanged.
 */
object TvRemoteKeyMap {
    private const val WHEEL_STEP = 1
    private const val HELD_REPEAT_INTERVAL = 3
    const val LONG_PRESS_BACK_REPEAT_COUNT = 5

    /**
     * Remote / controller keys that open or close the host CarPlay settings overlay on Android TV
     * and non-touch displays where a three-finger swipe is unavailable.
     */
    fun isHostMenuKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_TV_CONTENTS_MENU,
        KeyEvent.KEYCODE_BUTTON_START -> true
        else -> false
    }

    /**
     * Holding Back on a TV remote or controller opens the host CarPlay settings overlay, while a
     * short press continues sending [Button.BACK] to CarPlay's knob.
     */
    fun isLongPressBack(event: KeyEvent): Boolean =
        (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_BUTTON_B) &&
            event.action == KeyEvent.ACTION_DOWN &&
            (event.isLongPress || event.repeatCount >= LONG_PRESS_BACK_REPEAT_COUNT)

    /**
     * Standard TV/controller navigation, system and transport keys are never available for the
     * custom play/pause assignment. This preserves the existing knob, Android MediaSession
     * and OEM/system handlers for Fire TV, Google TV and other HID remotes.
     */
    fun isProtectedNavigationKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_HEADSETHOOK,
        CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE,
        CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE_DILINK3,
        CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE,
        CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG,
        CarPlayMediaButton.KEYCODE_BYD_CARPLAY_VOICE,
        CarPlayMediaButton.KEYCODE_BYD_CARPLAY_VOICE_LONG,
        KeyEvent.KEYCODE_TV, KeyEvent.KEYCODE_TV_INPUT,
        KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,
        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT -> true
        else -> false
    }

    /**
     * With the external-controller setting on a touchscreen display, only keys from a real input
     * device drive the knob. The system navigation bar's Back is a virtual key and keeps leaving
     * CarPlay as before.
     */
    fun fromExternalController(event: KeyEvent, enabled: Boolean): Boolean =
        enabled && event.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD && event.device?.isVirtual != true

    /**
     * Resolves an Android TV remote [event] into a [CarPlayRemoteAction], or `null` when unhandled.
     */
    fun resolveAction(
        event: KeyEvent,
        knobActive: Boolean = true,
        menuOpen: Boolean = false,
        hasActiveScreenStream: Boolean = true,
        sessionActive: Boolean = true,
        inCall: Boolean = false,
    ): CarPlayRemoteAction? {
        if (knobActive) {
            if (isHostMenuKey(event.keyCode)) {
                return if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                    CarPlayRemoteAction.ToggleHostMenu
                } else if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                    CarPlayRemoteAction.ConsumedNoOp
                } else {
                    null
                }
            }
            if (!menuOpen && isLongPressBack(event)) {
                return CarPlayRemoteAction.OpenHostMenuLongPressBack
            }
            if (!menuOpen && hasActiveScreenStream) {
                var knobAction: CarPlayRemoteAction? = null
                val handled = dispatchToKnob(event) { state, momentary ->
                    knobAction = CarPlayRemoteAction.Knob(state, momentary)
                    true
                }
                if (handled) {
                    return knobAction ?: CarPlayRemoteAction.ConsumedNoOp
                }
            }
        }

        val mediaPress = CarPlayMediaButton.forCarPlayWindow(
            event.keyCode,
            sessionActive = sessionActive,
            inCall = inCall,
        )
        if ((event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) && mediaPress != null) {
            return if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                CarPlayRemoteAction.MediaButton(mediaPress)
            } else {
                CarPlayRemoteAction.ConsumedNoOp
            }
        }

        if (CarPlayMediaButton.opensSiri(event.keyCode)) {
            return if (event.action == KeyEvent.ACTION_UP) {
                CarPlayRemoteAction.VoiceAssist(event.keyCode)
            } else if (event.action == KeyEvent.ACTION_DOWN) {
                CarPlayRemoteAction.ConsumedNoOp
            } else {
                null
            }
        }

        return null
    }

    fun dispatch(
        event: KeyEvent,
        sendKnob: ((AirPlayKnobState, Boolean) -> Boolean)?,
    ): Boolean =
        dispatchToKnob(event) { state, momentary -> sendKnob?.invoke(state, momentary) == true }

    fun dispatchToSession(event: KeyEvent, session: RtspControlSession?): Boolean =
        dispatchToKnob(event) { state, momentary ->
            if (session == null) {
                false
            } else {
                session.sendKnob(state, momentary)
                true
            }
        }

    fun dispatchToKnob(
        event: KeyEvent,
        sendKnob: (AirPlayKnobState, Boolean) -> Boolean,
    ): Boolean {
        val wheelDelta = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT -> -WHEEL_STEP

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT -> WHEEL_STEP

            else -> null
        }

        if (wheelDelta != null) {
            return dispatchWheel(event, sendKnob, wheelDelta)
        }

        val button = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_A -> Button.SELECT

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_B -> Button.BACK

            else -> return false
        }

        return dispatchButton(event, sendKnob, button)
    }

    private fun dispatchWheel(
        event: KeyEvent,
        sendKnob: (AirPlayKnobState, Boolean) -> Boolean,
        delta: Int,
    ): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val shouldSend =
                    event.repeatCount == 0 || event.repeatCount % HELD_REPEAT_INTERVAL == 0

                if (!shouldSend) {
                    true
                } else {
                    sendKnob(AirPlayKnobState(wheel = delta), true)
                }
            }

            // Relative wheel movement has no release state.
            KeyEvent.ACTION_UP -> true
            else -> false
        }
    }

    private fun dispatchButton(
        event: KeyEvent,
        sendKnob: (AirPlayKnobState, Boolean) -> Boolean,
        button: Button,
    ): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    sendKnob(button.state(down = true), false)
                } else {
                    true
                }
            }

            KeyEvent.ACTION_UP ->
                sendKnob(AirPlayKnobState(), false)

            else -> false
        }
    }

    private enum class Button {
        SELECT,
        BACK;

        fun state(down: Boolean): AirPlayKnobState = when (this) {
            SELECT -> AirPlayKnobState(select = down)
            BACK -> AirPlayKnobState(back = down)
        }
    }
}
