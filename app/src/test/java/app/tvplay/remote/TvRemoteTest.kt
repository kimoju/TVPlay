package app.tvplay.remote

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvRemoteTest {

    @Test
    fun remoteKeyMapMapsDpadToRotaryWheelAndLongPressBackToHostMenu() {
        assertTrue(TvRemoteKeyMap.isHostMenuKey(KeyEvent.KEYCODE_MENU))
        assertTrue(TvRemoteKeyMap.isHostMenuKey(KeyEvent.KEYCODE_SETTINGS))

        val downRight = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        val knobAction = TvRemoteKeyMap.resolveAction(downRight)
        assertTrue(knobAction is CarPlayRemoteAction.Knob)
        assertEquals(1, (knobAction as CarPlayRemoteAction.Knob).state.wheel)

        val longBack = KeyEvent(
            0L,
            0L,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_BACK,
            TvRemoteKeyMap.LONG_PRESS_BACK_REPEAT_COUNT,
        )
        assertTrue(TvRemoteKeyMap.isLongPressBack(longBack))
        assertEquals(
            CarPlayRemoteAction.OpenHostMenuLongPressBack,
            TvRemoteKeyMap.resolveAction(longBack),
        )

        val playPauseAction = TvRemoteKeyMap.resolveAction(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        )
        assertEquals(
            CarPlayRemoteAction.MediaButton(app.tvplay.engine.protocol.rtsp.CarPlayMediaButton.PLAY_PAUSE),
            playPauseAction,
        )

        val voiceUpAction = TvRemoteKeyMap.resolveAction(
            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SEARCH),
        )
        assertEquals(
            CarPlayRemoteAction.VoiceAssist(KeyEvent.KEYCODE_SEARCH),
            voiceUpAction,
        )
    }

    @Test
    fun videoColorFilterClampsPictureAdjustmentsAndResetsToIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = TvVideoColorFilter.preferences(context)
        TvVideoColorFilter.reset(prefs)
        assertTrue(TvVideoColorFilter.keys.all { TvVideoColorFilter.value(prefs, it) == TvVideoColorFilter.defaultValue(it) })

        prefs.edit().putInt(TvVideoColorFilter.BRIGHTNESS, 120).commit()
        assertEquals(50, TvVideoColorFilter.value(prefs, TvVideoColorFilter.BRIGHTNESS))
        assertFalse(TvVideoColorFilter.keys.all { TvVideoColorFilter.value(prefs, it) == TvVideoColorFilter.defaultValue(it) })

        TvVideoColorFilter.reset(prefs)
        assertEquals(0, TvVideoColorFilter.value(prefs, TvVideoColorFilter.BRIGHTNESS))
        assertEquals(100, TvVideoColorFilter.value(prefs, TvVideoColorFilter.CONTRAST))
    }
}
