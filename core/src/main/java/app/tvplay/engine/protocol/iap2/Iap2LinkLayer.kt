package app.tvplay.engine.protocol.iap2

import app.tvplay.engine.auth.DuplexByteStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.min

/**
 * The iAP2 link layer, deliberately kept independent from I/O, threads, CSM, and media.
 *
 * The caller supplies a monotonic millisecond clock, writes [takeOutput] to its byte stream, and
 * feeds arbitrary receive fragments back through [feed]. It exposes control-session bytes and raw
 * payloads from other negotiated sessions. It does not open a Lockdown connection or own a
 * [DuplexByteStream].
 *
 * An inbound wire frame is limited to the largest u16 length (`65535` bytes total). Pending
 * outbound and out-of-order packets are additionally capped by [Iap2LinkConfig] (64 by default).
 * Pending output is capped at 1 MiB and pending events at 256 entries by default, so neither peer
 * input nor a caller that temporarily stops draining the engine can grow memory without bound.
 */
class Iap2LinkEngine(
    private val config: Iap2LinkConfig = Iap2LinkConfig(),
) {
    enum class State {
        IDLE,
        DETECTING,
        NEGOTIATING,
        NORMAL,
        DEAD,
    }

    sealed class Event {
        data class Control(val bytes: ByteArray) : Event()
        data class Session(val sessionId: Int, val bytes: ByteArray) : Event()
        data class Writable(val value: Boolean) : Event()
        data class Dead(val reason: String?) : Event()
    }

    data class SessionDescriptor(
        val id: Int,
        val kind: Int,
        val version: Int,
    ) {
        init {
            require(id in 0..0xff)
            require(kind in 0..0xff)
            require(version in 0..0xff)
        }
    }

    data class SynchronizationPayload(
        val maxOutgoing: Int,
        val maxLength: Int,
        val retransmissionTimeoutMillis: Int,
        val acknowledgementTimeoutMillis: Int,
        val maxRetransmissions: Int,
        val maxAcknowledgements: Int,
        val sessions: List<SessionDescriptor>,
    ) {
        init {
            require(maxOutgoing in 0..0xff)
            require(maxLength in 0..MAX_WIRE_FRAME_BYTES)
            require(retransmissionTimeoutMillis in 0..0xffff)
            require(acknowledgementTimeoutMillis in 0..0xffff)
            require(maxRetransmissions in 0..0xff)
            require(maxAcknowledgements in 0..0xff)
        }

        fun encode(): ByteArray {
            val out = ByteArrayOutputStream(10 + sessions.size * SESSION_DESCRIPTOR_BYTES)
            out.write(SYNCHRONIZATION_VERSION)
            out.write(maxOutgoing)
            writeU16(out, maxLength)
            writeU16(out, retransmissionTimeoutMillis)
            writeU16(out, acknowledgementTimeoutMillis)
            out.write(maxRetransmissions)
            out.write(maxAcknowledgements)
            sessions.forEach {
                out.write(it.id)
                out.write(it.kind)
                out.write(it.version)
            }
            return out.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): SynchronizationPayload? {
                if (bytes.size < SYNCHRONIZATION_FIXED_BYTES || u8(bytes[0]) != SYNCHRONIZATION_VERSION) {
                    return null
                }
                val descriptors = ArrayList<SessionDescriptor>()
                var offset = SYNCHRONIZATION_FIXED_BYTES
                while (offset + SESSION_DESCRIPTOR_BYTES <= bytes.size) {
                    descriptors += SessionDescriptor(u8(bytes[offset]), u8(bytes[offset + 1]), u8(bytes[offset + 2]))
                    offset += SESSION_DESCRIPTOR_BYTES
                }
                return SynchronizationPayload(
                    maxOutgoing = u8(bytes[1]),
                    maxLength = readU16(bytes, 2),
                    retransmissionTimeoutMillis = readU16(bytes, 4),
                    acknowledgementTimeoutMillis = readU16(bytes, 6),
                    maxRetransmissions = u8(bytes[8]),
                    maxAcknowledgements = u8(bytes[9]),
                    sessions = descriptors,
                )
            }
        }
    }

    private data class Header(
        val length: Int,
        val control: Int,
        val sequence: Int,
        val acknowledgement: Int,
        val sessionId: Int,
    )

    private data class Packet(
        var sequence: Int,
        val sessionId: Int,
        val payload: ByteArray,
        var retransmissions: Int = 0,
        var deadlineMillis: Long = 0,
    )

    private var state = State.IDLE
    private var peerSynchronization = localSynchronization()
    private var peerSynchronizationReceived = false
    private var sentSequence = INITIAL_SEQUENCE
    private var lastSentAcknowledged: Int? = null
    private var lastReceivedInOrder = 0
    private var lastAcknowledgedReceived: Int? = null
    private var accumulatedAcknowledgements = 0
    private var writable = false

    private val unacknowledged = ArrayList<Packet>()
    private val queued = ArrayDeque<Packet>()
    private val outOfOrder = ArrayList<Packet>()
    private val events = ArrayDeque<Event>()
    private val output = ByteArrayOutputStream()
    private val receive = ReceiveBuffer()
    private var awaitingPayload: Header? = null

    private var markerDeadlineMillis: Long? = null
    private var synchronizationDeadlineMillis: Long? = null
    private var sendAcknowledgementDeadlineMillis: Long? = null
    private var receiveAcknowledgementDeadlineMillis: Long? = null

    fun state(): State = state

    fun writable(): Boolean = writable

    fun peerSynchronization(): SynchronizationPayload = peerSynchronization

    fun takeOutput(): ByteArray {
        val bytes = output.toByteArray()
        output.reset()
        return bytes
    }

    fun pollEvent(): Event? = if (events.isEmpty()) null else events.removeFirst()

    fun nextDeadlineMillis(): Long? = listOfNotNull(
        markerDeadlineMillis,
        synchronizationDeadlineMillis,
        sendAcknowledgementDeadlineMillis,
        receiveAcknowledgementDeadlineMillis,
    ).minOrNull()

    /** Starts a link attempt. Wired CarKit uses [wiredInitiator] = true. */
    fun start(wiredInitiator: Boolean, nowMillis: Long) {
        if (state != State.IDLE) return
        state = State.DETECTING
        if (!appendOutput(IAP2_MARKER)) return
        markerDeadlineMillis = nowMillis + MARKER_RESEND_MILLIS
        if (wiredInitiator && state != State.DEAD) enterNegotiating(nowMillis)
    }

    /** Supplies any received byte fragment; empty fragments are harmless. */
    fun feed(bytes: ByteArray, nowMillis: Long) {
        if (state == State.DEAD || bytes.isEmpty()) return
        var offset = 0
        while (offset < bytes.size && state != State.DEAD) {
            val room = receive.remainingCapacity()
            if (room == 0) {
                if (!parseAvailable(nowMillis)) {
                    die("iAP2 inbound frame exceeds $MAX_WIRE_FRAME_BYTES bytes")
                    return
                }
                continue
            }
            val count = minOf(room, bytes.size - offset)
            receive.append(bytes, offset, count)
            offset += count
            while (parseAvailable(nowMillis) && state != State.DEAD) {
                // Drain all complete frames before accepting more input.
            }
        }
    }

    /** The byte stream ended. */
    fun feedEof() {
        die(null)
    }

    /** Advances all due protocol timers with the caller's monotonic clock. */
    fun advanceTime(nowMillis: Long) {
        while (state != State.DEAD) {
            val deadline = nextDeadlineMillis() ?: return
            if (deadline > nowMillis) return
            when {
                markerDeadlineMillis?.let { it <= nowMillis } == true -> {
                    markerDeadlineMillis = null
                    if (state == State.DETECTING) {
                        if (appendOutput(IAP2_MARKER)) {
                            markerDeadlineMillis = nowMillis + MARKER_RESEND_MILLIS
                        }
                    }
                }

                synchronizationDeadlineMillis?.let { it <= nowMillis } == true -> {
                    synchronizationDeadlineMillis = null
                    if (state == State.NEGOTIATING) {
                        sendSynchronization()
                        if (state != State.DEAD) {
                            synchronizationDeadlineMillis = nowMillis + SYNCHRONIZATION_RESEND_MILLIS
                        }
                    }
                }

                sendAcknowledgementDeadlineMillis?.let { it <= nowMillis } == true -> {
                    sendAcknowledgementDeadlineMillis = null
                    if (state == State.NORMAL) {
                        lastAcknowledgedReceived = lastReceivedInOrder
                        sendAcknowledgement()
                    }
                }

                receiveAcknowledgementDeadlineMillis?.let { it <= nowMillis } == true -> {
                    receiveAcknowledgementDeadlineMillis = null
                    retransmitDuePacket(nowMillis)
                }
            }
        }
    }

    /** Queues a complete raw control-session payload (session id 10). */
    fun sendControl(bytes: ByteArray, nowMillis: Long) {
        sendSession(CONTROL_SESSION_ID, bytes, nowMillis)
    }

    /** Queues one raw payload on a negotiated iAP2 session. */
    fun sendSession(sessionId: Int, bytes: ByteArray, nowMillis: Long) {
        require(sessionId in 0..0xff) { "iAP2 session id must fit in one byte" }
        require(bytes.size <= MAX_PAYLOAD_BYTES) {
            "iAP2 session payload exceeds $MAX_PAYLOAD_BYTES bytes"
        }
        if (peerSynchronizationReceived || state == State.NORMAL) {
            require(peerPayloadIsAcceptable(bytes)) {
                "iAP2 session payload exceeds peer maxLength ${peerSynchronization.maxLength}"
            }
        }
        sendPacket(Packet(0, sessionId, bytes.copyOf()), nowMillis)
    }

    private fun parseAvailable(nowMillis: Long): Boolean {
        if (state == State.DEAD) return false
        if (state == State.DETECTING) {
            if (receive.size < IAP2_MARKER.size) return false
            if (!receive.matches(IAP2_MARKER)) {
                die("iAP2 marker was not received")
                return false
            }
            receive.discard(IAP2_MARKER.size)
            enterNegotiating(nowMillis)
            return true
        }

        val pending = awaitingPayload
        if (pending != null) {
            val payloadWithChecksumBytes = pending.length - HEADER_BYTES
            if (receive.size < payloadWithChecksumBytes) return false
            val payloadWithChecksum = receive.take(payloadWithChecksumBytes)
            awaitingPayload = null
            if (checksumValid(payloadWithChecksum)) {
                processFrame(
                    pending,
                    payloadWithChecksum.copyOf(payloadWithChecksum.size - CHECKSUM_BYTES),
                    nowMillis,
                )
            }
            return true
        }

        var discarded = false
        while (receive.size >= 2 && (receive[0] != LINK_START_HIGH || receive[1] != LINK_START_LOW)) {
            receive.discard(1)
            discarded = true
        }
        if (receive.size < HEADER_BYTES) return discarded
        val rawHeader = receive.take(HEADER_BYTES)
        val header = parseHeader(rawHeader)
        if (header == null || header.length < HEADER_BYTES) return true
        if (header.length > MAX_WIRE_FRAME_BYTES) {
            die("iAP2 frame length ${header.length} exceeds $MAX_WIRE_FRAME_BYTES")
            return false
        }
        if (header.length == HEADER_BYTES) {
            processFrame(header, null, nowMillis)
        } else {
            awaitingPayload = header
        }
        return true
    }

    private fun enterNegotiating(nowMillis: Long) {
        state = State.NEGOTIATING
        markerDeadlineMillis = null
        sendSynchronization()
        if (state != State.DEAD) {
            synchronizationDeadlineMillis = nowMillis + SYNCHRONIZATION_RESEND_MILLIS
        }
    }

    private fun processFrame(header: Header, payload: ByteArray?, nowMillis: Long) {
        if ((header.control and CONTROL_RESET) != 0) {
            die("peer sent iAP2 reset")
            return
        }
        if ((header.control and CONTROL_SYNCHRONIZE) != 0) {
            SynchronizationPayload.decode(payload ?: ByteArray(0))?.let {
                handleSynchronization(it, header.sequence)
            }
        }
        if ((header.control and CONTROL_ACKNOWLEDGEMENT) != 0) {
            accumulatedAcknowledgements += 1
            handleAcknowledgement(header.acknowledgement, nowMillis)
        }
        if ((header.control and CONTROL_EXTENDED_ACKNOWLEDGEMENT) != 0 && payload != null) {
            handleExtendedAcknowledgement(payload)
        }
        if ((header.control and CONTROL_ACKNOWLEDGEMENT.inv()) == 0 && payload != null) {
            handleData(Packet(header.sequence, header.sessionId, payload), nowMillis)
        }
        if (peerSynchronization.maxAcknowledgements > 0 &&
            accumulatedAcknowledgements >= peerSynchronization.maxAcknowledgements
        ) {
            accumulatedAcknowledgements = 0
            lastAcknowledgedReceived = lastReceivedInOrder
            sendAcknowledgement()
        }
    }

    private fun handleSynchronization(sync: SynchronizationPayload, sequence: Int) {
        if (state != State.NEGOTIATING) return
        peerSynchronization = sync
        peerSynchronizationReceived = true
        lastReceivedInOrder = sequence
        lastAcknowledgedReceived = sequence
        sendAcknowledgement()
    }

    private fun handleAcknowledgement(acknowledgement: Int, nowMillis: Long) {
        if (state == State.NEGOTIATING) {
            if (!peerSynchronizationReceived || !peerLimitsAreUsable() || queued.any { !peerPayloadIsAcceptable(it.payload) }) {
                die("peer iAP2 maxLength cannot carry pending control packets")
                return
            }
            state = State.NORMAL
            synchronizationDeadlineMillis = null
            setWritable(true)
        }
        lastSentAcknowledged = acknowledgement

        var rearmed = false
        while (unacknowledged.isNotEmpty()) {
            val first = unacknowledged.first()
            val distance = sequenceDistance(first.sequence, lastSentAcknowledged)
            if (distance > 0 && distance <= peerSynchronization.maxAcknowledgements + ACK_SLACK) {
                receiveAcknowledgementDeadlineMillis = first.deadlineMillis
                rearmed = true
                break
            }
            unacknowledged.removeAt(0)
        }
        if (!rearmed) receiveAcknowledgementDeadlineMillis = null

        while (sequenceDistance(sentSequence, lastSentAcknowledged) < peerSynchronization.maxOutgoing) {
            val packet = queued.pollFirst() ?: break
            sendPacket(packet, nowMillis)
            setWritable(true)
        }
    }

    private fun handleExtendedAcknowledgement(missing: ByteArray) {
        if (state != State.NORMAL) return
        for (packet in unacknowledged) {
            if (!missing.any { u8(it) == packet.sequence }) continue
            packet.retransmissions += 1
            if (packet.retransmissions == peerSynchronization.maxRetransmissions) {
                die("iAP2 packet ${packet.sequence} was not acknowledged")
                return
            }
            sendData(packet.sequence, packet.sessionId, packet.payload)
            sendAcknowledgementDeadlineMillis = null
            receiveAcknowledgementDeadlineMillis = packet.deadlineMillis
        }
    }

    private fun handleData(packet: Packet, nowMillis: Long) {
        val distance = sequenceDistance(packet.sequence, lastReceivedInOrder)
        if (distance == 0 || distance > peerSynchronization.maxOutgoing + ACK_SLACK) {
            sendAcknowledgement()
            return
        }
        if (outOfOrder.any { it.sequence == packet.sequence }) return
        if (outOfOrder.size >= config.maximumOutOfOrderPackets) {
            die("iAP2 out-of-order packet limit exceeded")
            return
        }
        outOfOrder += packet
        if (distance > 1) {
            if (distance >= peerSynchronization.maxOutgoing) {
                val missing = ArrayList<Int>()
                var sequence = lastReceivedInOrder
                while (sequenceDistance(packet.sequence, sequence) > 1) {
                    sequence = nextSequence(sequence)
                    missing += sequence
                }
                sendAcknowledgementDeadlineMillis = null
                sendExtendedAcknowledgement(missing)
            }
            return
        }

        outOfOrder.sortBy { sequenceDistance(it.sequence, lastReceivedInOrder) }
        while (outOfOrder.isNotEmpty() && sequenceDistance(outOfOrder.first().sequence, lastReceivedInOrder) == 1) {
            val inOrder = outOfOrder.removeAt(0)
            lastReceivedInOrder = inOrder.sequence
            if (inOrder.sessionId == CONTROL_SESSION_ID) {
                enqueueEvent(Event.Control(inOrder.payload))
            } else {
                enqueueEvent(Event.Session(inOrder.sessionId, inOrder.payload))
            }
        }

        if (peerSynchronization.maxAcknowledgements == 0) return
        val windowBeforeForcedAck = (peerSynchronization.maxOutgoing - config.maxOutgoingDelta).coerceAtLeast(1)
        if (sequenceDistance(lastReceivedInOrder, lastAcknowledgedReceived) >= windowBeforeForcedAck) {
            sendAcknowledgementDeadlineMillis = null
            lastAcknowledgedReceived = lastReceivedInOrder
            sendAcknowledgement()
        } else {
            sendAcknowledgementDeadlineMillis = nowMillis + peerSynchronization.acknowledgementTimeoutMillis
        }
    }

    private fun sendPacket(packet: Packet, nowMillis: Long) {
        if (state != State.NORMAL ||
            sequenceDistance(sentSequence, lastSentAcknowledged) > peerSynchronization.maxOutgoing
        ) {
            if (queued.size >= config.maximumQueuedPackets) {
                die("iAP2 outbound queue limit exceeded")
            } else {
                queued += packet
                setWritable(false)
            }
            return
        }
        sentSequence = nextSequence(sentSequence)
        packet.sequence = sentSequence
        packet.retransmissions = 0
        packet.deadlineMillis = nowMillis + peerSynchronization.retransmissionTimeoutMillis
        sendAcknowledgementDeadlineMillis = null
        sendData(packet.sequence, packet.sessionId, packet.payload)
        lastAcknowledgedReceived = lastReceivedInOrder
        if (peerSynchronization.maxRetransmissions > 0) {
            unacknowledged += packet
            receiveAcknowledgementDeadlineMillis = packet.deadlineMillis
        } else {
            lastSentAcknowledged = sentSequence
        }
    }

    private fun retransmitDuePacket(nowMillis: Long) {
        if (state != State.NORMAL || unacknowledged.isEmpty()) return
        val next = unacknowledged.minByOrNull { it.deadlineMillis } ?: return
        val secondDeadline = unacknowledged.filter { it !== next }.minOfOrNull { it.deadlineMillis }
        next.deadlineMillis = nowMillis + peerSynchronization.retransmissionTimeoutMillis
        next.retransmissions += 1
        if (next.retransmissions == peerSynchronization.maxRetransmissions) {
            die("iAP2 packet ${next.sequence} was not acknowledged")
            return
        }
        sendData(next.sequence, next.sessionId, next.payload)
        receiveAcknowledgementDeadlineMillis = secondDeadline ?: next.deadlineMillis
    }

    private fun sendSynchronization() {
        writePacket(localSynchronization().encode(), sentSequence, CONTROL_SYNCHRONIZE, 0)
    }

    private fun sendAcknowledgement() {
        writePacket(null, sentSequence, CONTROL_ACKNOWLEDGEMENT, 0)
    }

    private fun sendExtendedAcknowledgement(missing: List<Int>) {
        writePacket(missing.map { it.toByte() }.toByteArray(), sentSequence, CONTROL_EXTENDED_ACKNOWLEDGEMENT, 0)
    }

    private fun sendData(sequence: Int, sessionId: Int, payload: ByteArray) {
        writePacket(payload, sequence, CONTROL_ACKNOWLEDGEMENT, sessionId)
    }

    private fun writePacket(payload: ByteArray?, sequence: Int, control: Int, sessionId: Int) {
        if (state == State.DEAD) return
        accumulatedAcknowledgements = 0
        val length = payload?.size?.plus(HEADER_BYTES + CHECKSUM_BYTES) ?: HEADER_BYTES
        val header = ByteArray(HEADER_BYTES)
        header[0] = LINK_START_HIGH
        header[1] = LINK_START_LOW
        header[2] = (length ushr 8).toByte()
        header[3] = length.toByte()
        header[4] = control.toByte()
        header[5] = sequence.toByte()
        header[6] = lastReceivedInOrder.toByte()
        header[7] = sessionId.toByte()
        header[8] = checksum(header, HEADER_BYTES - CHECKSUM_BYTES).toByte()
        val frame = ByteArrayOutputStream(length)
        frame.write(header)
        if (payload != null) {
            frame.write(payload)
            frame.write(checksum(payload, payload.size))
        }
        appendOutput(frame.toByteArray())
    }

    private fun setWritable(value: Boolean) {
        if (writable == value) return
        writable = value
        enqueueEvent(Event.Writable(value))
    }

    private fun die(reason: String?) {
        if (state == State.DEAD) return
        markerDeadlineMillis = null
        synchronizationDeadlineMillis = null
        sendAcknowledgementDeadlineMillis = null
        receiveAcknowledgementDeadlineMillis = null
        state = State.DEAD
        writable = false
        output.reset()
        events.clear()
        events += Event.Dead(reason)
    }

    private fun appendOutput(bytes: ByteArray): Boolean {
        if (state == State.DEAD) return false
        if (bytes.size > config.maximumPendingOutputBytes - output.size()) {
            die("iAP2 pending output limit exceeded")
            return false
        }
        output.write(bytes)
        return true
    }

    private fun enqueueEvent(event: Event) {
        if (state == State.DEAD) return
        if (events.size >= config.maximumPendingEvents) {
            die("iAP2 pending event limit exceeded")
            return
        }
        events += event
    }

    // A usable session-10 link must carry at least one control byte in addition to its frame
    // header and payload checksum. This also gives higher layers a positive chunk limit.
    private fun peerLimitsAreUsable(): Boolean = peerSynchronization.maxLength > HEADER_BYTES + CHECKSUM_BYTES

    private fun peerPayloadIsAcceptable(payload: ByteArray): Boolean =
        peerLimitsAreUsable() && payload.size <= peerSynchronization.maxLength - HEADER_BYTES - CHECKSUM_BYTES

    private fun localSynchronization() = SynchronizationPayload(
        maxOutgoing = config.maxOutgoing,
        maxLength = MAX_WIRE_FRAME_BYTES,
        retransmissionTimeoutMillis = if (config.zeroAcknowledgements) 0 else DEFAULT_RETRANSMISSION_TIMEOUT_MILLIS,
        acknowledgementTimeoutMillis = if (config.zeroAcknowledgements) 0 else config.acknowledgementTimeoutMillis,
        maxRetransmissions = if (config.zeroAcknowledgements) 0 else DEFAULT_MAX_RETRANSMISSIONS,
        maxAcknowledgements = if (config.zeroAcknowledgements) 0 else DEFAULT_MAX_ACKNOWLEDGEMENTS,
        sessions = listOf(
            SessionDescriptor(CONTROL_SESSION_ID, 0, config.controlSessionVersion),
            SessionDescriptor(EA_SESSION_ID, 2, 1),
            SessionDescriptor(FILE_TRANSFER_SESSION_ID, 1, 2),
        ),
    )

    private class ReceiveBuffer {
        private val bytes = ByteArray(MAX_WIRE_FRAME_BYTES)
        private var head = 0
        private var tail = 0

        val size: Int get() = tail - head

        fun remainingCapacity(): Int {
            compact()
            return bytes.size - tail
        }

        operator fun get(index: Int): Byte = bytes[head + index]

        fun append(source: ByteArray, offset: Int, count: Int) {
            require(count <= remainingCapacity())
            source.copyInto(bytes, tail, offset, offset + count)
            tail += count
        }

        fun discard(count: Int) {
            require(count in 0..size)
            head += count
            if (head == tail) {
                head = 0
                tail = 0
            }
        }

        fun take(count: Int): ByteArray {
            require(count in 0..size)
            return bytes.copyOfRange(head, head + count).also { discard(count) }
        }

        fun matches(expected: ByteArray): Boolean = expected.indices.all { bytes[head + it] == expected[it] }

        private fun compact() {
            if (head == 0) return
            if (head < tail) bytes.copyInto(bytes, 0, head, tail)
            tail -= head
            head = 0
        }
    }

    companion object {
        const val CONTROL_SESSION_ID = 10
        const val EA_SESSION_ID = 11
        const val FILE_TRANSFER_SESSION_ID = 12

        val IAP2_MARKER = byteArrayOf(0xff.toByte(), 0x55, 0x02, 0x00, 0xee.toByte(), 0x10)

        private const val HEADER_BYTES = 9
        private const val CHECKSUM_BYTES = 1
        const val MAX_WIRE_FRAME_BYTES = 0xffff
        const val MAX_PAYLOAD_BYTES = MAX_WIRE_FRAME_BYTES - HEADER_BYTES - CHECKSUM_BYTES
        private const val SESSION_DESCRIPTOR_BYTES = 3
        private const val SYNCHRONIZATION_FIXED_BYTES = 10
        private const val SYNCHRONIZATION_VERSION = 1
        private const val LINK_START_HIGH: Byte = 0xff.toByte()
        private const val LINK_START_LOW: Byte = 0x5a
        private const val CONTROL_SYNCHRONIZE = 0x80
        private const val CONTROL_ACKNOWLEDGEMENT = 0x40
        private const val CONTROL_EXTENDED_ACKNOWLEDGEMENT = 0x20
        private const val CONTROL_RESET = 0x10
        private const val INITIAL_SEQUENCE = 99
        private const val MARKER_RESEND_MILLIS = 1_000L
        private const val SYNCHRONIZATION_RESEND_MILLIS = 500L
        private const val DEFAULT_RETRANSMISSION_TIMEOUT_MILLIS = 4_000
        private const val DEFAULT_MAX_RETRANSMISSIONS = 4
        private const val DEFAULT_MAX_ACKNOWLEDGEMENTS = 3
        private const val ACK_SLACK = 10

        private fun parseHeader(bytes: ByteArray): Header? {
            if (bytes.size != HEADER_BYTES || !checksumValid(bytes)) return null
            if (bytes[0] != LINK_START_HIGH || bytes[1] != LINK_START_LOW) return null
            return Header(
                length = readU16(bytes, 2),
                control = u8(bytes[4]),
                sequence = u8(bytes[5]),
                acknowledgement = u8(bytes[6]),
                sessionId = u8(bytes[7]),
            )
        }

        private fun checksumValid(bytes: ByteArray): Boolean = bytes.fold(0) { sum, byte -> (sum + u8(byte)) and 0xff } == 0

        private fun checksum(bytes: ByteArray, count: Int): Int =
            (-bytes.take(count).sumOf { u8(it) }) and 0xff

        private fun sequenceDistance(sequence: Int, previous: Int?): Int =
            if (previous == null) 0 else (sequence - previous) and 0xff

        private fun nextSequence(sequence: Int): Int = (sequence + 1) and 0xff

        private fun readU16(bytes: ByteArray, offset: Int): Int = (u8(bytes[offset]) shl 8) or u8(bytes[offset + 1])

        private fun u8(byte: Byte): Int = byte.toInt() and 0xff

        private fun writeU16(out: ByteArrayOutputStream, value: Int) {
            out.write(value ushr 8)
            out.write(value)
        }
    }
}

