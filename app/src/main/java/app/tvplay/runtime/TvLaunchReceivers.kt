package app.tvplay.runtime

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.tvplay.config.TvPreferencesStore
import app.tvplay.ui.stage.CarPlayScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

typealias BootReceiver = BootAutoStartReceiver
typealias PhoneBluetoothReceiver = BluetoothAutoConnectReceiver

/**
 * Starts the CarPlay stage screen when the TV / display finishes booting or the app is updated,
 * if auto-start on boot is enabled in [TvPreferencesStore].
 */
class BootAutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        TvLanOtaManager.reportPendingUpgradeSuccessAsync(context)
        val enabled = TvPreferencesStore.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, enabled)
        if (!enabled) return
        runCatching {
            context.startActivity(
                CarPlayScreen.intent(context)
                    .putExtra("wireless", TvPreferencesStore.loadAutoConnectWireless(context))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            StartupDiagnosticSnapshot.launchResult(context, null)
        }.onFailure { error ->
            StartupDiagnosticSnapshot.launchResult(
                context,
                error as? RuntimeException ?: RuntimeException(error),
            )
        }
    }
}

/**
 * Starts wireless CarPlay when the paired iPhone connects to the TV / display's Bluetooth.
 *
 * The user enables this opt-in setting and pairs the iPhone once through the wireless setup flow,
 * which records the iPhone's Bluetooth address. Connections from any other Bluetooth device are
 * ignored.
 */
class BluetoothAutoConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (TvBluetoothSinkGuard.isSinkProfileAction(action) || action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            TvBluetoothSinkGuard.onBluetoothEvent(context, device, action)
        }
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED || device == null) return
        val address = runCatching { device.address }.getOrNull()
        val enabled = TvPreferencesStore.loadConnectOnPhoneBluetooth(context)
        val savedPhone = TvPreferencesStore.loadPairedPhoneAddress(context)
        val activeSession = CarPlayBackgroundSession.hasSession()
        PhoneWakeDiagnostics.attempt(
            deviceAddress = address,
            savedPhoneAddress = savedPhone,
            toggleEnabled = enabled,
            sessionActive = activeSession,
        )
        if (!enabled) {
            PhoneWakeDiagnostics.result("ignored:toggleOff")
            return
        }
        if (savedPhone == null) {
            PhoneWakeDiagnostics.result("ignored:noSavedPhone")
            return
        }
        if (!address.equals(savedPhone, ignoreCase = true)) {
            PhoneWakeDiagnostics.result("ignored:otherDevice")
            return
        }
        if (activeSession) {
            PhoneWakeDiagnostics.result("ignored:sessionActive")
            return
        }
        val token = UUID.randomUUID().toString()
        launchToken = token
        runCatching {
            context.startActivity(
                CarPlayScreen.intent(context)
                    .putExtra("wireless", true)
                    .putExtra(EXTRA_AUTO_CONNECT, token)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    ),
            )
        }.fold(
            onSuccess = { PhoneWakeDiagnostics.result("startActivityRequested") },
            onFailure = { error -> PhoneWakeDiagnostics.result("failed:${error.javaClass.simpleName}") },
        )
    }

    companion object {
        const val EXTRA_AUTO_CONNECT = "auto_connect_token"

        @Volatile
        var launchToken: String? = null
    }
}

/**
 * Records the last Bluetooth auto-connect broadcast and whether Android allowed the activity launch.
 *
 * Raw Bluetooth MAC addresses are never stored here; only boolean match flags are kept so diagnostic
 * reports do not leak device identifiers.
 */
object PhoneWakeDiagnostics {
    @Volatile
    private var snapshot: String = "lastAttempt=none"

    fun attempt(
        deviceAddress: String?,
        savedPhoneAddress: String?,
        toggleEnabled: Boolean,
        sessionActive: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val matched = savedPhoneAddress != null && deviceAddress.equals(savedPhoneAddress, ignoreCase = true)
        snapshot = "lastAttempt=$time sdk=$sdkInt toggle=$toggleEnabled " +
            "savedPhone=${savedPhoneAddress != null} addressPresent=${!deviceAddress.isNullOrBlank()} " +
            "matched=$matched sessionActive=$sessionActive result=pending"
    }

    fun result(outcome: String) {
        snapshot = snapshot.replace(Regex("result=[^ ]+$"), "result=$outcome")
    }

    fun report(): String = snapshot

    internal fun resetForTest() {
        snapshot = "lastAttempt=none"
    }
}

/**
 * Remembers that a CarPlay-affecting setting changed while a session was active so the stage or
 * workbench can offer a one-tap reconnect when the user returns.
 */
object PendingReconnect {
    @Volatile
    private var pending = false

    fun mark() {
        pending = true
    }

    fun isPending(): Boolean = pending

    fun clear() {
        pending = false
    }
}
