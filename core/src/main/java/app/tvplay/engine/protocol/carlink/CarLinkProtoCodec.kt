package app.tvplay.engine.protocol.carlink

import java.io.ByteArrayOutputStream

/**
 * Pure Kotlin Protobuf (proto3 wire format) encoder and decoder for ICCOA CarLink (`UCarProto`).
 *
 * Field numbers are verified against `com.ucar.databus.proto.UCarProto` in `ucar-sdk`.
 */
object CarLinkProtoCodec {
    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2

    data class AuthRequestMsg(
        val version: Int = 1281,
        val authPk: ByteArray = ByteArray(0),
        val authPkHmac: ByteArray = ByteArray(0),
        val agreementPk: ByteArray = ByteArray(0),
        val agreementPkSig: ByteArray = ByteArray(0),
        val random: ByteArray = ByteArray(0),
        val id: ByteArray = ByteArray(0),
        val model: String = "",
        val userConfirmed: Boolean = false,
        val workModes: Int = 0,
        val productName: String = "",
    )

    data class AuthResponseMsg(
        val version: Int = 1281,
        val authPk: ByteArray = ByteArray(0),
        val authPkHmac: ByteArray = ByteArray(0),
        val agreementPk: ByteArray = ByteArray(0),
        val agreementPkSig: ByteArray = ByteArray(0),
        val random: ByteArray = ByteArray(0),
        val result: Int = 0,
        val workModes: Int = 1,
        val dayOrNightMode: Int = 2,
        val screenInfo: String = "",
    )

    data class AuthConfirmMsg(
        val cipher: ByteArray = ByteArray(0),
    )

    data class CarCertificateMsg(
        val content: ByteArray = ByteArray(0),
    )

    data class GetPortRequestMsg(
        val type: Int = 0,
    )

    data class GetPortResponseMsg(
        val result: Int = 0,
        val port: Int = 0,
    )

    data class GetUCarConfigResponseMsg(
        val carBrMac: ByteArray,
        val screenWidth: Int,
        val screenHeight: Int,
        val dpi: Int,
        val videoDisplayWidth: Int,
        val videoDisplayHeight: Int,
        val fps: Int = 60,
        val isSupportP2P: Boolean = true,
        val isSupportSoftAP: Boolean = false,
        val isDataTransMode: Boolean = true,
        val default5GChannel: Int = 149,
        val isSupportCamera: Boolean = false,
        val isSupportMic: Boolean = true,
        val isSupportLowLatencyDecodingMode: Boolean = true,
        val isSupportVoiceWaken: Boolean = false,
        val carCustomField: ByteArray = ByteArray(0),
        val sdkVersion: String = "1.5.0",
        val workModes: Int = 1,
        val isSupportPOINav: Boolean = true,
        val isSupportRotation: Boolean = false,
        val isSupportHomeKey: Boolean = true,
        val isSupportBackKey: Boolean = true,
        val isSupportHevc: Boolean = true,
        val realDpi: Int = dpi,
    )

    data class CustomKeyEventMsg(
        val action: Int,
        val keycode: Int,
        val metaState: Int = 0,
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class NotifyMirrorStateMsg(
        val mirrorState: Int,
    )

    data class NotifySwitchDayOrNightMsg(
        val mode: Int,
    )

    data class AudioPlayerControlMsg(
        val audioType: Int = 0,
        val bufferingCount: Int = 0,
        val speedAdjustStep: Int = 0,
    )

    data class HeartbeatMsg(
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class NotifyMusicInfoMsg(
        val artistName: String = "",
        val albumName: String = "",
        val coverArt: String = "",
        val lyrics: String = "",
        val playingTimesMs: Long = 0L,
        val title: String = "",
        val authorName: String = "",
        val writerName: String = "",
        val composerName: String = "",
        val playingCurrentTimeMs: Int = 0,
        val isFavorite: Boolean = false,
        val isPlaying: Boolean = false,
        val coverArtBitmap: ByteArray = ByteArray(0),
        val id: Int = 0,
    )

    data class NotifyNavigationInfoMsg(
        val isNavigating: Boolean = false,
        val directionIcon: ByteArray = ByteArray(0),
        val distance: String = "",
        val distanceUnit: String = "",
        val operation: String = "",
        val where: String = "",
        val title1: String = "",
        val title2: String = "",
    )

    // -------------------------------------------------------------------------
    // Encoders
    // -------------------------------------------------------------------------

    fun encodeAuthRequest(msg: AuthRequestMsg): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt32(out, 1, msg.version)
        writeBytes(out, 2, msg.authPk)
        writeBytes(out, 3, msg.authPkHmac)
        writeBytes(out, 4, msg.agreementPk)
        writeBytes(out, 5, msg.agreementPkSig)
        writeBytes(out, 6, msg.random)
        writeBytes(out, 7, msg.id)
        writeString(out, 8, msg.model)
        writeBool(out, 9, msg.userConfirmed)
        writeInt32(out, 10, msg.workModes)
        writeString(out, 11, msg.productName)
        return out.toByteArray()
    }

    fun encodeAuthResponse(msg: AuthResponseMsg): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt32(out, 1, msg.version)
        writeBytes(out, 2, msg.authPk)
        writeBytes(out, 3, msg.authPkHmac)
        writeBytes(out, 4, msg.agreementPk)
        writeBytes(out, 5, msg.agreementPkSig)
        writeBytes(out, 6, msg.random)
        writeInt32(out, 7, msg.result, includeZero = true)
        writeInt32(out, 8, msg.workModes)
        writeInt32(out, 9, msg.dayOrNightMode)
        writeString(out, 10, msg.screenInfo)
        return out.toByteArray()
    }

