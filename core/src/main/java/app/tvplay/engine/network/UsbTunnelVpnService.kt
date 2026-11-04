package app.tvplay.engine.network

import android.content.Context
import android.content.Intent
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
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

typealias CarPlayVpnService = UsbTunnelVpnService

/**
 * In-process AirPlay TCP listener for wireless CarPlay (Wi-Fi Direct & Home LAN).
 *
 * Does not use Android `VpnService` or require `BIND_VPN_SERVICE`.
 */
class UsbTunnelVpnService {
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

    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var additionalServers: List<ServerSocket> = emptyList()
    private var attachGeneration = 0

    @Volatile var lastAttachFailure: String? = null
        private set

    @Synchronized
    fun attach(
        @Suppress("UNUSED_PARAMETER") ncm: NcmUsbBridge,
        @Suppress("UNUSED_PARAMETER") linkLocal: String,
        @Suppress("UNUSED_PARAMETER") hostMac: ByteArray,
        @Suppress("UNUSED_PARAMETER") config: TvStreamServerConfig,
        @Suppress("UNUSED_PARAMETER") identity: AccessoryIdentity,
        @Suppress("UNUSED_PARAMETER") pairings: PairingRegistry,
        @Suppress("UNUSED_PARAMETER") mfi: MfiAuthProvider?,
        @Suppress("UNUSED_PARAMETER") listener: AirPlaySessionListener,
        @Suppress("UNUSED_PARAMETER") media: AirPlayMediaHandler,
    ): AttachResult = AttachResult.Failed("TVPlay 仅支持无线 CarPlay 投屏")

    /**
     * Starts the AirPlay TCP listener on the wireless Wi-Fi Direct / LAN address in-process.
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
            Log.i(TAG, "replacing stale wireless AirPlay listener attachment")
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

    @Synchronized
    fun detach() {
        releaseLocked()
    }

    @Synchronized
    fun detachWireless(owner: AirPlayListenerIdentity) {
        if (attachment?.listenerIdentity === owner) releaseLocked()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    fun boundPort(): Int? = attachment?.config?.port

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
                synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                }
                listener.onTransportError(message)
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        attachment = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        additionalServers.forEach { runCatching { it.close() } }
        additionalServers = emptyList()
        closeSessionsLocked()
    }

    companion object {
        private const val TAG = "tvplay-airplay"

        fun prepare(@Suppress("UNUSED_PARAMETER") context: Context): Intent? = null

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
