package app.tvplay.engine.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import app.tvplay.engine.auth.AccessoryIdentity
import app.tvplay.engine.auth.MfiAuthProvider
import app.tvplay.engine.auth.PairingRegistry
import app.tvplay.engine.protocol.rtsp.AirPlayListenerIdentity
import app.tvplay.engine.protocol.rtsp.AirPlayMediaHandler
import app.tvplay.engine.protocol.rtsp.AirPlaySession
import app.tvplay.engine.protocol.rtsp.AirPlaySessionListener
import app.tvplay.engine.protocol.rtsp.AirPlayTcpAccepted
import app.tvplay.engine.protocol.rtsp.isInternalAirPlayPeer
import app.tvplay.engine.session.TvStreamServerConfig
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

typealias CarPlayVpnService = UsbTunnelVpnService

/**
 * Hosts the AirPlay TCP listener for both USB NCM/VPN and wireless Wi-Fi transports.
 *
 * The wired USB path uses Android's local app-scoped VPN TUN interface (`fe80::2/64`) to bridge
 * USB CDC-NCM IPv6 Ethernet frames via [Ipv6NcmBridge]. The wireless path binds directly to the
 * Wi-Fi Direct / LAN interface without establishing a VPN tunnel.
 */
class UsbTunnelVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: UsbTunnelVpnService get() = this@UsbTunnelVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private data class AirPlayAttachment(
        val address: InetAddress,
        val config: TvStreamServerConfig,
        val identity: AccessoryIdentity,
        val pairings: PairingRegistry,
        val mfi: MfiAuthProvider?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val additionalAddresses: List<InetAddress> = emptyList(),
        val listenerIdentity: AirPlayListenerIdentity? = null,
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var additionalServers: List<ServerSocket> = emptyList()
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0

    /** The exception chain behind the latest [AttachResult.Failed], for the diagnostic log. */
    @Volatile var lastAttachFailure: String? = null
        private set

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: TvStreamServerConfig,
        identity: AccessoryIdentity,
        pairings: PairingRegistry,
        mfi: MfiAuthProvider?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale NCM/VPN attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val tunFd = runCatching {
                if (prepare(this) != null) {
                    trySilentVpnPreConsent()
                }
                Builder()
                    .addAddress(linkLocal, LINK_PREFIX)
                    .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                    .setSession(SESSION_NAME)
                    .setMtu(TUN_MTU)
                    .setBlocking(true)
                    // An empty app list routes every UID through this VPN. Scope it before establish;
                    // rejection must reach the existing attachment cleanup, never an unscoped retry.
                    .addAllowedApplication(packageName)
                    .establish()
            }.getOrNull()
            tun = tunFd

            if (tunFd != null) {
                val ipv6Bridge = Ipv6NcmBridge(ncm, tunFd, hostMac) { error ->
                    onTransportError(generation, listener, error)
                }
                ipv6Bridge.start()
                bridge = ipv6Bridge

                startAirPlayServer(
                    generation,
                    AirPlayAttachment(scopedToTunnel(address, listener), config, identity, pairings, mfi, listener, media),
                )
            } else {
                val directBind = selectDirectUsbBindAddress(address, listener)
                startAirPlayServer(
                    generation,
                    AirPlayAttachment(directBind, config, identity, pairings, mfi, listener, media),
                )
            }
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            lastAttachFailure = attachFailure(error)
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Starts the AirPlay listener on the wireless AP / station address without establishing a VPN or
     * NCM bridge.
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: TvStreamServerConfig,
        identity: AccessoryIdentity,
        pairings: PairingRegistry,
        mfi: MfiAuthProvider?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        additionalBindAddresses: List<InetAddress> = emptyList(),
        listenerIdentity: AirPlayListenerIdentity? = null,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale local-only Wi-Fi attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            startAirPlayServer(
                generation,
                AirPlayAttachment(
                    bindAddress,
                    config,
                    identity,
                    pairings,
                    mfi,
                    listener,
                    media,
                    additionalBindAddresses,
                    listenerIdentity,
                ),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            lastAttachFailure = attachFailure(error)
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Releases the active AirPlay listener and whichever VPN/NCM transport resources are active. */
    @Synchronized
    fun detach() {
        releaseLocked()
    }

    @Synchronized
    fun detachWireless(owner: AirPlayListenerIdentity) {
        if (attachment?.listenerIdentity === owner) releaseLocked()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    /** Port the AirPlay listener actually bound, which may differ from the configured port. */
    fun boundPort(): Int? = attachment?.config?.port

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    /**
     * Linux rejects bind() to a link-local IPv6 address without a scope id (EINVAL). Android 8+
     * IoBridge.bind looks the scope up itself, but Android 7.x binds natively in
     * PlainSocketImpl.socketBind and never derives one, so the unscoped wired listener failed with
     * "Invalid argument". Scope it to the tunnel that owns the address on every version; when no
     * interface owns it, keep the unscoped address and the platform's own behavior.
     */
    private fun trySilentVpnPreConsent() {
        val pkg = packageName
        val commands = arrayOf(
            arrayOf("sh", "-c", "cmd appops set $pkg ACTIVATE_VPN allow >/dev/null 2>&1 || appops set $pkg ACTIVATE_VPN allow >/dev/null 2>&1"),
        )
        for (cmd in commands) {
            runCatching {
                val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                proc.waitFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
                proc.destroyForcibly()
            }
        }
    }

    private fun selectDirectUsbBindAddress(
        requestedAddress: Inet6Address,
        listener: AirPlaySessionListener,
    ): InetAddress {
        val candidates = tunnelCandidates()
        val usbIface = candidates.firstOrNull { candidate ->
            val lower = candidate.name.lowercase()
            lower.startsWith("usb") || lower.startsWith("ncm") || lower.startsWith("rndis") || lower.startsWith("eth")
        }
        if (usbIface != null) {
            val linkLocal = usbIface.addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }
            if (linkLocal != null) {
                runCatching {
                    listener.onDebugLog("wired AirPlay direct USB interface bind iface=${usbIface.name} addr=${linkLocal.hostAddress}")
                }
                return Inet6Address.getByAddress(null, linkLocal.address, usbIface.index)
            }
        }
        runCatching {
            listener.onDebugLog("wired AirPlay running without VPN tunnel; binding wildcard IPv6 [::] for direct USB/local transport (requested=${requestedAddress.hostAddress})")
        }
        return InetAddress.getByName("::")
    }

    private fun scopedToTunnel(address: Inet6Address, listener: AirPlaySessionListener): InetAddress {
        if (address.scopeId != 0) return address
        var interfaces = emptyList<TunnelCandidate>()
        repeat(TUN_LOOKUP_ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(TUN_LOOKUP_DELAY_MILLIS)
            interfaces = tunnelCandidates()
            selectTunnelInterface(address, interfaces)?.let { owner ->
                runCatching { listener.onDebugLog("wired AirPlay listener scope iface=${owner.name} index=${owner.index}") }
                return Inet6Address.getByAddress(null, address.address, owner.index)
            }
        }
        val tunnels = interfaces.filter { it.name.startsWith("tun") }.joinToString(",") { it.name }.ifEmpty { "none" }
        runCatching { listener.onDebugLog("wired AirPlay listener scope iface=not_found tun=$tunnels; binding unscoped") }
        return address
    }

    private fun tunnelCandidates(): List<TunnelCandidate> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty().map { networkInterface ->
            TunnelCandidate(networkInterface.name, networkInterface.index, Collections.list(networkInterface.inetAddresses))
        }
    }.getOrDefault(emptyList())

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        val servers = if (replacement.additionalAddresses.isEmpty()) {
            listOf(AirPlayPortSelector.bind(replacement.address, replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            })
        } else {
            AirPlayPortSelector.bindAll(
                listOf(replacement.address) + replacement.additionalAddresses,
                replacement.config.port,
            ) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            }
        }
        val server = servers.first()
        attachment = replacement.copy(config = replacement.config.copy(port = server.localPort))
        serverSocket = server
        additionalServers = servers.drop(1)
        servers.forEach { bound ->
            runCatching {
                replacement.listener.onDebugLog(
                    "airplay listener ready family=${if (bound.inetAddress is Inet6Address) "IPv6" else "IPv4"} port=${bound.localPort}",
                )
            }
            Thread(
                { acceptLoop(generation, bound) },
                "airplay-accept",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                val acceptedAtNanos = System.nanoTime()
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get() || generation != attachGeneration ||
                        (serverSocket !== server && additionalServers.none { it === server })) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    val internalPeer = isInternalAirPlayPeer(socket.inetAddress, socket.localAddress)
                    current.listenerIdentity?.let { owner ->
                        current.listener.onTcpAccepted(
                            AirPlayTcpAccepted(
                                owner,
                                internalPeer,
                                acceptedAtNanos,
                            ),
                        )
                    }
                    runCatching {
                        current.listener.onDebugLog(
                            "airplay TCP accepted family=${if (socket.inetAddress is Inet6Address) "IPv6" else "IPv4"}",
                        )
                    }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onSessionActive(session: AirPlaySession) {
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionActive(session)
                            }

                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                    ).also(::addSession)
                }
                session.start()
            }
        } catch (error: IOException) {
            if (active.get()) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        synchronized(sessionsLock) {
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Exception) {
                    Log.w(TAG, "AirPlay session replacement failed", error)
                }
            }
            sessions.clear()
        }
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                val releasedGeneration = synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                    attachGeneration
                }
                listener.onTransportError(message)
                synchronized(this) {
                    if (attachGeneration == releasedGeneration && !active.get()) {
                        runCatching { stopSelf() }
                    }
                }
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        attachment = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        additionalServers.forEach { runCatching { it.close() } }
        additionalServers = emptyList()
        closeSessionsLocked()
        runCatching { bridge?.close() }
        bridge = null
        runCatching { tun?.close() }
        tun = null
    }

    companion object {
        private const val TAG = "tvplay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "TVPlay USB NCM"
        private const val TUN_MTU = 1500
        private const val TUN_LOOKUP_ATTEMPTS = 10
        private const val TUN_LOOKUP_DELAY_MILLIS = 50L

        internal data class TunnelCandidate(val name: String, val index: Int, val addresses: List<InetAddress>)

        /**
         * Picks the interface that owns [address]. A tun interface wins when another interface
         * happens to carry the same link-local address.
         */
        internal fun selectTunnelInterface(address: Inet6Address, interfaces: List<TunnelCandidate>): TunnelCandidate? {
            val owners = interfaces.filter { candidate ->
                candidate.index > 0 && candidate.addresses.any { owned ->
                    owned is Inet6Address && owned.address.contentEquals(address.address)
                }
            }
            return owners.firstOrNull { it.name.startsWith("tun") } ?: owners.firstOrNull()
        }

        /** Returns the VPN consent intent, or null when consent is already granted (or unavailable). */
        fun prepare(context: Context): Intent? = try {
            VpnService.prepare(context)
        } catch (error: Exception) {
            Log.w(TAG, "VpnService.prepare failed on this ROM", error)
            null
        }

        internal fun attachFailure(error: Throwable): String {
            val chain = generateSequence(error) { it.cause }.take(4).joinToString(" <- ") { failure ->
                "${failure.javaClass.simpleName}: ${failure.message ?: "no message"}"
            }
            val origin = error.stackTrace.firstOrNull()
                ?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}" }.orEmpty()
            return chain + origin
        }
    }
}