    fun encodeAuthConfirm(msg: AuthConfirmMsg): ByteArray {
        val out = ByteArrayOutputStream()
        writeBytes(out, 1, msg.cipher)
        return out.toByteArray()
    }

    fun encodeCarCertificate(msg: CarCertificateMsg): ByteArray {
        val out = ByteArrayOutputStream()
        writeBytes(out, 1, msg.content)
        return out.toByteArray()
    }

    fun encodeGetPortResponse(msg: GetPortResponseMsg): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt32(out, 1, msg.result, includeZero = true)
        writeInt32(out, 2, msg.port, includeZero = true)
        return out.toByteArray()
    }

    fun encodeGetUCarConfigResponse(msg: GetUCarConfigResponseMsg): ByteArray {
        val out = ByteArrayOutputStream()
        writeBytes(out, 1, msg.carBrMac)
        writeInt32(out, 2, msg.screenWidth)
        writeInt32(out, 3, msg.screenHeight)
        writeInt32(out, 4, msg.dpi)
        writeInt32(out, 5, msg.videoDisplayWidth)
        writeInt32(out, 6, msg.videoDisplayHeight)
        writeInt32(out, 7, msg.fps)
        writeBool(out, 8, msg.isSupportP2P)
        writeBool(out, 9, msg.isSupportSoftAP)
        writeBool(out, 10, msg.isDataTransMode)
        writeInt32(out, 11, msg.default5GChannel)
        writeBool(out, 12, msg.isSupportCamera)
        writeBool(out, 13, msg.isSupportMic)
        writeBool(out, 14, msg.isSupportLowLatencyDecodingMode)
        writeBool(out, 15, msg.isSupportVoiceWaken)
        writeBytes(out, 16, msg.carCustomField)
        writeString(out, 17, msg.sdkVersion)
        writeInt32(out, 18, msg.workModes)
        writeBool(out, 19, msg.isSupportPOINav)
        writeBool(out, 20, msg.isSupportRotation)
        writeBool(out, 21, msg.isSupportHomeKey)
        writeBool(out, 22, msg.isSupportBackKey)
        writeBool(out, 23, msg.isSupportHevc)
        writeInt32(out, 24, msg.realDpi)
        return out.toByteArray()
    }

    fun encodeCustomKeyEvent(msg: CustomKeyEventMsg): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt32(out, 1, msg.action, includeZero = true)
        writeInt32(out, 2, msg.keycode, includeZero = true)
        writeInt32(out, 3, msg.metaState)
        writeInt64(out, 4, msg.timestamp)
        return out.toByteArray()
    }

    fun encodeNotifyMirrorState(msg: NotifyMirrorStateMsg): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt32(out, 1, msg.mirrorState, includeZero = true)
        return out.toByteArray()
    }

    fun encodeNotifySwitchDayOrNight(msg: NotifySwitchDayOrNightMsg): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt32(out, 1, msg.mode, includeZero = true)
        return out.toByteArray()
    }

    fun encodeAudioPlayerControl(msg: AudioPlayerControlMsg): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt32(out, 1, msg.audioType, includeZero = true)
        writeInt32(out, 2, msg.bufferingCount)
        writeInt32(out, 3, msg.speedAdjustStep)
        return out.toByteArray()
    }

    fun encodeHeartbeat(msg: HeartbeatMsg): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt64(out, 1, msg.timestamp)
        return out.toByteArray()
    }

    /**
     * `UCarProto.NotifyCarToForeground` (`int64 timestamp = 1;`).
     */
    fun encodeNotifyCarToForeground(timestampMs: Long = System.currentTimeMillis()): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt64(out, 1, timestampMs)
        return out.toByteArray()
    }

    /**
     * `UCarProto.AwakenVoiceAssistant`:
     * - `1`: `bytes pcmData` (optional)
     * - `2`: `AudioFormat audioFormat` (optional)
     * - `3`: `int32 userPreset` (optional, default 0)
     */
    fun encodeAwakenVoiceAssistant(userPreset: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt32(out, 3, userPreset, includeZero = true)
        return out.toByteArray()
    }

    /**
     * `UCarProto.PickUpCall` (`isPickUp = true` -> field 1 bool).
     */
    fun encodePickUpCall(isPickUp: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        writeBool(out, 1, isPickUp)
        return out.toByteArray()
    }

    /**
     * `UCarProto.NotifyCallHungUp` (`isHungUp = true` -> field 1 bool).
     */
    fun encodeNotifyCallHungUp(isHungUp: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        writeBool(out, 1, isHungUp)
        return out.toByteArray()
    }

    /**
     * Default ICCOA CarLink `ccd.data` head-unit certificate (Base64-encoded signed JSON
     * with `cryptoConfigIndex = "o-5T4plzjMmcilZs"`, `signAlgorithm = "SHA256withRSA"`, `version = 1`).
     * Required by the phone's `CarCertManager` on `CERT (57249)` before starting WFD projection.
     */
    const val DEFAULT_CAR_CERT_BASE64: String =
        "eyJjb250ZW50IjpbMTIzLDM0LDExNSwxMDQsMTExLDExNCwxMTYsOTUsMTEwLDk3LDEwOSwxMDEsMzQsNTgsMzQsLTI2LC03NSwtMTE3LDQ4LC0yNCwtODEsLTEwNyw0OSwtMjQsLTY3LC05MCw1MCwtMjYsLTEwMCwtNzAsNTEsMzQsNDQsMzQsMTEyLDExNCwxMTEsMTAwLDExNyw5OSwxMTYsOTUsMTEwLDk3LDEwOSwxMDEsMzQsNTgsMzQsLTI2LC03NSwtMTE3LDQ4LC0yNCwtODEsLTEwNyw0OSwtMjQsLTY3LC05MCw1MCwtMjYsLTEwMCwtNzAsNTEsMzIsNTAsNDgsNTAsNTAsLTI2LC04NCwtNjYsMzQsNDQsMzQsMTE4LDEwMSwxMTAsMTAwLDExMSwxMTQsOTUsMTEwLDk3LDEwOSwxMDEsMzQsNTgsMzQsLTI2LC03NSwtMTE3LDQ4LC0yNCwtODEsLTEwNyw0OSwtMjQsLTY3LC05MCw1MCwtMjcsLTExNCwtMTI2LDUxLDM0LDQ0LDM0LDExOCwxMDEsMTEwLDEwMCwxMTEsMTE0LDk1LDEwNSwxMDAsMzQsNTgsMzQsNDgsNDgsNDgsNDksMzQsNDQsMzQsMTEyLDExNCwxMTEsMTAwLDExNyw5OSwxMTYsOTUsMTA1LDEwMCwzNCw1OCwzNCw0OCw0OCw0OCw0OSwzNCwxMjVdLCJjcnlwdG9Db25maWdJbmRleCI6Im8tNVQ0cGx6ak1tY2lsWnMiLCJzaWduQWxnb3JpdGhtIjoiU0hBMjU2d2l0aFJTQSIsInNpZ25hdHVyZSI6Wzk2LC02NCwxNiw1NiwtMTE0LC05OCwtNDEsNjMsOTEsLTc1LDEyNiwtNjcsLTExMSwyOSwtNTUsMTUsMjcsLTEwNywtNzYsNDksLTE2LDYyLC00NywtNDYsLTc1LC0xNCwyNCwxMDMsMzksMCwtMTA3LDEwNCw2LDk4LDEwNyw3MiwzNiwtNDYsODQsODgsLTY4LC0yMCw2NywtMTIyLC0xMDgsLTcxLC0xMjgsNTUsLTMyLC03LC0yMCwtMTA3LDg0LDExNyw4NSwzMiwtMTI2LC0xMTksNTYsNSwtMTgsLTg4LC01NywtODgsOTUsLTEyNiwtOTQsLTEwNSw1Miw4OCwtNTUsNiwtOTIsNDAsLTgyLC0zNCwxMTIsMzUsMjYsLTEzLDM5LC0xMDAsMTAyLDIxLDEyNywtMjksODMsLTQ3LC03OCw2OCw3Miw1MCwtOCwxMTUsMTUsLTEyNiwtNjgsLTQ1LC01MiwxMTEsNDksNDUsLTcsMjEsLTEwNyw0MywtMTIzLC0zOCwzNyw2NSwtNzIsMjcsODQsNzUsMTA0LC01NywtOTksMTQsMTExLC01NSwxMTIsLTUxLC0xMDksLTkyLC04MiwyNSwxMjQsODQsLTMzLDQ2LC01OCwtMTEsMTAyLC05MCwtMTI0LDUzLC0xMTUsLTQ2LC00MywtNiw5MCwtOSwtMTgsNDEsNDUsNzcsLTgyLC00NiwzMCw5NSw4NiwtNDQsLTExNSwtMTI3LDcxLDMyLDgsLTExNSwtMTgsODMsLTEwOSwtMTE1LDgxLC03MiwtOTIsOTIsLTYzLC05NywtNDUsLTM2LDY4LC05NCwtNTIsMTQsMjEsLTk3LC00LDEwOCwzNCw5MSw1MSwtNjQsODgsLTEwNSwtMTEyLDg5LC03MSwtODMsNTYsLTEwMywxMjIsLTk4LDk1LDEwNiwtNjEsLTYzLC05MSwzMCwtMTAxLDc2LDksLTk1LC0zMiwtNDYsMzksLTk2LC03NywtNDgsLTI3LDU2LDEwMSwtMTIyLC01MywtOCw3NywtMjEsLTExOSwtNzAsMTI3LDkxLDEwOCwtNDUsLTM1LC0xMDQsMzUsLTQ5LDg1LDQxLDEwMSw3LC00MSwtMTA4LC02OCwzMywtNDksLTYsNDksLTkzLDk1LDM0LC0xMjAsLTgxLC0xNCw3OSwtNzgsLTQzLDQ5LDQ5LDYyLC04NCwtMTA2LDc3LC0yNywxMDEsNjksMTExXSwidmVyc2lvbiI6MX0="

    val DEFAULT_CAR_CERT_BYTES: ByteArray = DEFAULT_CAR_CERT_BASE64.toByteArray(Charsets.US_ASCII)

    /**
     * Certificate-bound `short_name` and `product_name` inside [DEFAULT_CAR_CERT_BASE64] (`ccd.data`).
     * `ucar-sdk` (`UCarConnectProxy`) extracts these from `ccd.data` and uses `short_name` in BLE
     * Scan Response `0x0002` (`ADV_CAR_INFO_2_UUID`) and `product_name` in `ServerConnectionInfo.name`.
     */
    const val DEFAULT_CAR_SHORT_NAME: String = "测0试1车2机3"
    const val DEFAULT_CAR_PRODUCT_NAME: String = "测0试1车2机3 2022款"

    // -------------------------------------------------------------------------
    // Decoders
    // -------------------------------------------------------------------------

    fun decodeAuthRequest(data: ByteArray): AuthRequestMsg {
        var version = 1281
        var authPk = ByteArray(0)
        var authPkHmac = ByteArray(0)
        var agreementPk = ByteArray(0)
        var agreementPkSig = ByteArray(0)
        var random = ByteArray(0)
        var id = ByteArray(0)
        var model = ""
        var userConfirmed = false
        var workModes = 0
        var productName = ""
        parseFields(
            data = data,
            onVarint = { field, value ->
                when (field) {
                    1 -> version = value.toInt()
                    9 -> userConfirmed = value != 0L
                    10 -> workModes = value.toInt()
                }
            },
            onBytes = { field, bytes ->
                when (field) {
                    2 -> authPk = bytes
                    3 -> authPkHmac = bytes
                    4 -> agreementPk = bytes
                    5 -> agreementPkSig = bytes
                    6 -> random = bytes
                    7 -> id = bytes
                    8 -> model = bytes.toString(Charsets.UTF_8)
                    11 -> productName = bytes.toString(Charsets.UTF_8)
                }
            },
        )
        return AuthRequestMsg(
            version = version,
            authPk = authPk,
            authPkHmac = authPkHmac,
            agreementPk = agreementPk,
            agreementPkSig = agreementPkSig,
            random = random,
            id = id,
            model = model,
            userConfirmed = userConfirmed,
            workModes = workModes,
            productName = productName,
        )
    }

    fun decodeAuthResponse(data: ByteArray): AuthResponseMsg {
        var version = 1281
        var authPk = ByteArray(0)
        var authPkHmac = ByteArray(0)
        var agreementPk = ByteArray(0)
        var agreementPkSig = ByteArray(0)
        var random = ByteArray(0)
        var result = 0
        var workModes = 0
        var dayOrNightMode = 0
        var screenInfo = ""
        parseFields(
            data = data,
            onVarint = { field, value ->
                when (field) {
                    1 -> version = value.toInt()
                    7 -> result = value.toInt()
                    8 -> workModes = value.toInt()
                    9 -> dayOrNightMode = value.toInt()
                }
            },
            onBytes = { field, bytes ->
                when (field) {
                    2 -> authPk = bytes
                    3 -> authPkHmac = bytes
                    4 -> agreementPk = bytes
                    5 -> agreementPkSig = bytes
                    6 -> random = bytes
                    10 -> screenInfo = bytes.toString(Charsets.UTF_8)
                }
            },
        )
        return AuthResponseMsg(
            version = version,
            authPk = authPk,
            authPkHmac = authPkHmac,
            agreementPk = agreementPk,
            agreementPkSig = agreementPkSig,
            random = random,
            result = result,
            workModes = workModes,
            dayOrNightMode = dayOrNightMode,
            screenInfo = screenInfo,
        )
    }

    fun decodeAuthConfirm(data: ByteArray): AuthConfirmMsg {
        var cipher = ByteArray(0)
        parseFields(
            data = data,
            onVarint = { _, _ -> },
            onBytes = { field, bytes -> if (field == 1) cipher = bytes },
        )
        return AuthConfirmMsg(cipher = cipher)
    }

    fun decodeGetPortRequest(data: ByteArray): GetPortRequestMsg {
        var type = 0
        parseFields(
            data = data,
            onVarint = { field, value -> if (field == 1) type = value.toInt() },
            onBytes = { _, _ -> },
        )
        return GetPortRequestMsg(type = type)
    }

    fun decodeHeartbeat(data: ByteArray): HeartbeatMsg {
        var ts = 0L
        parseFields(
            data = data,
            onVarint = { field, value -> if (field == 1) ts = value },
            onBytes = { _, _ -> },
        )
        return HeartbeatMsg(timestamp = ts)
    }

    fun decodeNotifyMusicInfo(data: ByteArray): NotifyMusicInfoMsg {
        var artistName = ""
        var albumName = ""
        var coverArt = ""
        var lyrics = ""
        var playingTimesMs = 0L
        var title = ""
        var authorName = ""
        var writerName = ""
        var composerName = ""
        var playingCurrentTimeMs = 0
        var isFavorite = false
        var isPlaying = false
        var coverArtBitmap = ByteArray(0)
        var id = 0
        parseFields(
            data = data,
            onVarint = { field, value ->
                when (field) {
                    5 -> playingTimesMs = value
                    10 -> playingCurrentTimeMs = value.toInt()
                    11 -> isFavorite = value != 0L
                    12 -> isPlaying = value != 0L
                    14 -> id = value.toInt()
                }
            },
            onBytes = { field, bytes ->
                when (field) {
                    1 -> artistName = bytes.toString(Charsets.UTF_8)
                    2 -> albumName = bytes.toString(Charsets.UTF_8)
                    3 -> coverArt = bytes.toString(Charsets.UTF_8)
                    4 -> lyrics = bytes.toString(Charsets.UTF_8)
                    6 -> title = bytes.toString(Charsets.UTF_8)
                    7 -> authorName = bytes.toString(Charsets.UTF_8)
                    8 -> writerName = bytes.toString(Charsets.UTF_8)
                    9 -> composerName = bytes.toString(Charsets.UTF_8)
                    13 -> coverArtBitmap = bytes
                }
            },
        )
        return NotifyMusicInfoMsg(
            artistName = artistName,
            albumName = albumName,
            coverArt = coverArt,
            lyrics = lyrics,
            playingTimesMs = playingTimesMs,
            title = title,
            authorName = authorName,
            writerName = writerName,
            composerName = composerName,
            playingCurrentTimeMs = playingCurrentTimeMs,
            isFavorite = isFavorite,
            isPlaying = isPlaying,
            coverArtBitmap = coverArtBitmap,
            id = id,
        )
    }

    fun decodeNotifyNavigationInfo(data: ByteArray): NotifyNavigationInfoMsg {
        var isNavigating = false
        var directionIcon = ByteArray(0)
        var distance = ""
        var distanceUnit = ""
        var operation = ""
        var where = ""
        var title1 = ""
        var title2 = ""
        parseFields(
            data = data,
            onVarint = { field, value ->
                if (field == 1) isNavigating = value != 0L
            },
            onBytes = { field, bytes ->
                when (field) {
                    2 -> directionIcon = bytes
                    3 -> distance = bytes.toString(Charsets.UTF_8)
                    4 -> distanceUnit = bytes.toString(Charsets.UTF_8)
                    5 -> operation = bytes.toString(Charsets.UTF_8)
                    6 -> where = bytes.toString(Charsets.UTF_8)
                    7 -> title1 = bytes.toString(Charsets.UTF_8)
                    8 -> title2 = bytes.toString(Charsets.UTF_8)
                }
            },
        )
        return NotifyNavigationInfoMsg(
            isNavigating = isNavigating,
            directionIcon = directionIcon,
            distance = distance,
            distanceUnit = distanceUnit,
            operation = operation,
            where = where,
            title1 = title1,
            title2 = title2,
        )
    }

    // -------------------------------------------------------------------------
    // Low-level Protobuf wire primitives
    // -------------------------------------------------------------------------

    private fun writeTag(out: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        writeRawVarint64(out, ((fieldNumber shl 3) or wireType).toLong())
    }

    private fun writeInt32(
        out: ByteArrayOutputStream,
        fieldNumber: Int,
        value: Int,
        includeZero: Boolean = false,
    ) {
        if (value == 0 && !includeZero) return
        writeTag(out, fieldNumber, WIRE_VARINT)
        writeRawVarint64(out, value.toLong())
    }

    private fun writeInt64(
        out: ByteArrayOutputStream,
        fieldNumber: Int,
        value: Long,
        includeZero: Boolean = false,
    ) {
        if (value == 0L && !includeZero) return
        writeTag(out, fieldNumber, WIRE_VARINT)
        writeRawVarint64(out, value)
    }

    private fun writeBool(
        out: ByteArrayOutputStream,
        fieldNumber: Int,
        value: Boolean,
    ) {
        if (!value) return
        writeTag(out, fieldNumber, WIRE_VARINT)
        out.write(1)
    }

    private fun writeBytes(
        out: ByteArrayOutputStream,
        fieldNumber: Int,
        value: ByteArray,
    ) {
        if (value.isEmpty()) return
        writeTag(out, fieldNumber, WIRE_LENGTH_DELIMITED)
        writeRawVarint64(out, value.size.toLong())
        out.write(value)
    }

    private fun writeString(
        out: ByteArrayOutputStream,
        fieldNumber: Int,
        value: String,
    ) {
        if (value.isEmpty()) return
        writeBytes(out, fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    private fun writeRawVarint64(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
    }

    private fun parseFields(
        data: ByteArray,
        onVarint: (fieldNumber: Int, value: Long) -> Unit,
        onBytes: (fieldNumber: Int, bytes: ByteArray) -> Unit,
    ) {
        var pos = 0
        while (pos < data.size) {
            val (tag, nextPos) = readVarint64(data, pos)
            pos = nextPos
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07L).toInt()
            if (fieldNumber <= 0) break
            when (wireType) {
                0 -> {
                    val (v, p) = readVarint64(data, pos)
                    pos = p
                    onVarint(fieldNumber, v)
                }
                1 -> {
                    pos = (pos + 8).coerceAtMost(data.size)
                }
                2 -> {
                    val (lenLong, p) = readVarint64(data, pos)
                    val len = lenLong.toInt().coerceAtLeast(0)
                    val end = (p + len).coerceAtMost(data.size)
                    val slice = data.copyOfRange(p, end)
                    pos = end
                    onBytes(fieldNumber, slice)
                }
                5 -> {
                    pos = (pos + 4).coerceAtMost(data.size)
                }
                else -> break
            }
        }
    }

    private fun readVarint64(data: ByteArray, startPos: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = startPos
        while (pos < data.size && shift < 64) {
            val b = data[pos].toInt() and 0xFF
            pos++
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) {
                return result to pos
            }
            shift += 7
        }
        return result to pos
    }
}
