package app.tvplay.engine.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import app.tvplay.engine.protocol.iap2.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

typealias ExistingWifiManager = LanNetworkLink

/**
 * Attaches to an existing home LAN (Wi-Fi or wired Ethernet) on Android TV.
 * Never creates an AP, joins Wi-Fi, or changes system routing.
 */
class LanNetworkLink(
    context: Context,
    private val ssid: String,
    private val passphrase: String,
    private val onDiagnostic: (String) -> Unit = {},
    private val onNetworkChanged: () -> Unit = {},
    private val preferredInterface: String? = null,
) : WirelessHotspotManager {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        ?: throw IllegalStateException("ConnectivityManager is unavailable")
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val lock = Any()
    private val invalidated = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var selected: Network? = null
    @Volatile private var host: InetAddress? = null
    @Volatile private var hosts: List<InetAddress> = emptyList()
    private var interfaceIndex = 0
    @Volatile private var interfaceName: String? = null
    private var callbackRegistered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (network == selected) invalidate("network lost")
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            val tracked = network == selected ||
                (selected == null && properties.interfaceName == interfaceName)
            if (tracked && !sameLink(properties)) invalidate("interface or address changed")
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ExistingWifiManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        check(selected == null) { "ExistingWifiManager has already started" }
        val started = System.nanoTime()
        val configured = preferredInterface?.trim()?.takeIf { it.isNotEmpty() }
        while (!closed) {
            val allNetworks = runCatching { connectivity.allNetworks.toList() }.getOrDefault(emptyList())
            val network: Network? = if (configured != null) {
                allNetworks.firstOrNull {
                    runCatching { connectivity.getLinkProperties(it)?.interfaceName }.getOrNull() == configured
                }
            } else {
                val wifiNetworks = allNetworks.filter { candidate ->
                    runCatching { connectivity.getNetworkCapabilities(candidate) }.getOrNull()?.let {
                        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    } == true
                }
                val ethNetworks = allNetworks.filter { candidate ->
                    runCatching { connectivity.getNetworkCapabilities(candidate) }.getOrNull()?.let {
                        it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) &&
                            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    } == true
                }
                wifiNetworks.firstOrNull()
                    ?: ethNetworks.firstOrNull()
                    ?: runCatching { connectivity.activeNetwork }.getOrNull()?.takeIf { active ->
                        runCatching { connectivity.getNetworkCapabilities(active) }.getOrNull()
                            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
                    }
            }
            val properties = network?.let { runCatching { connectivity.getLinkProperties(it) }.getOrNull() }
            val fallbackIface = if (properties?.interfaceName == null && configured == null) {
                discoverFallbackLanInterface()
            } else {
                null
            }
            val name = properties?.interfaceName ?: configured ?: fallbackIface?.name
            val iface = fallbackIface ?: name?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
            val propAddresses = properties?.linkAddresses?.mapNotNull { it.address }.orEmpty()
            val ifaceAddresses = iface?.let {
                runCatching { Collections.list(it.inetAddresses) }.getOrDefault(emptyList())
            }.orEmpty()
            val linkAddresses: List<InetAddress> = (propAddresses + ifaceAddresses)
                .distinctBy { it.address.toList() }
            val address = if (iface == null) null else wirelessHostAddress(linkAddresses, iface.index)
            if (iface != null && name != null && address != null) {
                val addresses = existingWifiHostAddresses(linkAddresses, iface.index)
                var frequency: Int? = null
                var channel = 0
                var liveSsid: String? = null
                var observedSecurity = -1
                var scanCapabilities: String? = null
                val configuredSsid = ssid.trim().takeUnless { it.isEmpty() || it.equals("TVPlay", ignoreCase = true) }
                if (wifi != null) {
                    val capabilities = network?.let { runCatching { connectivity.getNetworkCapabilities(it) }.getOrNull() }
                    val networkInfo = if (Build.VERSION.SDK_INT >= 31) capabilities?.transportInfo as? WifiInfo else null
                    @Suppress("DEPRECATION")
                    val stationInfo = runCatching { wifi.connectionInfo }.getOrNull()
                    val info = listOfNotNull(networkInfo, stationInfo).firstOrNull { readableSsid(it) != null }
                        ?: networkInfo ?: stationInfo
                    liveSsid = readableSsid(info)
                    frequency = runCatching { info?.frequency }.getOrNull()?.takeIf { it > 0 }
                    channel = (frequency?.let(::wifiFrequencyMhzToChannel) ?: 0).takeIf { it in 0..255 } ?: 0
                    val rawBssid = runCatching { info?.bssid }.getOrNull()
                    if (Build.VERSION.SDK_INT >= 31 && info != null) {
                        observedSecurity = runCatching { info.currentSecurityType }.getOrDefault(-1)
                    }
                    val targetScanSsid = configuredSsid ?: liveSsid
                    val matchingScan = runCatching {
                        @Suppress("MissingPermission")
                        val scans = wifi.scanResults.orEmpty()
                        val candidates = scans.filter {
                            (!targetScanSsid.isNullOrBlank() && it.SSID == targetScanSsid) ||
                                (!rawBssid.isNullOrBlank() && it.BSSID.equals(rawBssid, ignoreCase = true))
                        }
                        candidates.firstOrNull { it.frequency >= 5000 } ?: candidates.firstOrNull()
                    }.getOrNull()
                    if (matchingScan != null) {
                        scanCapabilities = matchingScan.capabilities
                        if ((frequency == null || (frequency < 5000 && matchingScan.frequency >= 5000)) && matchingScan.frequency > 0) {
                            frequency = matchingScan.frequency
                            channel = (wifiFrequencyMhzToChannel(matchingScan.frequency) ?: 0).takeIf { it in 0..255 } ?: 0
                        }
                    }
                }
                if (configured == null && liveSsid != null && configuredSsid != null && !liveSsid.equals(configuredSsid, ignoreCase = false)) {
                    onDiagnostic("Configured SSID ($configuredSsid) differs from connected Wi-Fi ($liveSsid); using configured SSID for iPhone")
                }
                val capsUpper = scanCapabilities?.uppercase().orEmpty()
                val scanIndicatesSecured = capsUpper.contains("WPA") ||
                    capsUpper.contains("RSN") ||
                    capsUpper.contains("PSK") ||
                    capsUpper.contains("SAE") ||
                    capsUpper.contains("WEP") ||
                    capsUpper.contains("EAP")
                val scanIndicatesOpen = scanCapabilities != null && !scanIndicatesSecured
                val isKnownOpen = (Build.VERSION.SDK_INT >= 31 && observedSecurity == WifiInfo.SECURITY_TYPE_OPEN) ||
                    (observedSecurity == -1 && scanIndicatesOpen)
                val finalSsid = configuredSsid ?: liveSsid
                    ?: throw WirelessStartupException(
                        WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                        "未能读取当前路由器 Wi-Fi 名称 (SSID)，请配置 iPhone 所连的路由器 Wi-Fi 名称与密码，或改用免配置的 Wi-Fi Direct 直连",
                    )
                val effectivePassphrase = if (isKnownOpen) "" else passphrase
                if (!isKnownOpen && effectivePassphrase.isEmpty()) {
                    throw WirelessStartupException(
                        WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                        "局域网模式需向 iPhone 下发路由器 Wi-Fi 密码以完成无线 CarPlay 握手，请填写「$finalSsid」的 Wi-Fi 密码，或改用免密码的 Wi-Fi Direct 直连",
                    )
                }
                if (channel <= 0) {
                    channel = 36
                }
                val effectiveSecurity = if (effectivePassphrase.isEmpty()) {
                    Iap2WirelessSecurity.NONE
                } else {
                    Iap2WirelessSecurity.WPA_WPA2
                }

                synchronized(lock) {
                    check(!closed) { "ExistingWifiManager is closed" }
                    selected = network
                    host = address
                    hosts = addresses
                    interfaceIndex = iface.index
                    interfaceName = name
                    val request = requestWithoutDefaultCapabilities()
                    if (configured == null && network != null) {
                        val activeCaps = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
                        when {
                            activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ->
                                request.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ->
                                request.addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        }
                    }
                    runCatching {
                        connectivity.registerNetworkCallback(
                            request.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                            callback,
                        )
                        callbackRegistered = true
                    }
                }
                val current = if (network == null) null else runCatching { connectivity.getLinkProperties(network) }.getOrNull()
                if ((current != null && !sameLink(current)) || invalidated.get()) {
                    synchronized(lock) {
                        selected = null
                        if (callbackRegistered) {
                            runCatching { connectivity.unregisterNetworkCallback(callback) }
                            callbackRegistered = false
                        }
                    }
                    throw IOException("局域网网络在启动期间发生变化，正在重新连接")
                }
                onDiagnostic(
                    "Existing LAN attached iface=$name host=${address.hostAddress} " +
                        "networkNameReadable=${liveSsid != null} channel=$channel security=$effectiveSecurity " +
                        "receiverIdentity=saved" + if (configured != null) " configuredInterface=$configured" else "",
                )
                return WirelessHotspotInfo(
                    ssid = finalSsid,
                    passphrase = effectivePassphrase,
                    security = effectiveSecurity,
                    channel = channel,
                    frequencyMHz = frequency,
                    bssid = null,
                    interfaceName = name,
                    hostAddress = address,
                    bandLabel = when {
                        frequency == null -> "局域网"
                        frequency < 2500 -> "2.4 GHz"
                        frequency < 5955 -> "5 GHz"
                        else -> "6 GHz"
                    },
                    backend = WirelessHotspotBackend.EXISTING_WIFI,
                    hostAddresses = addresses,
                    accessPointBssid = null,
                )
            }
            if ((System.nanoTime() - started) / 1_000_000 >= timeoutMillis) {
                val interfaceMissing = configured != null &&
                    runCatching { NetworkInterface.getByName(configured) }.getOrNull() == null
                throw IOException(
                    if (interfaceMissing) {
                        "未找到指定的网络接口 $configured，请检查网络接口设置"
                    } else {
                        "当前电视未连接局域网 Wi-Fi 或网线，请先将电视与 iPhone 连接至同一路由器"
                    },
                )
            }
            Thread.sleep(200)
        }
        throw IOException("局域网连接已取消")
    }

    private fun discoverFallbackLanInterface(): NetworkInterface? = runCatching {
        val all = Collections.list(NetworkInterface.getNetworkInterfaces())
        all.firstOrNull { iface ->
            iface.isUp && !iface.isLoopback && !iface.isVirtual &&
                (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) &&
                wirelessHostAddress(Collections.list(iface.inetAddresses), iface.index) != null
        }
    }.getOrNull()

    private fun sameLink(properties: LinkProperties): Boolean {
        val currentIfaceName = interfaceName
        if (properties.interfaceName != null && properties.interfaceName != currentIfaceName) return false
        val bound = host ?: return true
        if (properties.linkAddresses.any { it.address.address.contentEquals(bound.address) }) {
            return true
        }
        val liveIface = currentIfaceName?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
        val liveAddresses = liveIface?.let {
            runCatching { Collections.list(it.inetAddresses) }.getOrDefault(emptyList())
        }.orEmpty()
        return liveAddresses.any { it.address.contentEquals(bound.address) }
    }

    private fun readableSsid(info: WifiInfo?): String? = info?.ssid?.removeSurrounding("\"")
        ?.takeUnless { it == WifiManager.UNKNOWN_SSID || it.isEmpty() }

    private fun invalidate(reason: String) {
        if (!closed && invalidated.compareAndSet(false, true)) {
            onDiagnostic("Existing Wi-Fi $reason; restarting wireless session")
            onNetworkChanged()
        }
    }

    override fun connectionDiagnosticSnapshot(): String =
        "existingWifiLink=${if (closed || invalidated.get()) "lost" else "attached"}"

    override fun close() {
        synchronized(lock) {
            closed = true
            selected = null
            if (callbackRegistered) {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
                callbackRegistered = false
            }
        }
    }

    private fun requestWithoutDefaultCapabilities(): NetworkRequest.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            NetworkRequest.Builder().clearCapabilities()
        } else {
            NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
        }

    companion object {
        fun detectConnectedWifiSsid(context: Context): String? = runCatching {
            val app = context.applicationContext
            val connectivity = app.getSystemService(ConnectivityManager::class.java)
            val wifi = app.getSystemService(WifiManager::class.java)
            val activeCap = connectivity?.activeNetwork?.let {
                runCatching { connectivity.getNetworkCapabilities(it) }.getOrNull()
            }
            val networkInfo = if (Build.VERSION.SDK_INT >= 31) activeCap?.transportInfo as? WifiInfo else null
            @Suppress("DEPRECATION")
            val stationInfo = runCatching { wifi?.connectionInfo }.getOrNull()
            listOfNotNull(networkInfo, stationInfo).firstNotNullOfOrNull { info ->
                info.ssid?.removeSurrounding("\"")
                    ?.takeUnless { it == WifiManager.UNKNOWN_SSID || it.isBlank() }
            }
        }.getOrNull()

        fun isConnectedWifiOpen(context: Context): Boolean = runCatching {
            val app = context.applicationContext
            val connectivity = app.getSystemService(ConnectivityManager::class.java)
            val wifi = app.getSystemService(WifiManager::class.java)
            val activeCap = connectivity?.activeNetwork?.let {
                runCatching { connectivity.getNetworkCapabilities(it) }.getOrNull()
            }
            val networkInfo = if (Build.VERSION.SDK_INT >= 31) activeCap?.transportInfo as? WifiInfo else null
            @Suppress("DEPRECATION")
            val stationInfo = runCatching { wifi?.connectionInfo }.getOrNull()
            val info = networkInfo ?: stationInfo
            if (Build.VERSION.SDK_INT >= 31 && info != null) {
                val sec = runCatching { info.currentSecurityType }.getOrDefault(-1)
                if (sec == WifiInfo.SECURITY_TYPE_OPEN) return@runCatching true
                if (sec > 0) return@runCatching false
            }
            val ssid = listOfNotNull(networkInfo, stationInfo).firstNotNullOfOrNull { candidate ->
                candidate.ssid?.removeSurrounding("\"")
                    ?.takeUnless { it == WifiManager.UNKNOWN_SSID || it.isBlank() }
            }
            val bssid = runCatching { info?.bssid }.getOrNull()
            @Suppress("MissingPermission")
            val scans = runCatching { wifi?.scanResults.orEmpty() }.getOrDefault(emptyList())
            val matching = scans.firstOrNull {
                (!bssid.isNullOrBlank() && it.BSSID.equals(bssid, ignoreCase = true)) ||
                    (!ssid.isNullOrBlank() && it.SSID == ssid)
            } ?: return@runCatching false
            val caps = matching.capabilities.uppercase()
            !caps.contains("WPA") && !caps.contains("RSN") && !caps.contains("PSK") &&
                !caps.contains("SAE") && !caps.contains("WEP") && !caps.contains("EAP")
        }.getOrDefault(false)

        fun hasActiveLanNetwork(context: Context): Boolean = runCatching {
            val app = context.applicationContext
            val connectivity = app.getSystemService(ConnectivityManager::class.java)
            val hasNetwork = connectivity?.allNetworks?.any { net ->
                val caps = runCatching { connectivity.getNetworkCapabilities(net) }.getOrNull()
                caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            } == true
            if (hasNetwork) return@runCatching true
            Collections.list(NetworkInterface.getNetworkInterfaces()).any { iface ->
                iface.isUp && !iface.isLoopback && !iface.isVirtual &&
                    (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) &&
                    wirelessHostAddress(Collections.list(iface.inetAddresses), iface.index) != null
            }
        }.getOrDefault(false)
    }
}

internal fun wirelessHostAddress(addresses: List<InetAddress>, interfaceIndex: Int): InetAddress? {
    val scopedAddresses = if (interfaceIndex > 0) {
        addresses.map { address ->
            if (address is Inet6Address && address.isLinkLocalAddress && address.scopeId == 0) {
                runCatching { Inet6Address.getByAddress(null, address.address, interfaceIndex) }
                    .getOrDefault(address)
            } else {
                address
            }
        }
    } else {
        addresses
    }
    return HotspotAddressPolicy.select(scopedAddresses)
}

/** Station LAN discovery must cover IPv4 multicast as well as scoped link-local IPv6. */
internal fun existingWifiHostAddresses(addresses: List<InetAddress>, interfaceIndex: Int): List<InetAddress> {
    val ipv4 = addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress
    }
    val ipv6 = if (interfaceIndex > 0) {
        addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }?.let {
            Inet6Address.getByAddress(null, it.address, interfaceIndex)
        }
    } else {
        null
    }
    return listOfNotNull(ipv4, ipv6)
}
