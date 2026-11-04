package app.tvplay.engine.protocol.rtsp

import android.view.KeyEvent
import app.tvplay.engine.av.ViewportTouchContact

typealias AirPlayContact = ViewportTouchContact
typealias RtspHidDispatcher = AirPlayHid

/** Momentary knob/button state for the rotary/remote controller. */
data class AirPlayKnobState(
    val select: Boolean = false,
    val home: Boolean = false,
    val back: Boolean = false,
    val x: Int = 0,
    val y: Int = 0,
    val wheel: Int = 0,
)

/** CarPlay HID device descriptors and report encoders. */
object AirPlayHid {
    const val TOUCH_HID_UID = 0x2a2a2a2a
    const val KNOB_HID_UID = 0x2a2a2a2b
    const val MEDIA_HID_UID = 0x2a2a2a2c
    const val TELEPHONY_HID_UID = 0x2a2a2a2d

    internal const val TOUCH_CONTACTS = 2
    private const val BYTES_PER_FINGER = 6

    fun touchHidDevice(xMax: Int, yMax: Int, displayUuid: String): Map<String, Any?> =
        hidDeviceEntry(TOUCH_HID_UID, "TVPlay Touchscreen", multitouchDescriptor(xMax, yMax), displayUuid)

    fun knobHidDevice(displayUuid: String): Map<String, Any?> =
        hidDeviceEntry(KNOB_HID_UID, "TVPlay Knob", knobDescriptor, displayUuid)

    fun mediaHidDevice(displayUuid: String): Map<String, Any?> =
        hidDeviceEntry(MEDIA_HID_UID, "TVPlay Media", mediaDescriptor, displayUuid)

    fun telephonyHidDevice(displayUuid: String): Map<String, Any?> =
        hidDeviceEntry(TELEPHONY_HID_UID, "TVPlay Telephony", telephonyDescriptor, displayUuid)

    /** Each contact goes in the slot named by its id; a slot with none is reported up. */
    fun touchReport(contacts: List<ViewportTouchContact>): ByteArray {
        val report = ByteArray(BYTES_PER_FINGER * TOUCH_CONTACTS)
        for (slot in 0 until TOUCH_CONTACTS) {
            val offset = slot * BYTES_PER_FINGER
            report[offset] = slot.toByte()
            val contact = contacts.firstOrNull { it.id == slot } ?: continue
            report[offset + 1] = if (contact.down) 0x01 else 0x00
            writeU16Le(report, offset + 2, Math.round(contact.x.coerceAtLeast(0.0)).toInt())
            writeU16Le(report, offset + 4, Math.round(contact.y.coerceAtLeast(0.0)).toInt())
        }
        return report
    }

    fun knobReport(state: AirPlayKnobState): ByteArray {
        val report = ByteArray(4)
        report[0] = (
            (if (state.select) 0x01 else 0) or
                (if (state.home) 0x02 else 0) or
                (if (state.back) 0x04 else 0)
            ).toByte()
        report[1] = clampAxis(state.x)
        report[2] = clampAxis(state.y)
        report[3] = clampAxis(state.wheel)
        return report
    }

    fun mediaReport(index: Int): ByteArray = byteArrayOf(index.toByte())

    fun telephonyReport(index: Int): ByteArray = byteArrayOf(index.toByte())

    private fun hidDeviceEntry(
        uid: Int,
        name: String,
        descriptor: ByteArray,
        displayUuid: String,
    ): Map<String, Any?> = linkedMapOf(
        "hidProductID" to 1L,
        "hidVendorID" to 2L,
        "hidCountryCode" to 0L,
        "uuid" to uid.toString(16),
        "name" to name,
        "displayUUID" to displayUuid,
        "hidDescriptor" to descriptor,
    )

    private fun multitouchDescriptor(xMax: Int, yMax: Int): ByteArray {
        val bytes = ArrayList<Int>()
        bytes.addAll(intArrayOf(0x05, 0x0d, 0x09, 0x04, 0xa1, 0x01).toList())
        repeat(TOUCH_CONTACTS) { bytes.addAll(fingerCollection(xMax, yMax).toList()) }
        bytes.add(0xc0)
        return bytes.map { it.toByte() }.toByteArray()
    }

    private fun fingerCollection(xMax: Int, yMax: Int): IntArray = intArrayOf(
        0x05, 0x0d, 0x09, 0x22, 0xa1, 0x02,
        0x09, 0x38, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02,
        0x15, 0x00, 0x25, 0x01, 0x09, 0x33, 0x75, 0x01, 0x95, 0x01, 0x81, 0x02,
        0x95, 0x07, 0x81, 0x03,
        0x05, 0x01, 0x26, xMax and 0xff, (xMax shr 8) and 0xff, 0x09, 0x30, 0x75, 0x10, 0x95, 0x01, 0x81, 0x02,
        0x26, yMax and 0xff, (yMax shr 8) and 0xff, 0x09, 0x31, 0x81, 0x02, 0xc0,
    )

    private val knobDescriptor = descriptor(
        0x05, 0x01, 0x09, 0x08, 0xa1, 0x01,
        0x05, 0x09, 0x09, 0x01, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x01, 0x81, 0x02,
        0x05, 0x0c, 0x0a, 0x23, 0x02, 0x0a, 0x24, 0x02, 0x95, 0x02, 0x81, 0x02,
        0x95, 0x05, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x01, 0xa1, 0x00,
        0x09, 0x30, 0x09, 0x31, 0x15, 0x81, 0x25, 0x7f, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02, 0xc0,
        0x09, 0x38, 0x15, 0x81, 0x25, 0x7f, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06, 0xc0,
    )

