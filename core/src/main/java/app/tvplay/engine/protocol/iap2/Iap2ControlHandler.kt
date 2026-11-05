package app.tvplay.engine.protocol.iap2

import app.tvplay.engine.auth.MfiAuthException
import app.tvplay.engine.auth.MfiAuthProvider
import app.tvplay.engine.auth.MfiCertificateType
import app.tvplay.engine.session.TvAccessoryIdentitySpec
import app.tvplay.engine.session.TvNowPlayingMetadata
import app.tvplay.engine.session.TvWirelessIdentitySpec
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.math.min

/** Runs the minimal iAP2 CSM MFi exchange without taking ownership of [Iap2Session]. */
class Iap2MfiAuthenticationClient(
    private val authentication: MfiAuthProvider,
    private val maximumCertificateLength: Int = MfiAuthProvider.DEFAULT_MAX_CERTIFICATE_BYTES,
) {
    init {
        require(maximumCertificateLength in 1..MAX_CSM_CERTIFICATE_BYTES) {
            "maximumCertificateLength must be in 1..$MAX_CSM_CERTIFICATE_BYTES"
        }
    }

    /** Blocks until the phone confirms AA05, or throws a typed authentication failure. */
    fun run(
        session: Iap2Session,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onProgress: (String) -> Unit = {},
    ) {
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS"
        }
        val deadlineNanos = deadlineAfter(timeoutMillis)
        val certificate = authentication.readCertificate(maximumCertificateLength)
        val certificateFrame = when (authentication.certificateType) {
            MfiCertificateType.MFI -> Iap2AuthenticationMessages.accessoryCertificate(certificate)
            MfiCertificateType.BAA -> Iap2AuthenticationMessages.accessoryCertificateBody(certificate)
        }
        val certificatePayloadBytes = certificateFrame.payload.size
        onProgress(
            "mfi type=${authentication.certificateType} " +
                "certificate loaded bytes=${certificate.size} " +
                "payload bytes=$certificatePayloadBytes",
        )
        while (true) {
            val remaining = remainingMillis(deadlineNanos)
            if (remaining == 0L) throw Iap2MfiAuthenticationException("Timed out waiting for iAP2 MFi authentication")
            val frame = session.recv(remaining)
                ?: throw Iap2MfiAuthenticationException("Timed out waiting for iAP2 MFi authentication")
            when (frame.messageId) {
                REQUEST_CERTIFICATE -> {
                    onProgress("iap2 mfi rx=0xaa00 request-certificate")
                    send(session, certificateFrame, deadlineNanos)
                    onProgress("iap2 mfi tx=0xaa01 certificate bytes=$certificatePayloadBytes")
                }
                REQUEST_CHALLENGE -> {
                    val challenge = requireParameterZero(frame)
                    if (challenge.size !in 1..MAXIMUM_CHALLENGE_BYTES) {
                        throw Iap2MfiAuthenticationException("Invalid iAP2 MFi challenge length ${challenge.size}")
                    }
                    onProgress("iap2 mfi rx=0xaa02 challenge bytes=${challenge.size}")
                    val signature = authentication.signChallenge(challenge)
                    send(session, Iap2AuthenticationMessages.response(signature), deadlineNanos)
                    onProgress("iap2 mfi tx=0xaa03 signature bytes=${signature.size}")
                }
                AUTHENTICATION_SUCCEEDED -> {
                    onProgress("iap2 mfi rx=0xaa05 authentication-succeeded")
                    return
                }
                AUTHENTICATION_FAILED -> throw Iap2MfiAuthenticationException("iPhone sent AuthenticationFailed")
                else -> throw Iap2MfiAuthenticationException(
                    "Unexpected iAP2 MFi message 0x${frame.messageId.toString(16).padStart(4, '0')}",
                )
            }
        }
    }

    private fun send(
        session: Iap2Session,
        frame: Iap2Frame,
        deadlineNanos: Long,
    ) {
        val remaining = remainingMillis(deadlineNanos)
        if (remaining == 0L) throw Iap2MfiAuthenticationException("Timed out sending iAP2 MFi authentication reply")
        session.send(frame, remaining)
    }

    private fun requireParameterZero(frame: Iap2Frame): ByteArray {
        return try {
            Iap2AuthenticationMessages.challenge(frame)
        } catch (failure: Exception) {
            throw Iap2MfiAuthenticationException("iAP2 MFi challenge is missing parameter 0", failure)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) return 0
        return ((remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
            .coerceAtMost(MAX_TIMEOUT_MILLIS)
    }

    companion object {
        private const val REQUEST_CERTIFICATE = 0xaa00
        private const val CERTIFICATE = 0xaa01
        private const val REQUEST_CHALLENGE = 0xaa02
        private const val RESPONSE = 0xaa03
        private const val AUTHENTICATION_FAILED = 0xaa04
        private const val AUTHENTICATION_SUCCEEDED = 0xaa05
        private const val MAXIMUM_CHALLENGE_BYTES = 128
        private const val MAX_CSM_CERTIFICATE_BYTES = 65_525
        private const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        private const val MAX_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/** A malformed, rejected, unexpected, or timed-out iAP2 MFi authentication exchange. */
class Iap2MfiAuthenticationException(message: String, cause: Throwable? = null) : MfiAuthException(message, cause)

typealias Iap2WirelessIdentification = TvWirelessIdentitySpec
typealias Iap2IdentificationConfig = TvAccessoryIdentitySpec

/** The two iAP2 links used during one wireless CarPlay connection have different responsibilities. */
enum class Iap2WirelessLinkRole {
    /** Short-lived RFCOMM link used only to authenticate and hand the iPhone the Wi-Fi endpoint. */
    BLUETOOTH_BOOTSTRAP,

    /** Long-lived iAP2 DataStream inside the active Wi-Fi AirPlay session. */
    RUNTIME_TUNNEL,
}

fun TvAccessoryIdentitySpec.forWirelessLink(
    @Suppress("UNUSED_PARAMETER") role: Iap2WirelessLinkRole,
    wirelessIdentification: TvWirelessIdentitySpec,
): TvAccessoryIdentitySpec = copy(wireless = wirelessIdentification)

/** Identification failures distinguished from the underlying iAP2 transport failure. */
sealed class Iap2IdentificationException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class TimedOut(message: String, cause: Throwable? = null) : Iap2IdentificationException(message, cause)

    class Rejected(
        parameterIds: Set<Int>,
        val unsupportedMessagesSent: Iap2RejectedMessageList? = null,
        val unsupportedMessagesReceived: Iap2RejectedMessageList? = null,
    ) : Iap2IdentificationException(
        "iAP2 identification rejected before MFi authentication; parameters ${parameterIds.sorted().hexadecimalIds()}; " +
            "unsupported messages sent=${unsupportedMessagesSent?.diagnostic() ?: "absent"} " +
            "received=${unsupportedMessagesReceived?.diagnostic() ?: "absent"}",
    ) {
        val parameterIds: Set<Int> = parameterIds.toSet()
    }

    class UnexpectedMessage(messageId: Int) : Iap2IdentificationException(
        "Unexpected iAP2 identification message 0x${messageId.toString(16).padStart(4, '0')}",
    )
}

/** Bounded diagnostic values from IdentificationRejected parameter 6 or 7, not negotiated features. */
data class Iap2RejectedMessageList internal constructor(
    val messageIds: List<Int>,
    val payloadCount: Int,
    val emptyPayloadCount: Int,
    val malformedPayloadCount: Int,
    val omittedMessageCount: Int,
) {
    internal fun diagnostic(): String =
        "${messageIds.hexadecimalIds()} " +
            "payloads=$payloadCount empty=$emptyPayloadCount malformed=$malformedPayloadCount " +
            "duplicateParameters=${payloadCount > 1} " +
            "duplicateRetainedIds=${messageIds.size != messageIds.toSet().size} " +
            "omitted=$omittedMessageCount"
}

private fun Iterable<Int>.hexadecimalIds(): String =
    joinToString(prefix = "[", postfix = "]") { "0x${it.toString(16).padStart(4, '0')}" }

/**
 * Synchronous accessory-side wired or wireless identification over an already owned CSM channel.
 *
 * This is intentionally only identification: it neither invokes MFi nor itself starts any
 * CarPlay, subscription, power, media, or UI service.
 */
class Iap2IdentificationClient(private val session: Iap2Session) {
    /**
     * Waits for link negotiation, then completes the 1D00/1D01/1D02 exchange. When the iPhone
     * rejects only messages it does not know (older iOS rejects the CarPlay 0x4300/0x4301 pair),
     * identification is sent once more without them, as iAP2 allows after a rejection.
     */
    @Throws(Iap2IdentificationException::class)
    fun identify(
        config: TvAccessoryIdentitySpec,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onProgress: (String) -> Unit = {},
    ) = exchange(session::awaitReady, session::recv, session::send, config, timeoutMillis, onProgress)

    companion object {
        /** The identification exchange over any message link; [identify] runs it on the session. */
        internal fun exchange(
            awaitReady: (Long) -> Boolean,
            recv: (Long) -> Iap2Frame?,
            send: (Iap2Frame, Long) -> Unit,
            config: TvAccessoryIdentitySpec,
            timeoutMillis: Long,
            onProgress: (String) -> Unit,
        ) {
            require(timeoutMillis in 1..MAXIMUM_TIMEOUT_MILLIS) {
                "timeoutMillis must be in 1..$MAXIMUM_TIMEOUT_MILLIS"
            }
            val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            if (!awaitReady(remainingMillis(deadlineNanos))) {
                throw Iap2IdentificationException.TimedOut("Timed out waiting for iAP2 control session readiness")
            }

            var omitted: Set<Int>? = null
            while (true) {
                val frame = recv(remainingMillis(deadlineNanos))
                    ?: throw Iap2IdentificationException.TimedOut("Timed out waiting for iAP2 identification")
                when (frame.messageId) {
                    START_IDENTIFICATION -> send(identificationInformation(config, omitted.orEmpty()), remainingMillis(deadlineNanos))
                    IDENTIFICATION_ACCEPTED -> return
                    IDENTIFICATION_REJECTED -> {
                        val rejection = identificationRejection(frame)
                        if (omitted != null) throw rejection
                        omitted = rejection.omittableMessages() ?: throw rejection
                        onProgress("iap2 identification retry without unsupported messages ${omitted.sorted().hexadecimalIds()}")
                        send(identificationInformation(config, omitted), remainingMillis(deadlineNanos))
                    }
                    else -> throw Iap2IdentificationException.UnexpectedMessage(frame.messageId)
                }
            }
        }

        const val START_IDENTIFICATION = 0x1d00
        const val IDENTIFICATION_INFORMATION = 0x1d01
        const val IDENTIFICATION_ACCEPTED = 0x1d02
        const val IDENTIFICATION_REJECTED = 0x1d03

        private const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        private const val MAXIMUM_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_REJECTED_MESSAGE_IDS = 32

        internal fun identificationRejection(frame: Iap2Frame): Iap2IdentificationException.Rejected {
            val parameters = Iap2BodyReader.of(frame).list()
            return Iap2IdentificationException.Rejected(
                parameterIds = parameters.mapTo(LinkedHashSet()) { it.id },
                unsupportedMessagesSent = rejectedMessageList(parameters, 6),
                unsupportedMessagesReceived = rejectedMessageList(parameters, 7),
            )
        }

        private fun rejectedMessageList(parameters: List<Iap2Parameter>, id: Int): Iap2RejectedMessageList? {
            val payloads = parameters.filter { it.id == id }
            if (payloads.isEmpty()) return null
            val messageIds = ArrayList<Int>(MAX_REJECTED_MESSAGE_IDS)
            var emptyPayloadCount = 0
            var malformedPayloadCount = 0
            var omittedMessageCount = 0
            for (parameter in payloads) {
                val payload = parameter.payload
                when {
                    payload.isEmpty() -> emptyPayloadCount++
                    payload.size % 2 != 0 -> malformedPayloadCount++
                    else -> {
                        // These two rejection parameters contain BE16 message IDs, unlike the
                        // empty presence markers used by other rejected identification fields.
                        // Keep repetitions visible and do not guess IDs from an odd-length payload.
                        for (offset in payload.indices step 2) {
                            if (messageIds.size < MAX_REJECTED_MESSAGE_IDS) {
                                messageIds += ((payload[offset].toInt() and 0xff) shl 8) or
                                    (payload[offset + 1].toInt() and 0xff)
                            } else {
                                omittedMessageCount++
                            }
                        }
                    }
                }
            }
            return Iap2RejectedMessageList(
                messageIds = messageIds.toList(),
                payloadCount = payloads.size,
                emptyPayloadCount = emptyPayloadCount,
                malformedPayloadCount = malformedPayloadCount,
                omittedMessageCount = omittedMessageCount,
            )
        }

        /**
         * The message IDs a rejection lists as unsupported, when dropping them is all it asks for:
         * only the two message-list parameters are rejected, their IDs are intact, and none is an
         * authentication message. Null when identification cannot be corrected this way.
         */
        internal fun Iap2IdentificationException.Rejected.omittableMessages(): Set<Int>? {
            if (parameterIds.isEmpty() || !MESSAGE_LIST_PARAMETERS.containsAll(parameterIds)) return null
            val lists = listOfNotNull(unsupportedMessagesSent, unsupportedMessagesReceived)
            if (lists.any { it.malformedPayloadCount > 0 || it.omittedMessageCount > 0 }) return null
            val ids = lists.flatMap { it.messageIds }.toSet()
            if (ids.isEmpty() || ids.any { it in AUTHENTICATION_MESSAGES }) return null
            return ids
        }

        /** Builds the wired or wireless IdentificationInformation frame. */
        fun identificationInformation(config: TvAccessoryIdentitySpec, omitted: Set<Int> = emptySet()): Iap2Frame {
            val wireless = config.wireless
            val sentMessages = MESSAGES_SENT_BY_ACCESSORY
            val receivedMessages = MESSAGES_RECEIVED_FROM_PHONE
            return Iap2Messages.build(Iap2Endpoints.IDENTIFICATION_INFORMATION) {
                string(0, config.name)
                string(1, config.modelIdentifier)
                string(2, config.manufacturer)
                string(3, config.serialNumber)
                string(4, config.firmwareVersion)
                string(5, config.hardwareVersion)
                u16List(
                    6,
                    if (wireless == null) {
                        sentMessages.asIterable()
                    } else {
                        sentMessages.filterNot { it == POWER_SOURCE_UPDATE }.toIntArray().asIterable() +
                            ACCESSORY_WIFI_CONFIGURATION_INFORMATION
                    }.filterNot { it in omitted },
                )
                u16List(
                    7,
                    if (wireless == null) {
                        receivedMessages.asIterable()
                    } else {
                        receivedMessages.asIterable() + WIRELESS_PHONE_MESSAGES.asIterable()
                    }.filterNot { it in omitted },
                )
                u8(8, if (wireless == null) 2 else 0)
                u16(9, 20)
                group(10) {
                    u8(0, 1)
                    string(1, config.externalAccessoryProtocol)
                    u8(2, 0)
                }
                string(12, config.language)
                strings(13, listOf(config.language))
                if (wireless == null) {
                    group(16) {
                        u16(0, 0)
                        string(1, "USBHostTransport")
                        void(2)
                        u8(3, config.carPlayUsbInterfaceNumber)
                        void(4)
                    }
                } else {
                    group(17) {
                        u16(0, 0)
                        string(1, "blue")
                        void(2)
                        bytes(3, wireless.bluetoothMacBytes())
                        string(4, "blue")
                        void(5)
                    }
                    group(24) {
                        u16(0, 1)
                        string(1, wireless.ssid)
                        void(2)
                        u16(3, 1)
                        void(4)
                        void(5)
                    }
                }
                group(30) {
                    u16(0, 42)
                    string(1, "RouteGuidance")
                    u16(2, 64) // MaxCurrentRoadNameLength
                    u16(4, 64) // MaxAfterManeuverRoadNameLength
                    u16(6, 8)
                    u16(7, 64) // MaxLaneGuidanceDescriptionLength
                    u16(8, 8) // MaxLaneGuidanceStorageCapacity
                }
            }
        }

        private fun remainingMillis(deadlineNanos: Long): Long {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) throw Iap2IdentificationException.TimedOut("iAP2 identification timed out")
            return min(MAXIMUM_TIMEOUT_MILLIS, (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
        }

        /* Include the authentication messages implemented by Iap2MfiAuthenticationClient. */
        private val MESSAGES_SENT_BY_ACCESSORY = intArrayOf(
            0xaa01, // AuthenticationCertificate
            0xaa03, // AuthenticationResponse
            0x5000, // StartNowPlayingUpdates
            0x5002, // StopNowPlayingUpdates
            0x5200, // StartRouteGuidanceUpdates
            0x5203, // StopRouteGuidanceUpdates
            0xae00, // StartPowerUpdates
            0xae02, // StopPowerUpdates
            0x4157, // StartCommunicationsUpdates
            0x4159, // StopCommunicationsUpdates
            0x4154, // StartCallStateUpdates
            0x4156, // StopCallStateUpdates
            0xae03, // PowerSourceUpdate
            0x4301, // CarPlayStartSession
        )
        private val MESSAGES_RECEIVED_FROM_PHONE = intArrayOf(
            0xaa00, // RequestAuthenticationCertificate
            0xaa02, // RequestAuthenticationChallengeResponse
            0xaa04, // AuthenticationFailed
            0xaa05, // AuthenticationSucceeded
            0xea00, // StartExternalAccessoryProtocolSession
            0xea01, // StopExternalAccessoryProtocolSession
            0x5001, // NowPlayingUpdate
            0x5201, // RouteGuidanceUpdate
            0x5202, // RouteGuidanceManeuverUpdate
            0x5204, // LaneGuidanceInfoUpdate
            0xae01, // PowerUpdate
            0x4158, // CommunicationsUpdate
            0x4155, // CallStateUpdate
            0x4300, // CarPlayAvailability
        )
        private val MESSAGE_LIST_PARAMETERS = setOf(6, 7)
        private val AUTHENTICATION_MESSAGES = setOf(0xaa00, 0xaa01, 0xaa02, 0xaa03, 0xaa04, 0xaa05)
        private const val POWER_SOURCE_UPDATE = 0xae03
        private const val ACCESSORY_WIFI_CONFIGURATION_INFORMATION = 0x5703
        private val WIRELESS_PHONE_MESSAGES = intArrayOf(0x4e0d, 0x4e0e, 0x5702)
    }
}

/**
 * Retains incremental NowPlayingUpdate (0x5001) fields into [TvNowPlayingMetadata].
 *
 * Updates carry only what changed, so an omitted field preserves its previous value while a
 * present empty string clears it.
 */
class Iap2NowPlayingTracker {
    @Volatile var nowPlaying = TvNowPlayingMetadata()
        private set

    val playing: Boolean get() = nowPlaying.isPlaying == true

    /** The complete new retained state when this frame changed it, otherwise null. */
    fun acceptUpdate(frame: Iap2Frame): TvNowPlayingMetadata? {
        if (frame.messageId != NOW_PLAYING_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        val media = runCatching { body.optionalGroup(MEDIA_ITEM) }.getOrNull()
        val playback = runCatching { body.optionalGroup(PLAYBACK) }.getOrNull()
        val previous = nowPlaying
        val next = previous.copy(
            title = media.updatedString(TITLE, previous.title),
            artist = media.updatedString(ARTIST, previous.artist),
            album = media.updatedString(ALBUM, previous.album),
            appName = playback.updatedString(SOURCE_APP, previous.appName),
            appBundleId = playback.updatedString(APP_BUNDLE_ID, previous.appBundleId),
            playbackRate = playback.updatedPlaybackRate(previous.playbackRate),
            artworkFileTransferId = media.updatedU8(ARTWORK_TRANSFER_ID, previous.artworkFileTransferId),
        )
        if (next == previous) return null
        nowPlaying = next
        return next
    }

    /** The new playing state when [frame] changed it, otherwise null. */
    fun accept(frame: Iap2Frame): Boolean? {
        val previous = playing
        val next = acceptUpdate(frame)?.let { it.isPlaying == true } ?: return null
        return next.takeIf { it != previous }
    }

    /** The session ended: nothing plays any more. */
    fun clear(): Boolean? {
        if (!playing) return null
        nowPlaying = nowPlaying.copy(playbackRate = 0.0)
        return false
    }

    /** Clears all retained metadata at the end of a CarPlay session. */
    fun clearAll(): TvNowPlayingMetadata? {
        if (nowPlaying == TvNowPlayingMetadata()) return null
        nowPlaying = TvNowPlayingMetadata()
        return nowPlaying
    }

    private fun Iap2BodyReader?.updatedString(id: Int, previous: String?): String? {
        if (this == null || !has(id)) return previous
        return runCatching { string(id).trim().ifEmpty { null } }.getOrElse { previous }
    }

    private fun Iap2BodyReader?.updatedU8(id: Int, previous: Int?): Int? {
        if (this == null || !has(id)) return previous
        return runCatching { u8(id) }.getOrElse { previous }
    }

    private fun Iap2BodyReader?.updatedPlaybackRate(previous: Double?): Double? {
        if (this == null || !has(STATUS)) return previous
        return runCatching { if (u8(STATUS) == STATUS_PLAYING) 1.0 else 0.0 }.getOrElse { previous }
    }

    companion object {
        const val NOW_PLAYING_UPDATE = 0x5001
        private const val MEDIA_ITEM = 0
        private const val PLAYBACK = 1

        private const val TITLE = 1
        private const val ALBUM = 6
        private const val ARTIST = 12
        private const val ARTWORK_TRANSFER_ID = 26

        private const val STATUS = 0
        private const val SOURCE_APP = 7
        private const val APP_BUNDLE_ID = 16
        private const val STATUS_PLAYING = 1
    }
}

/**
 * The wired control sequence after a CSM channel is ready:
 * Identification, MFi, power announcement, five update subscriptions, then CarPlay availability.
 *
 * The caller retains ownership of [session]. While [run] is active it is the only receiver and
 * forwards each non-availability CSM frame to [onIncoming]; it neither opens NCM nor implements
 * an AirPlay receiver.
 */
class Iap2WiredControlClient(
    private val session: Iap2Session,
    private val mfi: Iap2MfiAuthenticationClient,
) {
    constructor(
        session: Iap2Session,
        authProvider: MfiAuthProvider,
    ) : this(session, Iap2MfiAuthenticationClient(authProvider))

    fun run(
        identification: TvAccessoryIdentitySpec,
        endpoint: Iap2WiredCarPlayEndpoint,
        availableCurrentMilliAmps: Int,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onIncoming: (Iap2Frame) -> Unit = {},
        onNowPlaying: (TvNowPlayingMetadata) -> Unit = {},
        onProgress: (String) -> Unit = {},
        startSessionAgain: () -> StartAgain = { StartAgain.DONE },
    ): Iap2WiredControlResult {
        require(availableCurrentMilliAmps in 0..0xffff) {
            "availableCurrentMilliAmps must be in 0..65535"
        }
        require(timeoutMillis == NO_TIMEOUT_MILLIS || timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS or NO_TIMEOUT_MILLIS"
        }

        val deadlineNanos = Iap2ControlDeadline(timeoutMillis)
        Iap2IdentificationClient(session).identify(identification, requireRemaining(deadlineNanos), onProgress)
        onProgress("iap2 identification accepted")
        var stage = Iap2WiredControlStage.IDENTIFIED
        mfi.run(session, requireRemaining(deadlineNanos), onProgress)
        stage = Iap2WiredControlStage.AUTHENTICATED
        onProgress("iap2 authentication accepted")
        deadlineNanos.authenticated()

        send(powerSourceUpdate(availableCurrentMilliAmps), deadlineNanos)
        for (subscription in subscriptions()) send(subscription, deadlineNanos)
        stage = Iap2WiredControlStage.SUBSCRIBED
        onProgress("iap2 power/subscriptions sent availableCurrentMa=$availableCurrentMilliAmps")

        var forwardedFrames = 0
        val rxLog = Iap2RxLog()
        val nowPlayingTracker = Iap2NowPlayingTracker()
        var carPlayStartSessions = 0
        var startAgainDecided = false
        while (true) {
            val remaining = remainingMillis(deadlineNanos)
            if (remaining == 0L) {
                return Iap2WiredControlResult(Iap2WiredControlTerminal.TIMED_OUT, stage, forwardedFrames, carPlayStartSessions)
            }
            var startAgainPending = false
            if (!startAgainDecided && stage == Iap2WiredControlStage.CARPLAY_START_SENT) {
                when (startSessionAgain()) {
                    StartAgain.WAIT -> startAgainPending = true
                    StartAgain.DONE -> startAgainDecided = true
                    StartAgain.SEND -> {
                        startAgainDecided = true
                        send(carPlayStartSession(endpoint), deadlineNanos)
                        carPlayStartSessions++
                        onProgress(START_SESSION_AGAIN_PROGRESS)
                    }
                }
            }
            val pollTimeout = if (startAgainPending) minOf(remaining, START_AGAIN_POLL_MILLIS) else remaining
            val incoming = session.recv(pollTimeout)
            if (incoming == null) {
                if (session.isClosed) {
                    return Iap2WiredControlResult(
                        Iap2WiredControlTerminal.CHANNEL_CLOSED,
                        stage,
                        forwardedFrames,
                        carPlayStartSessions,
                    )
                }
                if (remainingMillis(deadlineNanos) == 0L) {
                    return Iap2WiredControlResult(
                        Iap2WiredControlTerminal.TIMED_OUT,
                        stage,
                        forwardedFrames,
                        carPlayStartSessions,
                    )
                }
                continue
            }

            when (incoming.messageId) {
                CARPLAY_AVAILABILITY -> {
                    onProgress("iap2 rx=0x4300 carplay-availability")
                    onProgress(carPlayAvailabilitySummary(incoming.payload))
                    // Send the wired answer on every availability notification; do not gate it on
                    // the phone's advertised availability boolean.
                    send(carPlayStartSession(endpoint), deadlineNanos)
                    stage = Iap2WiredControlStage.CARPLAY_START_SENT
                    carPlayStartSessions++
                    onProgress("iap2 tx=0x4301 carplay-start-session")
                }

                else -> {
                    rxLog.line(incoming.messageId)?.let(onProgress)
                    nowPlayingTracker.acceptUpdate(incoming)?.let(onNowPlaying)
                    onIncoming(incoming)
                    forwardedFrames++
                }
            }
        }
    }

    private fun send(frame: Iap2Frame, deadlineNanos: Iap2ControlDeadline) {
        session.send(frame, requireRemaining(deadlineNanos))
    }

    /** Asked while a session is awaited after `carplay-start-session`; see WiredStartAgain. */
    enum class StartAgain { WAIT, SEND, DONE }

    companion object {
        const val NO_TIMEOUT_MILLIS = Long.MAX_VALUE
        const val START_SESSION_AGAIN_PROGRESS =
            "iap2 tx=0x4301 carplay-start-session again: no AirPlay connection since the USB network opened"
        private const val START_AGAIN_POLL_MILLIS = 500L
        private const val CARPLAY_AVAILABILITY = 0x4300
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MAX_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L

        /** Wired PowerSourceUpdate encoding: current and the charge-if-powered flag. */
        fun powerSourceUpdate(availableCurrentMilliAmps: Int): Iap2Frame =
            Iap2ControlMessages.powerSourceUpdate(availableCurrentMilliAmps, charging = true)

        /** Exact five subscription requests emitted during bring-up. */
        fun subscriptions(): List<Iap2Frame> = Iap2ControlMessages.subscriptions()

        /** Builds the wired-only CarPlayStartSession message; no NCM or AirPlay socket is opened. */
        fun carPlayStartSession(endpoint: Iap2WiredCarPlayEndpoint): Iap2Frame =
            Iap2CarPlayMessages.startSession(
                wiredIpv6Addresses = endpoint.ipv6Addresses,
                airPlayPort = endpoint.airPlayPort,
                deviceIdentifier = endpoint.deviceIdentifier,
                publicKey = endpoint.publicKey,
                sourceVersion = endpoint.sourceVersion,
            )

        fun carPlayAvailabilitySummary(payload: ByteArray): String {
            return try {
                val availability = Iap2CarPlayMessages.availability(payload).wired
                val available = availability?.available
                val transport = availability?.identifier
                "iap2 4300 wiredAvailable=$available usbTransport=${transport ?: "none"}"
            } catch (error: RuntimeException) {
                "iap2 4300 decode failed: ${error.message}"
            }
        }

        private fun requireRemaining(deadlineNanos: Iap2ControlDeadline): Long = remainingMillis(deadlineNanos).also {
            if (it == 0L) throw Iap2IdentificationException.TimedOut("Timed out during wired iAP2 control bring-up")
        }

        private fun remainingMillis(deadlineNanos: Iap2ControlDeadline): Long = deadlineNanos.remainingMillis()
    }
}

/** A configured wired AirPlay endpoint; configuration does not claim that either service is live. */
class Iap2WiredCarPlayEndpoint(
    ipv6Addresses: List<String>,
    val airPlayPort: Int,
    val publicKey: String,
    val sourceVersion: String,
    val deviceIdentifier: String? = null,
) {
    /** A stable copy so a caller cannot mutate a validated endpoint before it is encoded. */
    val ipv6Addresses: List<String> = ipv6Addresses.toList()

    init {
        require(ipv6Addresses.isNotEmpty()) { "At least one wired IPv6 address is required" }
        require(ipv6Addresses.all(::isIpv6Literal)) { "Every wired address must be an IPv6 text literal" }
        require(airPlayPort in 1..65535) { "airPlayPort must be in 1..65535" }
        require(publicKey.isNotEmpty()) { "publicKey is required and must not be empty" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion is required and must not be empty" }
        require('\u0000' !in publicKey) { "publicKey must not contain U+0000" }
        require('\u0000' !in sourceVersion) { "sourceVersion must not contain U+0000" }
        deviceIdentifier?.let {
            require(it.isNotEmpty()) { "deviceIdentifier is optional, but must not be empty when provided" }
            require('\u0000' !in it) { "deviceIdentifier must not contain U+0000" }
        }
    }

    private companion object {
        fun isIpv6Literal(value: String): Boolean {
            if (value.contains('%') || '\u0000' in value || !value.contains(':')) return false
            return try {
                InetAddress.getByName(value) is Inet6Address
            } catch (_: Exception) {
                false
            }
        }
    }
}

enum class Iap2WiredControlStage {
    IDENTIFIED,
    AUTHENTICATED,
    SUBSCRIBED,
    CARPLAY_START_SENT,
}

enum class Iap2WiredControlTerminal { CHANNEL_CLOSED, TIMED_OUT }

/** End state of the control loop only; it is not evidence of NCM or AirPlay availability. */
data class Iap2WiredControlResult(
    val terminal: Iap2WiredControlTerminal,
    val stage: Iap2WiredControlStage,
    val forwardedFrames: Int,
    val carPlayStartSessionsSent: Int,
)

/**
 * The wireless control sequence after a CSM channel is ready:
 * Identification, MFi, the five update subscriptions, then Wi-Fi credentials and the
 * 0x4E0D/0x4E0E Wireless CarPlay transport notifications.
 *
 * The caller retains ownership of [session]. While [run] is active it is the only receiver and
 * forwards each non-control CSM frame to [onIncoming].
 */
class Iap2WirelessControlClient(
    private val session: Iap2Session,
    private val mfi: Iap2MfiAuthenticationClient,
) {
    constructor(
        session: Iap2Session,
        authProvider: MfiAuthProvider,
    ) : this(session, Iap2MfiAuthenticationClient(authProvider))

    fun run(
        identification: TvAccessoryIdentitySpec,
        endpoint: Iap2WirelessCarPlayEndpoint,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onReady: () -> Unit = {},
        beforeStartSession: () -> Unit = {},
        onStartSessionSent: (Iap2StartSessionSent) -> Unit = {},
        onIncoming: (Iap2Frame) -> Unit = {},
        onNowPlaying: (TvNowPlayingMetadata) -> Unit = {},
        // Evaluated only in the authenticated control loop when its original deadline expires.
        // The caller must prove that the current CarPlay session is usable before keeping it alive.
        keepAliveAfterDeadline: () -> Boolean = { false },
        suppressSessionBootstrap: () -> Boolean = { false },
        onProgress: (String) -> Unit = {},
    ): Iap2WirelessControlResult {
        require(identification.wireless != null) {
            "Wireless control requires a TvAccessoryIdentitySpec with wireless transport"
        }
        require(timeoutMillis == NO_TIMEOUT_MILLIS || timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS or NO_TIMEOUT_MILLIS"
        }

        val deadline = WirelessControlDeadline(timeoutMillis)
        Iap2IdentificationClient(session).identify(identification, deadline.requireRemaining(), onProgress)
        onProgress("iap2 identification accepted")
        var stage = Iap2WirelessControlStage.IDENTIFIED

        mfi.run(session, deadline.requireRemaining(), onProgress)
        stage = Iap2WirelessControlStage.AUTHENTICATED
        onProgress("iap2 authentication accepted")

        for (subscription in Iap2WiredControlClient.subscriptions()) {
            session.send(subscription, deadline.requireRemaining())
        }
        stage = Iap2WirelessControlStage.SUBSCRIBED
        onProgress("iap2 subscriptions sent")
        onReady()

        fun sendControl(frame: Iap2Frame) {
            session.send(frame, deadline.requireRemaining(keepAliveAfterDeadline))
        }

        var forwardedFrames = 0
        val rxLog = Iap2RxLog()
        val nowPlayingTracker = Iap2NowPlayingTracker()
        var wifiConfigurationsSent = 0
        var carPlayStartSessionsSent = 0
        var preTransportWiFiConfigurationsSent = 0
        var postTransportWiFiConfigurationsSent = 0
        var transportNotificationSeen = false
        var wirelessCarPlayAvailableSeen = false
        while (true) {
            val remaining = deadline.remainingMillis(keepAliveAfterDeadline)
            if (remaining == 0L) {
                return Iap2WirelessControlResult(
                    Iap2WirelessControlTerminal.TIMED_OUT,
                    stage,
                    forwardedFrames,
                    wifiConfigurationsSent,
                    carPlayStartSessionsSent,
                    transportNotificationSeen,
                    postTransportWiFiConfigurationsSent,
                    wirelessCarPlayAvailableSeen,
                )
            }
            val incoming = session.recv(remaining)
            if (incoming == null) {
                if (session.isClosed) {
                    return Iap2WirelessControlResult(
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED,
                        stage,
                        forwardedFrames,
                        wifiConfigurationsSent,
                        carPlayStartSessionsSent,
                        transportNotificationSeen,
                        postTransportWiFiConfigurationsSent,
                        wirelessCarPlayAvailableSeen,
                    )
                }
                if (deadline.remainingMillis(keepAliveAfterDeadline) == 0L) {
                    return Iap2WirelessControlResult(
                        Iap2WirelessControlTerminal.TIMED_OUT,
                        stage,
                        forwardedFrames,
                        wifiConfigurationsSent,
                        carPlayStartSessionsSent,
                        transportNotificationSeen,
                        postTransportWiFiConfigurationsSent,
                        wirelessCarPlayAvailableSeen,
                    )
                }
                continue
            }

            when (incoming.messageId) {
                REQUEST_ACCESSORY_WIFI_CONFIGURATION -> {
                    onProgress("iap2 rx=0x5702 request-wifi-configuration")
                    if (suppressSessionBootstrap()) {
                        onProgress("iap2 0x5703 suppressed: wireless session already active")
                        continue
                    }
                    val postTransport = transportNotificationSeen
                    val sentCount = if (postTransport) {
                        postTransportWiFiConfigurationsSent
                    } else {
                        preTransportWiFiConfigurationsSent
                    }
                    val limit = if (postTransport) {
                        MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS
                    } else {
                        MAX_PRE_TRANSPORT_WIFI_CONFIGURATION_SENDS
                    }
                    if (sentCount >= limit) {
                        onProgress(
                            "iap2 0x5703 ignored: maximum Wi-Fi configuration sends reached",
                        )
                    } else {
                        sendControl(accessoryWiFiConfiguration(endpoint))
                        stage = later(
                            stage,
                            if (postTransport) {
                                Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT
                            } else {
                                Iap2WirelessControlStage.WIFI_CONFIG_SENT
                            },
                        )
                        wifiConfigurationsSent++
                        if (postTransport) {
                            postTransportWiFiConfigurationsSent++
                        } else {
                            preTransportWiFiConfigurationsSent++
                        }
                        onProgress("iap2 tx=0x5703 accessory-wifi-configuration")
                    }
                }

                CARPLAY_AVAILABILITY -> {
                    onProgress("iap2 rx=0x4300 carplay-availability")
                    onProgress(carPlayAvailabilityDiagnostic(incoming))
                    if (suppressSessionBootstrap()) {
                        onProgress("iap2 0x4301 suppressed: wireless session already active")
                        continue
                    }
                    if (wifiConfigurationsSent == 0) {
                        sendControl(accessoryWiFiConfiguration(endpoint))
                        stage = later(stage, Iap2WirelessControlStage.WIFI_CONFIG_SENT)
                        wifiConfigurationsSent++
                        preTransportWiFiConfigurationsSent++
                        onProgress("iap2 tx=0x5703 accessory-wifi-configuration")
                    }
                    beforeStartSession()
                    sendStartSession(endpoint, ::sendControl, onStartSessionSent)
                    stage = later(stage, Iap2WirelessControlStage.CARPLAY_START_SENT)
                    carPlayStartSessionsSent++
                    onProgress("iap2 tx=0x4301 carplay-start-session")
                }

                WIRELESS_CARPLAY_UPDATE -> {
                    val available = Iap2WirelessMessages.wirelessCarPlayAvailability(incoming)
                    if (available) wirelessCarPlayAvailableSeen = true
                    onProgress("iap2 rx=0x4e0d wireless-carplay-update available=$available")
                }

                DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION -> {
                    transportNotificationSeen = true
                    val identifiers = Iap2WirelessMessages.deviceTransportIdentifier(incoming)
                    stage = later(stage, Iap2WirelessControlStage.TRANSPORT_NOTIFIED)
                    if (suppressSessionBootstrap()) {
                        onProgress(
                            "iap2 rx=0x4e0e device-transport-identifier " +
                                "bluetooth=${identifiers.bluetoothMac ?: "none"} " +
                                "usb=${identifiers.usbTransportIdentifier ?: "none"}; " +
                                "0x5703 suppressed (wireless session already active)",
                        )
                        continue
                    }
                    onProgress(
                        "iap2 rx=0x4e0e device-transport-identifier " +
                            "bluetooth=${identifiers.bluetoothMac ?: "none"} " +
                            "usb=${identifiers.usbTransportIdentifier ?: "none"}; " +
                            "resending 0x5703",
                    )
                    if (
                        postTransportWiFiConfigurationsSent >=
                        MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS
                    ) {
                        onProgress(
                            "iap2 post-transport 0x5703 ignored: " +
                                "maximum Wi-Fi configuration sends reached",
                        )
                    } else {
                        sendControl(accessoryWiFiConfiguration(endpoint))
                        stage = later(
                            stage,
                            Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT,
                        )
                        wifiConfigurationsSent++
                        postTransportWiFiConfigurationsSent++
                        onProgress(
                            "iap2 tx=0x5703 post-transport accessory-wifi-configuration",
                        )
                    }
                }

                else -> {
                    rxLog.line(incoming.messageId)?.let(onProgress)
                    nowPlayingTracker.acceptUpdate(incoming)?.let(onNowPlaying)
                    onIncoming(incoming)
                    forwardedFrames++
                }
            }
        }
    }

    companion object {
        private const val REQUEST_ACCESSORY_WIFI_CONFIGURATION = 0x5702
        private const val CARPLAY_AVAILABILITY = 0x4300
        private const val WIRELESS_CARPLAY_UPDATE = 0x4e0d
        private const val DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION = 0x4e0e
        const val NO_TIMEOUT_MILLIS = Long.MAX_VALUE
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MAX_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val MAX_PRE_TRANSPORT_WIFI_CONFIGURATION_SENDS = 5
        private const val MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS = 2

        /** Malformed optional availability metadata must not change existing control behavior. */
        internal fun carPlayAvailabilityDiagnostic(frame: Iap2Frame): String = try {
            val value = Iap2CarPlayMessages.availability(frame)
            "iap2 availability wired=${value.wired?.available ?: "unknown"} " +
                "wireless=${value.wireless?.available ?: "unknown"} " +
                "themeAssets=${value.themeAssets?.available ?: "unknown"}"
        } catch (error: Exception) {
            "iap2 availability decode=failed failureClass=${error.javaClass.simpleName}"
        }

        /** Optional AP hint is independent of the AirPlay receiver identity. */
        fun accessoryWiFiConfiguration(endpoint: Iap2WirelessCarPlayEndpoint): Iap2Frame =
            Iap2WirelessMessages.accessoryWiFiConfiguration(
                ssid = endpoint.ssid,
                passphrase = endpoint.passphrase,
                channel = endpoint.channel,
                securityType = endpoint.security.wireValue,
                bssid = endpoint.accessPointBssid,
            )

        /** Wireless 0x4301 reply carrying the receiver address, port and pairing identity. */
        fun carPlayStartSession(endpoint: Iap2WirelessCarPlayEndpoint): Iap2Frame =
            Iap2CarPlayMessages.startSession(
                wireless = Iap2WirelessSessionParameters(
                    ssid = endpoint.ssid,
                    passphrase = endpoint.passphrase,
                    channel = endpoint.channel,
                    ipAddresses = endpoint.ipAddresses,
                    securityType = endpoint.security.wireValue,
                ),
                airPlayPort = endpoint.airPlayPort,
                deviceIdentifier = endpoint.deviceIdentifier,
                publicKey = endpoint.publicKey,
                sourceVersion = endpoint.sourceVersion,
            )

        private fun later(
            current: Iap2WirelessControlStage,
            next: Iap2WirelessControlStage,
        ): Iap2WirelessControlStage = if (current.ordinal >= next.ordinal) current else next
    }
}

/** Wireless 0x5703 security values. */
enum class Iap2WirelessSecurity(val wireValue: Int) {
    NONE(0),
    WEP(1),
    WPA_WPA2(2),
    WPA3_TRANSITION(3),
    WPA3_ONLY(4),
}

/** Wireless hotspot and AirPlay endpoint sent in 0x5703 and 0x4301. */
class Iap2WirelessCarPlayEndpoint(
    val ssid: String,
    val passphrase: String,
    val channel: Int,
    val security: Iap2WirelessSecurity,
    ipAddresses: List<String>,
    val airPlayPort: Int,
    val deviceIdentifier: String,
    val publicKey: String,
    val sourceVersion: String,
    accessPointBssid: ByteArray? = null,
) {
    val ipAddresses: List<String> = ipAddresses.toList()
    val accessPointBssid: ByteArray? = accessPointBssid?.copyOf()

    init {
        require(accessPointBssid == null || accessPointBssid.size == 6) { "AP address must contain six bytes" }
        require(ssid.isNotBlank()) { "ssid is required and must not be blank" }
        require('\u0000' !in ssid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        if (security != Iap2WirelessSecurity.NONE) {
            require(passphrase.isNotEmpty()) { "passphrase is required for secured Wi-Fi" }
        }
        require(channel in 0..0xff) { "channel must be in 0..255" }
        require(ipAddresses.isNotEmpty()) { "At least one wireless IP address is required" }
        require(ipAddresses.all { it.isNotBlank() && '\u0000' !in it }) {
            "Every wireless IP address must be non-blank and must not contain U+0000"
        }
        require(airPlayPort in 1..65535) { "airPlayPort must be in 1..65535" }
        require(deviceIdentifier.isNotBlank()) { "deviceIdentifier is required and must not be blank" }
        require('\u0000' !in deviceIdentifier) { "deviceIdentifier must not contain U+0000" }
        require(publicKey.isNotEmpty()) { "publicKey is required and must not be empty" }
        require('\u0000' !in publicKey) { "publicKey must not contain U+0000" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion is required and must not be empty" }
        require('\u0000' !in sourceVersion) { "sourceVersion must not contain U+0000" }
    }
}

enum class Iap2WirelessControlStage {
    IDENTIFIED,
    AUTHENTICATED,
    SUBSCRIBED,
    WIFI_CONFIG_SENT,
    CARPLAY_START_SENT,
    TRANSPORT_NOTIFIED,
    POST_TRANSPORT_WIFI_CONFIG_SENT,
}

enum class Iap2WirelessControlTerminal { CHANNEL_CLOSED, TIMED_OUT }

/** End state of the control loop only; it is not evidence of a live Wi-Fi or AirPlay session. */
data class Iap2WirelessControlResult(
    val terminal: Iap2WirelessControlTerminal,
    val stage: Iap2WirelessControlStage,
    val forwardedFrames: Int,
    val wifiConfigurationsSent: Int,
    val carPlayStartSessionsSent: Int,
    val transportNotificationSeen: Boolean,
    val postTransportWiFiConfigurationsSent: Int,
    val wirelessCarPlayAvailableSeen: Boolean,
)

/** Emitted only after StartSession has been sent, rather than driving timeouts from log text. */
data class Iap2StartSessionSent(val sentAtNanos: Long)

internal fun sendStartSession(
    endpoint: Iap2WirelessCarPlayEndpoint,
    send: (Iap2Frame) -> Unit,
    onSent: (Iap2StartSessionSent) -> Unit,
) {
    send(Iap2WirelessControlClient.carPlayStartSession(endpoint))
    onSent(Iap2StartSessionSent(System.nanoTime()))
}

/** A bounded bring-up deadline that a proven live session can extend for ongoing control updates. */
internal class WirelessControlDeadline(timeoutMillis: Long, private val nowNanos: () -> Long = System::nanoTime) {
    private var extendedForLiveSession = false
    private val deadlineNanos = if (timeoutMillis == Iap2WirelessControlClient.NO_TIMEOUT_MILLIS) {
        Long.MAX_VALUE
    } else {
        val now = nowNanos()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        if (now > Long.MAX_VALUE - delta) Long.MAX_VALUE else now + delta
    }

    fun remainingMillis(keepAlive: () -> Boolean = { false }): Long {
        if (extendedForLiveSession) return if (keepAlive()) MAX_POLL_MILLIS else 0L
        if (deadlineNanos == Long.MAX_VALUE) return MAX_POLL_MILLIS
        val remaining = deadlineNanos - nowNanos()
        if (remaining <= 0) {
            if (!keepAlive()) return 0L
            extendedForLiveSession = true
            return MAX_POLL_MILLIS
        }
        return min(MAX_POLL_MILLIS, (remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
    }

    fun requireRemaining(keepAlive: () -> Boolean = { false }): Long = remainingMillis(keepAlive).also {
        if (it == 0L) throw Iap2IdentificationException.TimedOut("Timed out during wireless iAP2 control bring-up")
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_POLL_MILLIS = 5 * 60 * 1_000L
    }
}
