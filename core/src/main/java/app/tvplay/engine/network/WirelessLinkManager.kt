package app.tvplay.engine.network

import android.content.Context
import app.tvplay.engine.protocol.iap2.Iap2WirelessSecurity
import app.tvplay.engine.session.TvFailureReason
import app.tvplay.engine.session.TvWirelessMode
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

enum class WirelessHotspotBackend(val label: String) {
    WIFI_P2P("Wi-Fi P2P"),
    EXISTING_WIFI("Existing Wi-Fi / Same LAN"),
}

/** The live Wi-Fi credentials and interface details for a wireless CarPlay network. */
class WirelessHotspotInfo(
    val ssid: String,
    val passphrase: String,
    val security: Iap2WirelessSecurity,
    val channel: Int,
    val frequencyMHz: Int?,
    val bssid: String?,
    val interfaceName: String?,
    val hostAddress: InetAddress?,
    val bandLabel: String,
    val backend: WirelessHotspotBackend,
    /** Addresses on the selected interface that discovery and TCP must both serve. */
    val hostAddresses: List<InetAddress> = listOfNotNull(hostAddress),
    /** Wi-Fi AP hint for 0x5703; independent of the receiver's AirPlay identity in [bssid]. */
    val accessPointBssid: ByteArray? = null,
) {
    override fun toString(): String =
        "WirelessHotspotInfo(backend=${backend.label}, ssid='$ssid', " +
            "passphrase=<redacted>, security=$security, channel=$channel, " +
            "frequencyMHz=$frequencyMHz, bssid='$bssid', interfaceName=$interfaceName, " +
            "hostAddress=$hostAddress, bandLabel='$bandLabel')"
}

/** Prepares a wireless network and owns only the resources acquired by this manager. */
interface WirelessHotspotManager : Closeable {
    /**
     * Creates or attaches to a wireless network and waits up to [timeoutMillis] for its live
     * configuration and interface. Implementations must not be called on the main thread.
     */
    fun start(timeoutMillis: Long): WirelessHotspotInfo

    /** Confirms the selected interface and address remain usable before publishing. */
    fun validateReady() {}

    /** The authenticated wireless session has rendered CarPlay; AP creation alone is insufficient. */
    fun onCarPlayConfirmed() {}

    /** Counts reported by the framework, when available; never contains station identities. */
    fun connectionDiagnosticSnapshot(): String = "association=not_exposed"
}

/**
 * Coordinates wireless CarPlay link setup for the two supported TV modes:
 * - [TvWirelessMode.WIFI_DIRECT] via [WifiDirectGroupLink]
 * - [TvWirelessMode.LAN_WIFI] via [LanNetworkLink]
 */
class WirelessLinkManager(
    private val delegate: WirelessHotspotManager,
) : WirelessHotspotManager by delegate {
    constructor(
        context: Context,
        mode: TvWirelessMode,
        lanSsid: String = "",
        lanPassphrase: String = "",
        lanInterface: String? = null,
        wifiDirectChannel: Int = WifiP2pChannels.AUTO,
        onDiagnostic: (String) -> Unit = {},
        onNetworkChanged: () -> Unit = {},
    ) : this(
        create(
            context = context,
            mode = mode,
            lanSsid = lanSsid,
            lanPassphrase = lanPassphrase,
            lanInterface = lanInterface,
            wifiDirectChannel = wifiDirectChannel,
            onDiagnostic = onDiagnostic,
            onNetworkChanged = onNetworkChanged,
        ),
    )

    companion object {
        fun create(
            context: Context,
            mode: TvWirelessMode,
            lanSsid: String = "",
            lanPassphrase: String = "",
            lanInterface: String? = null,
            wifiDirectChannel: Int = WifiP2pChannels.AUTO,
            onDiagnostic: (String) -> Unit = {},
            onNetworkChanged: () -> Unit = {},
        ): WirelessHotspotManager = when (mode) {
            TvWirelessMode.WIFI_DIRECT -> WifiDirectGroupLink(
                context = context,
                diagnostic = onDiagnostic,
                preferredChannel = wifiDirectChannel,
            )
            TvWirelessMode.LAN_WIFI -> LanNetworkLink(
                context = context,
                ssid = lanSsid,
                passphrase = lanPassphrase,
                onDiagnostic = onDiagnostic,
                onNetworkChanged = onNetworkChanged,
                preferredInterface = lanInterface,
            )
        }
    }
}