/** Configuration advertised by the local link endpoint. */
data class Iap2LinkConfig(
    val maxOutgoing: Int = 30,
    val maxOutgoingDelta: Int = 0,
    val acknowledgementTimeoutMillis: Int = 500,
    val zeroAcknowledgements: Boolean = false,
    val controlSessionVersion: Int = 1,
    val maximumQueuedPackets: Int = 64,
    val maximumOutOfOrderPackets: Int = 64,
    val maximumPendingOutputBytes: Int = 1_048_576,
    val maximumPendingEvents: Int = 256,
) {
    init {
        require(maxOutgoing in 0..0xff)
        require(maxOutgoingDelta in 0..0xff)
        require(acknowledgementTimeoutMillis in 0..0xffff)
        require(controlSessionVersion in 0..0xff)
        require(maximumQueuedPackets in 1..256)
        require(maximumOutOfOrderPackets in 1..256)
        require(maximumPendingOutputBytes >= 1)
        require(maximumPendingEvents >= 1)
    }
}

/**
 * Blocking facade for an iAP2 link over a caller-supplied byte stream.
 *
 * [open] starts one worker. That worker is the only code that drives [Iap2LinkEngine] or calls
 * [DuplexByteStream.send]/[DuplexByteStream.recv]. Callers may concurrently wait
 * for readiness, queue complete session-10 payloads, receive session-10 payloads, or close this
 * channel. This class owns and closes [underlying].
 *
 * It deliberately does not parse CSM control messages, EA, media, UI, or Lockdown setup. The link
 * worker does handle the bounded request/reply flow for Now Playing artwork file transfers so those
 * replies cannot be delayed behind a blocking control-session consumer.
 */
