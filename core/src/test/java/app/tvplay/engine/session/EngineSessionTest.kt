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

    @Test
    fun firstTcpWatchdogResetsTimerOnRepeatedStartSessionAndDisarmsOnExternalAccept() {
        val listener = app.tvplay.engine.protocol.rtsp.AirPlayListenerIdentity(1)
        var cancelledCount = 0
        var firedCount = 0
        var latestAction: (() -> Unit)? = null
        val watchdog = FirstTcpWatchdog(
            listener = listener,
            schedule = { _, action ->
                latestAction = action
                ({ cancelledCount++ })
            },
            onTimeout = { firedCount++ },
        )

        watchdog.startSessionSent(100L)
        watchdog.startSessionSent(200L)
        assertEquals(1, cancelledCount)

        assertTrue(
            watchdog.accepted(
                app.tvplay.engine.protocol.rtsp.AirPlayTcpAccepted(
                    listener = listener,
                    internal = false,
                    acceptedAtNanos = 150L,
                ),
            ),
        )
        assertEquals(2, cancelledCount)
        latestAction?.invoke()
        assertEquals(0, firedCount)
    }

    @Test
    fun hotspotAddressPolicyRejectsUnscopedLinkLocalIpv6AndPrefersIpv4ForLan() {
        val unscopedLinkLocal = java.net.Inet6Address.getByAddress(
            null,
            byteArrayOf(
                0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0,
                0x02, 0x11, 0x22, 0xff.toByte(), 0xfe.toByte(), 0x33, 0x44, 0x55,
            ),
            0,
        )
        val scopedLinkLocal = java.net.Inet6Address.getByAddress(
            null,
            byteArrayOf(
                0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0,
                0x02, 0x11, 0x22, 0xff.toByte(), 0xfe.toByte(), 0x33, 0x44, 0x55,
            ),
            2,
        )
        val lanIpv4 = java.net.InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 88))

        // Even when preferredFamily is IPv6, unscoped link-local IPv6 (scopeId == 0) must not be selected over valid IPv4
        val selectedWithUnscoped = app.tvplay.engine.network.HotspotAddressPolicy.select(
            listOf(unscopedLinkLocal, lanIpv4),
            app.tvplay.engine.network.HotspotAddressPolicy.FAMILY_IPV6,
        )
        assertEquals(lanIpv4, selectedWithUnscoped)

        // When preferredFamily is IPv4 (as enforced for LAN_WIFI), IPv4 is selected even if scoped IPv6 is present
        val selectedForLan = app.tvplay.engine.network.HotspotAddressPolicy.select(
            listOf(scopedLinkLocal, lanIpv4),
            app.tvplay.engine.network.HotspotAddressPolicy.FAMILY_IPV4,
        )
        assertEquals(lanIpv4, selectedForLan)
    }

    @Test
    fun usbTunnelVpnServiceAttachWirelessFallsBackToPrimaryAddressWhenSecondaryCannotBind() {
        val service = app.tvplay.engine.network.UsbTunnelVpnService()
        val primaryLoopback = java.net.InetAddress.getByName("127.0.0.1")
        val unbindableSecondary = java.net.InetAddress.getByAddress(
            byteArrayOf(198.toByte(), 51, 100, 254.toByte()),
        )
        val config = TvStreamServerConfig(
            deviceName = "TVPlay",
            deviceId = "02:11:22:33:44:55",
            btMac = "02:11:22:33:44:55",
            sourceVersion = "950.7.1",
            main = TvDisplaySpec(widthPixels = 1920, heightPixels = 1080),
            port = 0,
        )
        val owner = app.tvplay.engine.protocol.rtsp.AirPlayListenerIdentity(1)
        val result = service.attachWireless(
            bindAddress = primaryLoopback,
            config = config,
            identity = app.tvplay.engine.auth.AccessoryIdentity.generate(),
            pairings = app.tvplay.engine.auth.PairingRegistry(),
            mfi = null,
            listener = object : app.tvplay.engine.protocol.rtsp.AirPlaySessionListener {},
            listenerIdentity = owner,
            media = object : app.tvplay.engine.protocol.rtsp.AirPlayMediaHandler {},
            additionalBindAddresses = listOf(unbindableSecondary),
        )
        assertEquals(app.tvplay.engine.network.UsbTunnelVpnService.AttachResult.Started, result)
        assertTrue((service.boundPort() ?: 0) > 0)
        service.detachWireless(owner)
    }
}