    private val mediaDescriptor = descriptor(
        0x05, 0x0c, 0x09, 0x01, 0xa1, 0x01,
        0x15, 0x00, 0x25, 0x06, 0x05, 0x0c,
        0x0a, 0x00, 0x00, 0x0a, 0xb0, 0x00, 0x0a, 0xb1, 0x00, 0x0a, 0xcd, 0x00,
        0x0a, 0xb5, 0x00, 0x0a, 0xb6, 0x00, 0x0a, 0x9e, 0x02,
        0x75, 0x08, 0x95, 0x01, 0x81, 0x00, 0xc0,
    )

    private val telephonyDescriptor = descriptor(
        0x05, 0x0b, 0x09, 0x07, 0xa1, 0x01,
        0x15, 0x00, 0x25, 0x11, 0x05, 0x0b,
        0x09, 0x00, 0x09, 0x20, 0x09, 0x21, 0x09, 0x26, 0x09, 0x2f,
        0x09, 0xb0, 0x09, 0xb1, 0x09, 0xb2, 0x09, 0xb3, 0x09, 0xb4, 0x09, 0xb5,
        0x09, 0xb6, 0x09, 0xb7, 0x09, 0xb8, 0x09, 0xb9, 0x09, 0xba, 0x09, 0xbb,
        0x05, 0x07, 0x09, 0x2a,
        0x75, 0x08, 0x95, 0x01, 0x81, 0x00, 0xc0,
    )

    private fun descriptor(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

    private fun clampAxis(value: Int): Byte = value.coerceIn(-127, 127).toByte()

    private fun writeU16Le(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }
}

/**
 * Keeps each finger in one touch report slot from press to release. Contacts arrive with
 * Android pointer ids, which stay with a finger while the pointer index of the others changes. A
 * finger that is gone, and every finger when the list is empty, is released where it last was: a
 * release at (0,0) drags it to the corner first. A finger beyond the free slots is left out.
 */
class AirPlayTouchSlots(private val slots: Int = AirPlayHid.TOUCH_CONTACTS) {
    private val pointers = arrayOfNulls<Int>(slots)
    private val held = arrayOfNulls<ViewportTouchContact>(slots)

    /** The contacts to report, each with its slot as id; empty slots are left out. */
    fun assign(contacts: List<ViewportTouchContact>): List<ViewportTouchContact> {
        val report = arrayOfNulls<ViewportTouchContact>(slots)
        for (contact in contacts) {
            val slot = pointers.indexOf(contact.id).takeIf { it >= 0 }
                ?: pointers.indexOf(null).takeIf { it >= 0 && contact.down }
                ?: continue
            pointers[slot] = contact.id
            report[slot] = contact.copy(id = slot)
        }
        for (slot in 0 until slots) {
            if (report[slot] == null) report[slot] = held[slot]?.copy(down = false)
            val sent = report[slot]
            if (sent?.down == true) {
                held[slot] = sent
            } else {
                held[slot] = null
                pointers[slot] = null
            }
        }
        return report.filterNotNull()
    }
}

/**
 * Hardware media keys → CarPlay media HID presses (indices into [AirPlayHid]'s media report).
 */
object CarPlayMediaButton {
    const val PLAY = 1
    const val PAUSE = 2
    const val PLAY_PAUSE = 3
    const val NEXT = 4
    const val PREVIOUS = 5

    const val KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE = 353
    const val KEYCODE_BYD_AUTO_MEDIA_VOICE = 304
    const val KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG = 312
    const val KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE_DILINK3 = 331
    const val KEYCODE_BYD_CARPLAY_VOICE = 327
    const val KEYCODE_BYD_CARPLAY_VOICE_LONG = 328

    /** Whether [keyCode] is a voice key that opens Siri. */
    fun opensSiri(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
        keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE || keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG

    /** The voice keys sent only for CarPlay; they open Siri while a CarPlay session runs. */
    fun opensSiriWhileCarPlay(keyCode: Int): Boolean =
        keyCode == KEYCODE_BYD_CARPLAY_VOICE || keyCode == KEYCODE_BYD_CARPLAY_VOICE_LONG

    /** The two voice keys sent for a short press of the voice button. */
    fun isShortVoiceKey(keyCode: Int): Boolean =
        keyCode == KEYCODE_BYD_CARPLAY_VOICE || keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE

    /** The CarPlay press for [keyCode], or null when the key is not a media key CarPlay handles. */
    fun forKeyCode(keyCode: Int, experimentalDiLink3Keys: Boolean = false): Int? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE -> PLAY_PAUSE
        KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE_DILINK3 -> if (experimentalDiLink3Keys) PLAY_PAUSE else null
        else -> null
    }

    /**
     * The CarPlay press the CarPlay screen itself should claim for [keyCode], or null to let the key
     * go on to the system.
     */
    fun forCarPlayWindow(
        keyCode: Int,
        sessionActive: Boolean,
        inCall: Boolean,
    ): Int? {
        if (!sessionActive || inCall) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
            else -> null
        }
    }
}