class Iap2LinkChannel private constructor(
    private val underlying: DuplexByteStream,
    private val linkConfig: Iap2LinkConfig,
    private val initiateNegotiation: Boolean,
    private val onArtwork: (Iap2ArtworkTransfer) -> Unit,
) : AutoCloseable {
    private data class Command(val sessionId: Int, val payload: ByteArray)

    private val lock = Object()
    private val commands = ArrayDeque<Command>()
    private var commandBytes = 0
    private val controls = ArrayDeque<ByteArray>()
    private var controlBytes = 0
    private val fileTransfers = Iap2FileTransferReceiver()

    private var ready = false
    private var peerMaxControlPayloadBytes: Int? = null
    private var closing = false
    private var terminated = false
    private var terminalFailure: Throwable? = null
    private var streamCloseStarted = false

    private val worker = Thread(::runPump, "xcertplay-iap2-link").apply {
        isDaemon = true
    }

    /** Waits until session 10 is writable; returns false on timeout or clean close, and throws on failure. */
    fun awaitReady(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        synchronized(lock) {
            waitFor(timeoutMillis) { (ready && !terminated) || terminated }
            throwTerminalFailureLocked()
            return ready && !terminated
        }
    }

    /**
     * The peer's negotiated maximum session-10 payload length, available after [awaitReady]
     * returns true. It excludes the nine-byte iAP2 header and one-byte payload checksum.
     */
    fun peerMaxControlPayloadBytes(): Int? = synchronized(lock) { peerMaxControlPayloadBytes }

    /**
     * Copies and queues one raw session-10 payload. Returns false after close/termination or when
     * the bounded command queue is full. A peer LSP length violation is discovered by the worker
     * when it reaches the engine and terminates the channel.
     */
    fun sendControl(bytes: ByteArray): Boolean {
        require(bytes.size <= Iap2LinkEngine.MAX_PAYLOAD_BYTES) {
            "iAP2 control payload exceeds ${Iap2LinkEngine.MAX_PAYLOAD_BYTES} bytes"
        }
        val copy = bytes.copyOf()
        synchronized(lock) {
            if (terminated || closing) return false
            return enqueueCommandLocked(Iap2LinkEngine.CONTROL_SESSION_ID, copy)
        }
    }

    /**
     * Like [sendControl], but waits for command-queue capacity. CSM uses this to queue every
     * fragment of one frame without converting link backpressure into a partial CSM write.
     */
    internal fun sendControlAwaitCapacity(bytes: ByteArray, timeoutMillis: Long): Boolean {
        require(bytes.size <= Iap2LinkEngine.MAX_PAYLOAD_BYTES) {
            "iAP2 control payload exceeds ${Iap2LinkEngine.MAX_PAYLOAD_BYTES} bytes"
        }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val copy = bytes.copyOf()
        synchronized(lock) {
            waitFor(timeoutMillis) {
                terminated || closing || hasCommandCapacityLocked(copy.size)
            }
            throwTerminalFailureLocked()
            if (terminated || closing) return false
            return enqueueCommandLocked(Iap2LinkEngine.CONTROL_SESSION_ID, copy)
        }
    }

    /** Returns the next control payload; returns null on timeout or clean close, and throws on failure. */
    fun recvControl(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        synchronized(lock) {
            waitFor(timeoutMillis) { controls.isNotEmpty() || terminated }
            throwTerminalFailureLocked()
            if (controls.isEmpty()) return null
            val bytes = controls.removeFirst()
            controlBytes -= bytes.size
            return bytes
        }
    }

    /**
     * Marks the channel closed, directly closes its owned stream to break a blocking recv, then
     * waits a bounded time for the worker. Stream or worker failures are rethrown, including
     * [Error]s; they are never silently discarded.
     */
    override fun close() {
        synchronized(lock) {
            if (!closing) {
                closing = true
                terminalLocked(null)
            }
        }

        var failure = closeUnderlyingOnce()
        if (failure != null) {
            synchronized(lock) {
                terminalFailure = combineFailures(terminalFailure, failure)
                lock.notifyAll()
            }
        }
        try {
            worker.join(CLOSE_JOIN_MILLIS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            failure = combineFailures(failure, interrupted)
        }
        if (worker.isAlive) {
            failure = combineFailures(failure, IOException("iAP2 link worker did not stop after close"))
        }
        synchronized(lock) {
            failure = combineFailures(failure, terminalFailure)
        }
        if (failure != null) throw failure
    }

    private fun runPump() {
        val engine = Iap2LinkEngine(linkConfig)
        try {
            engine.start(wiredInitiator = initiateNegotiation, nowMillis = nowMillis())
            while (!isClosing()) {
                drainCommands(engine)
                engine.advanceTime(nowMillis())
                flush(engine)
                if (!drainEvents(engine)) return
                if (isClosing()) return

                val next = engine.nextDeadlineMillis()
                val waitMillis = min(
                    RECEIVE_POLL_MILLIS,
                    next?.let { (it - nowMillis()).coerceAtLeast(0) } ?: RECEIVE_POLL_MILLIS,
                ).coerceAtLeast(MINIMUM_RECEIVE_POLL_MILLIS)
                val received = underlying.recv(RECEIVE_CHUNK_BYTES, waitMillis)
                if (isClosing()) return
                when {
                    received == null -> Unit
                    received.isEmpty() -> engine.feedEof()
                    else -> engine.feed(received, nowMillis())
                }
            }
            finish(null)
        } catch (failure: Throwable) {
            if (failure is Error) {
                finish(failure)
                throw failure
            }
            if (isClosing()) finish(null) else finish(failure)
        }
    }

    private fun drainCommands(engine: Iap2LinkEngine) {
        while (engine.writable()) {
            val command = synchronized(lock) {
                if (commands.isEmpty()) {
                    null
                } else {
                    commands.removeFirst().also {
                        commandBytes -= it.payload.size
                        lock.notifyAll()
                    }
                }
            } ?: return
            engine.sendSession(command.sessionId, command.payload, nowMillis())
            if (isClosing()) return
        }
    }

    private fun flush(engine: Iap2LinkEngine) {
        val bytes = engine.takeOutput()
        if (bytes.isNotEmpty()) underlying.send(bytes)
    }

    /** Returns false when a link terminal event has been observed. */
    private fun drainEvents(engine: Iap2LinkEngine): Boolean {
        while (true) {
            when (val event = engine.pollEvent() ?: return true) {
                is Iap2LinkEngine.Event.Writable -> synchronized(lock) {
                    if (event.value) {
                        // The engine only emits writable after validating this is positive.
                        peerMaxControlPayloadBytes = engine.peerSynchronization().maxLength - 10
                    }
                    ready = event.value && !terminated
                    lock.notifyAll()
                }

                is Iap2LinkEngine.Event.Control -> {
                    val overflow = synchronized(lock) {
                        if (controls.size >= MAX_PENDING_CONTROLS ||
                            event.bytes.size > MAX_PENDING_CONTROL_BYTES - controlBytes
                        ) {
                            true
                        } else {
                            controls += event.bytes
                            controlBytes += event.bytes.size
                            lock.notifyAll()
                            false
                        }
                    }
                    if (overflow) {
                        finish(IOException("iAP2 received-control queue limit exceeded"))
                        return false
                    }
                }

                is Iap2LinkEngine.Event.Session -> {
                    if (event.sessionId != Iap2LinkEngine.FILE_TRANSFER_SESSION_ID) continue
                    val outcome = fileTransfers.accept(event.bytes)
                    outcome.replies.forEach { reply ->
                        engine.sendSession(Iap2LinkEngine.FILE_TRANSFER_SESSION_ID, reply, nowMillis())
                    }
                    outcome.completed?.let { completed ->
                        try {
                            onArtwork(completed)
                        } catch (_: Exception) {
                            // A UI/consumer callback cannot terminate the control link.
                        }
                    }
                }

                is Iap2LinkEngine.Event.Dead -> {
                    finish(IOException(event.reason ?: "iAP2 link ended"))
                    return false
                }
            }
        }
    }

    private fun finish(failure: Throwable?) {
        synchronized(lock) {
            finishLocked(failure)
        }
        closeUnderlyingOnce()?.let { closeFailure ->
            synchronized(lock) {
                terminalFailure = combineFailures(terminalFailure, closeFailure)
                lock.notifyAll()
            }
        }
    }

    private fun finishLocked(failure: Throwable?) {
        if (!terminated) terminalLocked(failure) else if (failure != null) {
            terminalFailure = combineFailures(terminalFailure, failure)
            lock.notifyAll()
        }
    }

    private fun terminalLocked(failure: Throwable?) {
        terminated = true
        ready = false
        commands.clear()
        commandBytes = 0
        controls.clear()
        controlBytes = 0
        fileTransfers.clear()
        terminalFailure = combineFailures(terminalFailure, failure)
        lock.notifyAll()
    }

    /** lock must already be held. */
    private fun throwTerminalFailureLocked() {
        terminalFailure?.let { throw it }
    }

    private fun isClosing(): Boolean = synchronized(lock) { closing || terminated }

    private fun closeUnderlyingOnce(): Throwable? {
        synchronized(lock) {
            if (streamCloseStarted) return null
            streamCloseStarted = true
        }
        return try {
            underlying.close()
            null
        } catch (failure: Throwable) {
            failure
        }
    }

    /** lock must already be held. */
    private fun hasCommandCapacityLocked(bytes: Int): Boolean =
        commands.size < MAX_PENDING_COMMANDS && bytes <= MAX_PENDING_COMMAND_BYTES - commandBytes

    /** lock must already be held. */
    private fun enqueueCommandLocked(sessionId: Int, bytes: ByteArray): Boolean {
        if (!hasCommandCapacityLocked(bytes.size)) return false
        commands += Command(sessionId, bytes)
        commandBytes += bytes.size
        lock.notifyAll()
        return true
    }

    /** lock must already be held. */
    private fun waitFor(timeoutMillis: Long, readyPredicate: () -> Boolean): Boolean {
        if (readyPredicate()) return true
        if (timeoutMillis == 0L) return false
        val budgetNanos = timeoutMillis.coerceAtMost(MAX_WAIT_MILLIS) * NANOS_PER_MILLISECOND
        val startedNanos = System.nanoTime()
        while (!readyPredicate()) {
            val elapsedNanos = System.nanoTime() - startedNanos
            if (elapsedNanos >= budgetNanos) return false
            val remainingNanos = budgetNanos - elapsedNanos
            try {
                lock.wait(
                    remainingNanos / NANOS_PER_MILLISECOND,
                    (remainingNanos % NANOS_PER_MILLISECOND).toInt(),
                )
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    private fun combineFailures(first: Throwable?, second: Throwable?): Throwable? {
        if (first == null) return second
        if (second != null && second !== first) first.addSuppressed(second)
        return first
    }

    companion object {
        private const val RECEIVE_CHUNK_BYTES = 8_192
        private const val RECEIVE_POLL_MILLIS = 100L
        private const val MINIMUM_RECEIVE_POLL_MILLIS = 1L
        private const val CLOSE_JOIN_MILLIS = 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_WAIT_MILLIS = Long.MAX_VALUE / NANOS_PER_MILLISECOND
        private const val MAX_PENDING_COMMANDS = 64
        private const val MAX_PENDING_COMMAND_BYTES = 1_048_576
        private const val MAX_PENDING_CONTROLS = 64
        private const val MAX_PENDING_CONTROL_BYTES = 1_048_576

        private val WIRED_LINK_CONFIG = Iap2LinkConfig(
            maxOutgoing = 4,
            controlSessionVersion = 2,
            zeroAcknowledgements = true,
        )
        private val WIRELESS_LINK_CONFIG = Iap2LinkConfig(
            maxOutgoing = 4,
            controlSessionVersion = 2,
        )

        /** Opens and immediately starts a wired iAP2 link, taking ownership of the supplied stream. */
        fun open(
            underlying: DuplexByteStream,
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2LinkChannel =
            Iap2LinkChannel(underlying, WIRED_LINK_CONFIG, initiateNegotiation = true, onArtwork)
                .also { it.worker.start() }

        /**
         * Opens a wireless RFCOMM link. Sends the iAP2 marker but lets the phone initiate
         * synchronization, and keeps acknowledgements enabled for Bluetooth.
         */
        fun openWireless(
            underlying: DuplexByteStream,
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2LinkChannel =
            Iap2LinkChannel(underlying, WIRELESS_LINK_CONFIG, initiateNegotiation = false, onArtwork)
                .also { it.worker.start() }

        /**
         * Opens the iAP2 link carried by an AirPlay type-130 tunnel. The accessory initiates
         * synchronization and zero-acknowledgement mode matches the Wi-Fi transport.
         */
        fun openTunnel(
            underlying: DuplexByteStream,
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2LinkChannel =
            Iap2LinkChannel(underlying, WIRED_LINK_CONFIG, initiateNegotiation = true, onArtwork)
                .also { it.worker.start() }

        private fun nowMillis(): Long = System.nanoTime() / NANOS_PER_MILLISECOND
    }
}

/**
 * Blocking CSM facade over one owned [Iap2LinkChannel].
 *
 * It keeps CSM framing separate from typed control messages. Sending waits for a completed iAP2
 * negotiation so every complete CSM frame can be split to the peer's advertised session-10 limit.
 * One receiver owns the stateful framer; concurrent senders are serialized so their split frames
 * cannot interleave. Closing this channel closes its link and the link's owned byte stream.
 */
class Iap2CsmChannel private constructor(
    private val link: Iap2LinkChannel,
) : AutoCloseable {
    private val stateLock = Object()
    private val sendLock = Object()
    private val receiveLock = Object()
    private val framer = Iap2CsmFramer()
    private val receivedFrames = ArrayDeque<Iap2Frame>()
    private var receivedBytes = 0
    private var closed = false
    private var terminalFailure: Throwable? = null

    /** True once this channel is closed or has recorded a terminal transport failure. */
    val isClosed: Boolean
        get() = synchronized(stateLock) {
            closed || terminalFailure != null
        }

    /** Waits for the underlying link to negotiate a writable session 10. */
    fun awaitReady(timeoutMillis: Long): Boolean {
        requireTimeout(timeoutMillis)
        try {
            checkOpen()
        } catch (failure: Error) {
            fatal(failure)
        }
        return try {
            val ready = link.awaitReady(timeoutMillis)
            if (ready && link.peerMaxControlPayloadBytes() == null) {
                throw IOException("iAP2 link became writable without a peer control payload limit")
            }
            ready
        } catch (failure: Throwable) {
            if (failure is Error) fatal(failure)
            failClosed(failure)
        }
    }

    /**
     * Queues one complete CSM frame. Call [awaitReady] successfully first.
     *
     * A false result from the link can mean that a previous chunk of this frame was accepted. In
     * that case continuing would corrupt CSM framing, so the owned link is closed and an exception
     * is thrown instead of returning a partial-send result.
     */
    fun send(frame: Iap2Frame, timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS) {
        requireTimeout(timeoutMillis)
        synchronized(sendLock) {
            try {
                checkOpen()
            } catch (failure: Error) {
                fatal(failure)
            }
            val peerLimit = link.peerMaxControlPayloadBytes()
                ?: throw IllegalStateException("CSM send requires a successful iAP2 awaitReady")
            val chunks = Iap2CsmFramer.splitForLink(frame, peerLimit)
            val deadlineNanos = deadlineAfter(timeoutMillis)
            for (chunk in chunks) {
                val queued = try {
                    link.sendControlAwaitCapacity(chunk, remainingMillis(deadlineNanos))
                } catch (failure: Throwable) {
                    if (failure is Error) fatal(failure)
                    failClosed(failure)
                }
                if (!queued) {
                    failClosed(IOException("iAP2 could not queue the complete CSM frame before its timeout"))
                }
            }
        }
    }

    /**
     * Returns the next complete CSM frame, null on timeout or clean close, and throws on failure.
     * A single underlying control payload can decode into several frames; the remainder stays in a
     * bounded queue for later calls.
     */
    fun recv(timeoutMillis: Long): Iap2Frame? {
        requireTimeout(timeoutMillis)
        synchronized(receiveLock) {
            takeReceived()?.let { return it }
            val deadlineNanos = deadlineAfter(timeoutMillis)
            while (true) {
                try {
                    checkOpen()
                } catch (failure: Error) {
                    fatal(failure)
                }
                val control = try {
                    link.recvControl(remainingMillis(deadlineNanos))
                } catch (failure: Throwable) {
                    if (failure is Error) fatal(failure)
                    failClosed(failure)
                } ?: return null

                val frames = try {
                    framer.offer(control)
                } catch (failure: Throwable) {
                    if (failure is Error) fatal(failure)
                    failClosed(IOException("Invalid CSM control payload", failure))
                }
                for (frame in frames) enqueue(frame)
                takeReceived()?.let { return it }
                if (remainingMillis(deadlineNanos) == 0L) return null
            }
        }
    }

    /** Closes the owned iAP2 link and its owned byte stream. */
    override fun close() {
        val close = synchronized(stateLock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (!close) return
        try {
            link.close()
        } catch (failure: Throwable) {
            if (failure is Error) {
                terminalize(failure)
                throw failure
            }
            throw terminalize(failure)
        }
    }

    private fun enqueue(frame: Iap2Frame) {
        val encodedBytes = frame.encodedFrame().size
        if (receivedFrames.size >= MAX_PENDING_FRAMES || encodedBytes > MAX_PENDING_FRAME_BYTES - receivedBytes) {
            failClosed(IOException("CSM received-frame queue limit exceeded"))
        }
        receivedFrames += frame
        receivedBytes += encodedBytes
    }

    private fun takeReceived(): Iap2Frame? {
        val frame = receivedFrames.pollFirst() ?: return null
        receivedBytes -= frame.encodedFrame().size
        return frame
    }

    private fun checkOpen() {
        synchronized(stateLock) {
            terminalFailure?.let { throw it }
            if (closed) throw IOException("CSM channel is closed")
        }
    }

    /** Records a terminal error, breaks a blocked receiver, then always throws that error. */
    private fun failClosed(failure: Throwable): Nothing {
        val terminal = terminalize(failure)
        try {
            close()
        } catch (closeFailure: Throwable) {
            if (closeFailure is Error) throw closeFailure
            if (closeFailure !== terminal) terminal.addSuppressed(closeFailure)
        }
        throw terminal
    }

    /** Closes best-effort before propagating the original fatal VM or transport error. */
    private fun fatal(error: Error): Nothing {
        terminalize(error)
        try {
            close()
        } catch (_: Throwable) {
            // The original Error is the failure the caller must observe.
        }
        throw error
    }

    private fun terminalize(failure: Throwable): Throwable {
        synchronized(stateLock) {
            val prior = terminalFailure
            if (prior == null) {
                terminalFailure = failure
                return failure
            }
            if (prior !== failure) prior.addSuppressed(failure)
            return prior
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        return System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) return 0
        return min(MAX_TIMEOUT_MILLIS, (remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
    }

    private fun requireTimeout(timeoutMillis: Long) {
        require(timeoutMillis in 0..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 0..$MAX_TIMEOUT_MILLIS"
        }
    }

    companion object {
        private const val MAX_PENDING_FRAMES = 64
        private const val MAX_PENDING_FRAME_BYTES = 1_048_576
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val DEFAULT_SEND_TIMEOUT_MILLIS = 5_000L

        /** Opens the owned iAP2 link over an owned carkit stream. */
        fun open(
            underlying: DuplexByteStream,
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2CsmChannel =
            Iap2CsmChannel(Iap2LinkChannel.open(underlying, onArtwork))

        /** Opens the owned iAP2 link over an owned wireless RFCOMM stream. */
        fun openWireless(
            underlying: DuplexByteStream,
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2CsmChannel =
            Iap2CsmChannel(Iap2LinkChannel.openWireless(underlying, onArtwork))

        /** Opens the owned iAP2 link over an AirPlay type-130 tunnel. */
        fun openTunnel(
            underlying: DuplexByteStream,
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2CsmChannel =
            Iap2CsmChannel(Iap2LinkChannel.openTunnel(underlying, onArtwork))
    }
}

/** An unlimited drive still has a bounded authentication handshake and bounded I/O polls. */
internal class Iap2ControlDeadline(
    private val timeoutMillis: Long,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val unlimited = timeoutMillis == Long.MAX_VALUE
    private val startedNanos = clockNanos()
    private var authenticated = false

    fun authenticated() {
        authenticated = true
    }

    fun remainingMillis(): Long {
        if (unlimited && authenticated) return MAX_POLL_MILLIS
        val budget = if (unlimited) HANDSHAKE_MILLIS else timeoutMillis
        val elapsedNanos = (clockNanos() - startedNanos).coerceAtLeast(0)
        val remainingNanos = budget * 1_000_000 - elapsedNanos
        if (remainingNanos <= 0) return 0
        return ((remainingNanos + 999_999) / 1_000_000).coerceAtMost(MAX_POLL_MILLIS)
    }

    companion object {
        const val HANDSHAKE_MILLIS = 60_000L
        const val MAX_POLL_MILLIS = 30_000L
    }
}

/** A completed iAP2 v2 NowPlayingArtworkData transfer. */
data class Iap2ArtworkTransfer(val id: Int, val bytes: ByteArray)

/**
 * Bounded receiver for iAP2 file-transfer session datagrams.
 *
 * Datagram byte 0 is the transfer id. Byte 1 contains First/Last flags and a low-nibble opcode:
 * Data(0), Start(1), Cancel(2), Setup(4), or Success(5). Version-2 Setup carries an eight-byte
 * big-endian size followed by a two-byte file type. Only NowPlayingArtworkData is accepted.
 */
class Iap2FileTransferReceiver(
    private val maximumArtworkBytes: Int = DEFAULT_MAXIMUM_ARTWORK_BYTES,
) {
    data class Outcome(
        val replies: List<ByteArray> = emptyList(),
        val completed: Iap2ArtworkTransfer? = null,
    )

    private data class Pending(
        val expectedBytes: Int,
        val output: ByteArrayOutputStream = ByteArrayOutputStream(expectedBytes),
    )

    private val pending = LinkedHashMap<Int, Pending>()

    init {
        require(maximumArtworkBytes > 0)
    }

    @Synchronized
    fun accept(datagram: ByteArray): Outcome {
        if (datagram.size < HEADER_BYTES) return Outcome()
        val id = datagram[0].toInt() and 0xff
        val flags = datagram[1].toInt() and 0xff
        return when (flags and OPCODE_MASK) {
            OPCODE_SETUP -> setup(id, datagram)
            OPCODE_DATA -> data(id, flags, datagram)
            OPCODE_CANCEL -> {
                if (pending.remove(id) == null) return Outcome()
                Outcome(completed = Iap2ArtworkTransfer(id, ByteArray(0)))
            }
            else -> Outcome()
        }
    }

    @Synchronized
    fun clear() = pending.clear()

    private fun setup(id: Int, datagram: ByteArray): Outcome {
        pending.remove(id)
        if (datagram.size < VERSION_2_SETUP_BYTES) return Outcome(replies = listOf(cancel(id)))
        val size = readU64(datagram, SIZE_OFFSET)
        val type = readU16(datagram, FILE_TYPE_OFFSET)
        if (type != NOW_PLAYING_ARTWORK_TYPE) return Outcome(replies = listOf(cancel(id)))
        if (size < 0 || size > maximumArtworkBytes) return rejected(id)
        if (size == 0L) {
            return Outcome(
                replies = listOf(start(id), success(id)),
                completed = Iap2ArtworkTransfer(id, ByteArray(0)),
            )
        }
        pending[id] = Pending(size.toInt())
        while (pending.size > MAXIMUM_PENDING_TRANSFERS) pending.remove(pending.keys.first())
        return Outcome(replies = listOf(start(id)))
    }

    private fun data(id: Int, flags: Int, datagram: ByteArray): Outcome {
        val transfer = pending[id] ?: return Outcome()
        val count = datagram.size - HEADER_BYTES
        if (count > transfer.expectedBytes - transfer.output.size()) {
            pending.remove(id)
            return rejected(id)
        }
        transfer.output.write(datagram, HEADER_BYTES, count)
        val last = flags and FLAG_LAST != 0
        if (!last && transfer.output.size() < transfer.expectedBytes) return Outcome()
        pending.remove(id)
        if (transfer.output.size() != transfer.expectedBytes) {
            return rejected(id)
        }
        return Outcome(
            replies = listOf(success(id)),
            completed = Iap2ArtworkTransfer(id, transfer.output.toByteArray()),
        )
    }

    // An empty completion clears the art, so a refused cover does not leave the previous song's.
    private fun rejected(id: Int) = Outcome(replies = listOf(cancel(id)), completed = Iap2ArtworkTransfer(id, ByteArray(0)))

    private fun start(id: Int) = byteArrayOf(id.toByte(), OPCODE_START.toByte())

    private fun cancel(id: Int) = byteArrayOf(id.toByte(), OPCODE_CANCEL.toByte())

    private fun success(id: Int) = byteArrayOf(id.toByte(), OPCODE_SUCCESS.toByte())

    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) {
            if (value < 0) return -1
            value = (value shl 8) or (bytes[offset + it].toLong() and 0xff)
        }
        return value
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    companion object {
        private const val HEADER_BYTES = 2
        private const val SIZE_OFFSET = 2
        private const val FILE_TYPE_OFFSET = 10
        private const val VERSION_2_SETUP_BYTES = 12
        private const val OPCODE_MASK = 0x0f
        private const val FLAG_LAST = 0x40
        private const val OPCODE_DATA = 0
        private const val OPCODE_START = 1
        private const val OPCODE_CANCEL = 2
        private const val OPCODE_SETUP = 4
        private const val OPCODE_SUCCESS = 5
        private const val NOW_PLAYING_ARTWORK_TYPE = 2
        private const val MAXIMUM_PENDING_TRANSFERS = 4
        const val DEFAULT_MAXIMUM_ARTWORK_BYTES = 2 * 1024 * 1024
    }
}

/**
 * The report line for each received iAP2 message. Now-playing (0x5001) and route-guidance
 * (0x5201) updates repeat every second or so: together they were over half of a 25,000-line
 * report (#473), and reports cut off by GitHub's size limits lost the lines that mattered (#536,
 * #606). Those two are logged the first [first] times, then once every [every] with a count.
 */
internal class Iap2RxLog(private val first: Int = 5, private val every: Int = 100) {
    private val counts = HashMap<Int, Int>()

    fun line(messageId: Int): String? {
        val id = "0x" + messageId.toString(16).padStart(4, '0')
        if (messageId !in PERIODIC) return "iap2 rx=$id"
        val count = (counts[messageId] ?: 0) + 1
        counts[messageId] = count
        return when {
            count <= first -> "iap2 rx=$id"
            count % every == 0 -> "iap2 rx=$id count=$count"
            else -> null
        }
    }

    private companion object {
        val PERIODIC = setOf(0x5001, 0x5201)
    }
}