/**
 * Converts IEEE 802.11 frequency in MHz to its channel number.
 *
 * Android's [android.net.wifi.p2p.WifiP2pGroup.getFrequency] reports MHz, while the iAP2
 * wireless CarPlay payload expects a channel number.
 */
internal fun wifiFrequencyMhzToChannel(frequencyMHz: Int): Int? = when {
    frequencyMHz in 2412..2472 && (frequencyMHz - 2407) % 5 == 0 ->
        (frequencyMHz - 2407) / 5
    frequencyMHz == 2484 -> 14
    frequencyMHz in 5160..5895 && (frequencyMHz - 5000) % 5 == 0 ->
        (frequencyMHz - 5000) / 5
    frequencyMHz in 5955..7115 && (frequencyMHz - 5950) % 5 == 0 ->
        (frequencyMHz - 5950) / 5
    else -> null
}

/** Converts an IEEE 802.11 channel number to MHz when its band is known. */
internal fun wifiChannelToFrequencyMhz(channel: Int, band: Int? = null): Int? = when {
    channel == 0 -> null
    band == 1 && channel in 1..13 -> 2407 + channel * 5
    band == 1 && channel == 14 -> 2484
    band == 2 && channel in 32..177 -> 5000 + channel * 5
    band == 4 && channel in 1..233 -> 5950 + channel * 5
    else -> null
}

/** Maps Android's [android.net.wifi.SoftApConfiguration] band constants to a user-facing label. */
internal fun wifiBandLabel(band: Int?): String? = when (band) {
    1 -> "2.4 GHz"
    2 -> "5 GHz"
    3 -> "2.4 / 5 GHz (auto)"
    4 -> "6 GHz"
    else -> null
}

object WirelessStartupPolicy {
    const val HOTSPOT_READY_MILLIS = 15_000L
    const val INTERFACE_POLL_MILLIS = 250L
    const val STABLE_SAMPLES = 3
    const val FIRST_TCP_MILLIS = 30_000L
    const val MAX_STARTUP_RETRIES = 5
    const val STABLE_SESSION_MILLIS = 60_000L
}

enum class WirelessStartupFailure {
    HOTSPOT_NOT_READY, FIRST_TCP_TIMEOUT, HOTSPOT_CONFIGURATION,

    /** The head unit's Android Bluetooth accepts connections that reach no phone. */
    BLUETOOTH_UNUSABLE,

    /** Android 13+ asked to turn the car hotspot off for Wi-Fi Direct, and the driver said no. */
    WIFI_DIRECT_BESIDE_HOTSPOT;

    /** False when no further attempt can change what went wrong. */
    val retryable: Boolean get() = this != HOTSPOT_CONFIGURATION && this != BLUETOOTH_UNUSABLE &&
        this != WIFI_DIRECT_BESIDE_HOTSPOT
}

class WirelessStartupException(
    val reason: WirelessStartupFailure,
    message: String,
    val knownCause: TvFailureReason? = null,
) : IOException(message)

