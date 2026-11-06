package app.tvplay.engine.protocol.carlink

import android.view.KeyEvent

/**
 * ICCOA CarLink UIBC (User Input Back Channel, TCP port 4321) touch & key JSON encoder
 * (`com.ovm.wfd.uibc.UibcManager`).
 *
 * Also provides a TV remote D-pad virtual cursor & Android keycode translator so Android TV
 * users can navigate Xiaomi CarWith, vivo Jovi InCar, and OPPO Car+ effortlessly with a TV remote.
 */
object CarLinkUibcDispatcher {
    const val TYPE_TOUCH = 1
    const val TYPE_KEY = 2

    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    data class TouchPointer(
        val id: Int = 0,
        val x: Int,
        val y: Int,
    )

    /**
     * Builds the UTF-8 JSON payload for an ICCOA UIBC touch event (`type = 1`).
     */
    fun buildTouchJson(
        action: Int,
        displayWidth: Int,
        displayHeight: Int,
        pointers: List<TouchPointer>,
    ): String {
        require(pointers.isNotEmpty()) { "At least one touch pointer is required" }
        val w = displayWidth.coerceAtLeast(1)
        val h = displayHeight.coerceAtLeast(1)
        return buildString {
            append('{')
            append("\"type\":").append(TYPE_TOUCH).append(',')
            append("\"action\":").append(action).append(',')
            append("\"width\":").append(w).append(',')
            append("\"height\":").append(h).append(',')
            append("\"count\":").append(pointers.size)
            pointers.forEachIndexed { index, pointer ->
                append(",\"trackID").append(index).append("\":").append(pointer.id)
                append(",\"x").append(index).append("\":").append(pointer.x.coerceIn(0, w))
                append(",\"y").append(index).append("\":").append(pointer.y.coerceIn(0, h))
            }
            append('}')
        }
    }

    /**
     * Builds the UTF-8 JSON payload for an ICCOA UIBC key event (`type = 2`).
     */
    fun buildKeyJson(
        action: Int,
        keyCode: Int,
        metaState: Int = 0,
    ): String = buildString {
        append('{')
        append("\"type\":").append(TYPE_KEY).append(',')
        append("\"action\":").append(action).append(',')
        append("\"keycode\":").append(keyCode).append(',')
        append("\"metaState\":").append(metaState)
        append('}')
    }

    /**
     * Encodes a UIBC JSON payload into wire bytes, encrypting with AES/GCM (`SecurityManager`)
     * when [sessionKey] is negotiated.
     */
    fun encodeWirePayload(
        json: String,
        sessionKey: ByteArray? = null,
    ): ByteArray {
        val raw = json.toByteArray(Charsets.UTF_8)
        return if (sessionKey != null && (sessionKey.size == 16 || sessionKey.size == 32)) {
            CarLinkAuthCipher.encryptGcmWire(sessionKey, raw)
        } else {
            raw
        }
    }

    /**
     * Translates an Android TV remote / media button code into ICCOA `UCarProto.CustomKeyEvent.KeyCode`.
     */
    fun mapAndroidKeyToCarLinkKeyCode(androidKeyCode: Int): Int = when (androidKeyCode) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> 4 // KEY_CODE_BACK
        KeyEvent.KEYCODE_HOME -> 3 // KEY_CODE_HOME
        KeyEvent.KEYCODE_MEDIA_NEXT -> 87 // KEY_CODE_MEDIA_NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> 88 // KEY_CODE_MEDIA_PREVIOUS
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> 85 // KEY_CODE_MEDIA_PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY -> 126 // KEY_CODE_MEDIA_PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> 127 // KEY_CODE_MEDIA_PAUSE
        KeyEvent.KEYCODE_CALL -> 5 // KEY_CODE_CALL
        KeyEvent.KEYCODE_ENDCALL -> 6 // KEY_CODE_ENDCALL
        KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH -> 231 // KEY_CODE_VOICE_ASSIST
        KeyEvent.KEYCODE_DPAD_UP -> 19
        KeyEvent.KEYCODE_DPAD_DOWN -> 20
        KeyEvent.KEYCODE_DPAD_LEFT -> 21
        KeyEvent.KEYCODE_DPAD_RIGHT -> 22
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 23
        else -> androidKeyCode
    }
}
