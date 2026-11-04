package app.tvplay.engine.session

import app.tvplay.engine.av.TvDisplaySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSessionTest {

    @Test
    fun streamServerConfigComputesBufferedAudioOutputEnabled() {
        val display = TvDisplaySpec(widthPixels = 1920, heightPixels = 1080)
        val enabledConfig = TvStreamServerConfig(
            deviceName = "TVPlay",
            deviceId = "AA:BB:CC:DD:EE:FF",
            btMac = "AA:BB:CC:DD:EE:FF",
            sourceVersion = "420.34",
            main = display,
            mainBufferedAudio = true,
            disableAudioOutput = false,
        )
        assertTrue(enabledConfig.bufferedAudioOutputEnabled)

        val mutedConfig = enabledConfig.copy(disableAudioOutput = true)
        assertFalse(mutedConfig.bufferedAudioOutputEnabled)
    }

    @Test
    fun wirelessIdentitySpecParsesBluetoothMacBytes() {
        val spec = TvWirelessIdentitySpec(
            bluetoothMac = "12:34:56:78:9A:BC",
            ssid = "DIRECT-TVPlay",
        )
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9a.toByte(), 0xbc.toByte()),
            spec.bluetoothMacBytes(),
        )
    }

    @Test
    fun usbDeviceFilterEnforcesU16Bounds() {
        val filter = UsbDeviceFilter(vendorId = 0x05ac, productId = 0x12a8)
        assertEquals(0x05ac, filter.vendorId)
        assertEquals(0x12a8, filter.productId)
    }

    @Test
    fun isNonPhoneBluetoothAccessoryExcludesTvRemotesAndSpeakersButKeepsIphones() {
        assertTrue(
            isNonPhoneBluetoothAccessory(
                name = "小米蓝牙语音遥控器",
                majorDeviceClass = android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL,
            ),
        )
        assertTrue(
            isNonPhoneBluetoothAccessory(
                name = "Bluetooth Remote",
                majorDeviceClass = null,
            ),
        )
        assertTrue(
            isNonPhoneBluetoothAccessory(
                name = "Living Room Soundbar",
                majorDeviceClass = android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO,
            ),
        )
        assertFalse(
            isNonPhoneBluetoothAccessory(
                name = "iPhone 16 Pro",
                majorDeviceClass = android.bluetooth.BluetoothClass.Device.Major.PHONE,
            ),
        )
        assertFalse(
            isNonPhoneBluetoothAccessory(
                name = "张三的手机",
                majorDeviceClass = android.bluetooth.BluetoothClass.Device.Major.PHONE,
            ),
        )
    }
}