/** Observes startup without changing connection deadlines, address selection or retry behavior. */
internal class WirelessStartupDiagnostics(
    private val sample: () -> String,
    private val log: (String) -> Unit,
    private val intervalMillis: Long = 10_000,
    private val nowNs: () -> Long = System::nanoTime,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val startedNanos = nowNs()
    private var authenticated = false
    private var wifiConfigs = 0
    private var startRequests = 0
    private var tcpAccepted = 0
    private var sessionActive = false
    private var firstStartRequestNs: Long? = null
    private var firstTcpAfterStartMs: Long? = null
    @Volatile private var lastSnapshot = ""
    private val worker = Thread(::observe, "diplay-wireless-diagnostics").apply { isDaemon = true }

    init { require(intervalMillis > 0) }

    @Synchronized fun start() {
        if (!closed.get() && worker.state == Thread.State.NEW) worker.start()
    }

    @Synchronized fun controlProgress(message: String) {
        when (message) {
            "iap2 authentication accepted" -> authenticated = true
            "iap2 tx=0x5703 accessory-wifi-configuration",
            "iap2 tx=0x5703 post-transport accessory-wifi-configuration" -> wifiConfigs++
            "iap2 tx=0x4301 carplay-start-session" -> {
                startRequests++
                if (firstStartRequestNs == null) firstStartRequestNs = nowNs()
            }
        }
    }

    @Synchronized fun connectionAccepted() {
        tcpAccepted++
        if (firstTcpAfterStartMs == null) {
            firstStartRequestNs?.let { firstTcpAfterStartMs = elapsedMillis(it) }
        }
    }
    @Synchronized fun sessionActive() { sessionActive = true }

    @Synchronized fun summary(): String {
        val waitingFor = when {
            sessionActive -> "none"
            tcpAccepted > 0 -> "AirPlay_protocol"
            startRequests > 0 -> "WiFi_discovery_or_AirPlay_TCP"
            authenticated -> "WiFi_configuration_or_start_request"
            else -> "Bluetooth_iAP2_authentication"
        }
        return "wireless startup elapsedMs=${elapsedMillis(startedNanos)} " +
            "authenticated=$authenticated wifiConfigs=$wifiConfigs startRequests=$startRequests " +
            "tcpAccepted=$tcpAccepted sessionActive=$sessionActive waitingFor=$waitingFor " +
            "startRequestAgeMs=${firstStartRequestNs?.let(::elapsedMillis) ?: "none"} " +
            "firstTcpAfterStartMs=${firstTcpAfterStartMs ?: "none"}"
    }

    private fun elapsedMillis(since: Long): Long = (nowNs() - since).coerceAtLeast(0) / 1_000_000

    private fun observe() {
        try {
            while (!closed.get()) {
                val snapshot = try { sample() } catch (error: Exception) {
                    "sampling=unavailable failureClass=${error.javaClass.simpleName}"
                }
                if (closed.get()) return
                lastSnapshot = snapshot
                emit(summary())
                emitSnapshot(snapshot)
                Thread.sleep(intervalMillis)
            }
        } catch (_: InterruptedException) {
            // Teardown interrupts sleep or an in-flight Android callback wait.
        }
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.interrupt()
        emit("${summary()} observation=ended")
        emitSnapshot(lastSnapshot, cached = true)
    }

    private fun emitSnapshot(snapshot: String, cached: Boolean = false) {
        // The report redactor caps each log line at 700 characters. Never append the kernel
        // counters to the already detailed startup/interface/P2P/Bonjour state line.
        snapshot.lineSequence().filter { it.isNotBlank() }.take(4).forEach { line ->
            emit("wireless snapshot${if (cached) " cached=true" else ""} $line")
        }
    }

    private fun emit(message: String) {
        try { log(message) } catch (_: RuntimeException) {
            // An observer cannot fail startup or teardown.
        }
    }
}

/** Counts are useful in exported reports; literals, hardware identifiers and names are not. */
internal object WirelessInterfaceDiagnostics {
    fun snapshot(interfaceName: String?): String {
        if (interfaceName == null) return "interfaceState=unknown"
        return try {
            val network = NetworkInterface.getByName(interfaceName)
                ?: return "interfaceState=missing"
            "interfaceState=${if (network.isUp) "up" else "down"} multicast=${network.supportsMulticast()} " +
                addressSummary(Collections.list(network.inetAddresses))
        } catch (error: Exception) {
            "interfaceState=unavailable failureClass=${error.javaClass.simpleName}"
        }
    }

    fun addressSummary(addresses: List<InetAddress>): String =
        "ipv4Usable=${addresses.count { it is Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }} " +
            "ipv6LinkLocal=${addresses.count { it is Inet6Address && it.isLinkLocalAddress }} " +
            "ipv6Scoped=${addresses.count { it is Inet6Address && it.isLinkLocalAddress && it.scopeId > 0 }}"
}

/**
 * Optional kernel receive counters sampled by the existing wireless observer, never a media thread.
 * Android/OEM policy may hide these files; an inaccessible source is tried only once per run.
 * UDP counters cover the device network namespace, not a particular interface, app or RTP stream.
 */
