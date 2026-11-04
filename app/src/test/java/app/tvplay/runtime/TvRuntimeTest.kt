package app.tvplay.runtime

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import app.tvplay.config.TvPreferencesStore
import app.tvplay.ui.stage.TvStageActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvRuntimeTest {

    @Test
    fun diagnosticRedactorStripsPrivateAddressesAndCredentialsFromLogs() {
        TvDiagnosticsLogger.clearRing()
        TvDiagnosticsLogger.record("Connected to 192.168.1.42 over AA:BB:CC:DD:EE:FF")
        val lines = TvDiagnosticsLogger.recentLines()
        assertEquals(1, lines.size)
        val line = lines.first()
        assertFalse("192.168.1.42" in line)
        assertFalse("AA:BB:CC:DD:EE:FF" in line)
    }

    @Test
    fun diagnosticRedactorRetainsProtocolMilestones() {
        val redacted = DiagnosticRedactor.redact("RTSP SETUP completed on stream 110")
        assertNotNull(redacted)
        assertTrue("RTSP SETUP" in redacted!!)
    }

    @Test
    fun bootAutoStartReceiverHonorsAutoStartPreferenceAndRecordsDiagnosticSnapshot() {
        val context = RuntimeEnvironment.getApplication()
        val receiver = BootAutoStartReceiver()

        TvPreferencesStore.saveAutoStartOnBoot(context, false)
        shadowOf(context).clearNextStartedActivities()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(context).nextStartedActivity)
        assertTrue("launchEnabledAtBoot=false" in StartupDiagnosticSnapshot.report(context))

        TvPreferencesStore.saveAutoStartOnBoot(context, true)
        TvPreferencesStore.saveWirelessEnabled(context, false)
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val launched = shadowOf(context).nextStartedActivity
        assertNotNull(launched)
        assertEquals(TvStageActivity::class.java.name, launched.component?.className)
        assertFalse(launched.getBooleanExtra("wireless", true))
        assertTrue("launchEnabledAtBoot=true" in StartupDiagnosticSnapshot.report(context))
    }

    @Test
    fun bluetoothAutoConnectReceiverFiltersByTogglePairedPhoneAndActiveSession() {
        val context = RuntimeEnvironment.getApplication()
        val receiver = BluetoothAutoConnectReceiver()
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val matchingDevice = adapter.getRemoteDevice("AA:BB:CC:11:22:33")
        val otherDevice = adapter.getRemoteDevice("AA:BB:CC:99:88:77")

        CarPlayBackgroundSession.clear()
        PhoneWakeDiagnostics.resetForTest()

        // 1. Toggle disabled -> ignored
        TvPreferencesStore.saveConnectOnPhoneBluetooth(context, false)
        TvPreferencesStore.savePairedPhone(context, "AA:BB:CC:11:22:33", "iPhone")
        receiver.onReceive(
            context,
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, matchingDevice),
        )
        assertTrue("result=ignored:toggleOff" in PhoneWakeDiagnostics.report())
        assertNull(shadowOf(context).nextStartedActivity)

        // 2. Toggle enabled, non-matching device -> ignored
        TvPreferencesStore.saveConnectOnPhoneBluetooth(context, true)
        receiver.onReceive(
            context,
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, otherDevice),
        )
        assertTrue("result=ignored:otherDevice" in PhoneWakeDiagnostics.report())
        assertNull(shadowOf(context).nextStartedActivity)

        // 3. Toggle enabled, matching device -> launches TvStageActivity in wireless mode
        receiver.onReceive(
            context,
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, matchingDevice),
        )
        assertTrue("result=startActivityRequested" in PhoneWakeDiagnostics.report())
        val started = shadowOf(context).nextStartedActivity
        assertNotNull(started)
        assertEquals(TvStageActivity::class.java.name, started.component?.className)
        assertTrue(started.getBooleanExtra("wireless", false))
        assertNotNull(started.getStringExtra(BluetoothAutoConnectReceiver.EXTRA_AUTO_CONNECT))
    }

    @Test
    fun foregroundServiceAndDiagnosticExportCompleteCleanly() {
        val context = RuntimeEnvironment.getApplication()
        TvSessionForegroundService.startOrUpdate(context, wireless = true, status = "iPhone 16 Pro")
        TvSessionForegroundService.startOrUpdate(context, wireless = false, status = "iPhone 16 Pro")
        TvSessionForegroundService.stop(context)

        val serviceController = Robolectric.buildService(TvSessionForegroundService::class.java).create()
        serviceController.get().onStartCommand(
            Intent(context, TvSessionForegroundService::class.java)
                .putExtra("wireless", true)
                .putExtra("device_name", "iPhone"),
            0,
            1,
        )
        serviceController.get().onStartCommand(
            Intent(context, TvSessionForegroundService::class.java).apply {
                action = TvSessionForegroundService.ACTION_STOP
            },
            0,
            2,
        )
        serviceController.destroy()

        val logFile = TvDiagnosticsLogger.openSessionLog(context, "Unit test session header")
        TvDiagnosticsLogger.record("Test diagnostic line 1")
        logFile.awaitIdle(1_000L)
        val report = TvDiagnosticsLogger.buildReport(context, "Custom Extra Header")
        assertTrue("Custom Extra Header" in report)
        val exported = TvDiagnosticsLogger.exportReport(context, "Export Test")
        assertTrue(exported.displayPath.isNotBlank())
        logFile.close()
    }
}