internal class WirelessReceiveDiagnostics(
    interfaceName: String?,
    private val read: (String, Int) -> String = ::readBoundedDiagnosticFile,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val interfaceSource = CounterSource("ifaceRx") {
        if (interfaceName == null || !INTERFACE_NAME.matches(interfaceName)) throw IOException()
        linkedMapOf<String, Long>().apply {
            INTERFACE_COUNTERS.forEach { (file, label) ->
                put(label, counter(read("/sys/class/net/$interfaceName/statistics/$file", 32).trim()))
            }
        }
    }
    private val udp4Source = CounterSource("udp4") {
        parseUdp4(read("/proc/net/snmp", MAX_PROC_BYTES))
    }
    private val udp6Source = CounterSource("udp6") {
        parseUdp6(read("/proc/net/snmp6", MAX_PROC_BYTES))
    }
    private var previousSampleNs: Long? = null

    fun snapshot(): String {
        val now = nowNs()
        val elapsed = previousSampleNs?.let { ((now - it).coerceAtLeast(0)) / 1_000_000 }
        previousSampleNs = now
        val prefix = "receiveCounters windowMs=${elapsed ?: "baseline"} udpScope=device "
        // Keep every source on its own short exported line, including full-width Long deltas.
        return listOf(interfaceSource, udp4Source, udp6Source).joinToString("\n") {
            prefix + it.snapshot()
        }
    }

    private class CounterSource(private val label: String, private val sample: () -> Map<String, Long>) {
        private var previous: Map<String, Long>? = null
        private var unavailable: String? = null

        fun snapshot(): String {
            unavailable?.let { return "$label=unavailable failureClass=$it" }
            val current = try { sample() } catch (error: Exception) {
                val type = error.javaClass.simpleName
                unavailable = type
                return "$label=unavailable failureClass=$type"
            }
            val before = previous
            previous = current
            if (before == null) return "$label=baseline"
            // Interface recreation or counter wrap must never appear as a negative loss count.
            if (current.keys != before.keys || current.any { (key, value) -> value < before.getValue(key) }) {
                return "$label=reset"
            }
            return "$label=sampled " + current.entries.joinToString(" ") { (key, value) ->
                "$label${key}Delta=${value - before.getValue(key)}"
            }
        }
    }

    companion object {
        private const val MAX_PROC_BYTES = 16 * 1024
        private val INTERFACE_NAME = Regex("[A-Za-z0-9_.-]{1,15}")
        private val INTERFACE_COUNTERS = linkedMapOf(
            "rx_packets" to "Packets", "rx_bytes" to "Bytes", "rx_dropped" to "Dropped",
            "rx_errors" to "Errors", "rx_missed_errors" to "MissedErrors",
        )
        private val UDP_COUNTERS = listOf("InDatagrams", "InErrors", "RcvbufErrors", "InCsumErrors")

        internal fun parseUdp4(text: String): Map<String, Long> {
            if (text.length > MAX_PROC_BYTES) throw IOException()
            val rows = text.lineSequence().filter { it.startsWith("Udp:") }.take(3).toList()
            if (rows.size != 2) throw IOException()
            val names = rows[0].trim().split(Regex("\\s+")).drop(1)
            val values = rows[1].trim().split(Regex("\\s+")).drop(1)
            if (names.size != values.size || names.distinct().size != names.size) throw IOException()
            return selectedUdpCounters(names.zip(values).toMap())
        }

        internal fun parseUdp6(text: String): Map<String, Long> {
            if (text.length > MAX_PROC_BYTES) throw IOException()
            val values = linkedMapOf<String, String>()
            text.lineSequence().filter { it.startsWith("Udp6") }.forEach { row ->
                val parts = row.trim().split(Regex("\\s+"))
                if (parts.size != 2 || values.put(parts[0].removePrefix("Udp6"), parts[1]) != null) {
                    throw IOException()
                }
            }
            return selectedUdpCounters(values)
        }

        private fun selectedUdpCounters(values: Map<String, String>): Map<String, Long> =
            linkedMapOf<String, Long>().apply {
                UDP_COUNTERS.forEach { key ->
                    val value = values[key]
                    // Older kernels may omit checksum-specific accounting.
                    if (value == null && key == "InCsumErrors") return@forEach
                    put(key, counter(value ?: throw IOException()))
                }
            }

        private fun counter(value: String): Long =
            value.toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException()
    }
}

/** Read only a small, fixed diagnostic file; never export its content or a read exception message. */
internal fun readBoundedDiagnosticFile(path: String, maxBytes: Int): String = File(path).inputStream().use { input ->
    val bytes = ByteArray(maxBytes + 1)
    var count = 0
    while (count < bytes.size) {
        val read = input.read(bytes, count, bytes.size - count)
        if (read < 0) break
        if (read == 0) throw IOException()
        count += read
    }
    if (count > maxBytes) throw IOException()
    String(bytes, 0, count, Charsets.US_ASCII)
}
