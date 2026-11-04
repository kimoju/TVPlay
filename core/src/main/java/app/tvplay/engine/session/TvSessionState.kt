package app.tvplay.engine.session

import java.io.IOException

typealias ConnectionFailureCause = TvFailureReason
typealias ConnectionFailureException = TvSessionFailureException
typealias CarPlayNowPlaying = TvNowPlayingMetadata

enum class TvFailureReason {
    CHOSEN_IPHONE_NOT_PAIRED,
    NO_PAIRED_IPHONE,
    IPHONE_NOT_CHOSEN,
    WIFI_DIRECT_CHANNEL_UNAVAILABLE,
    LAN_NETWORK_NOT_READY,
    WIFI_DIRECT_START_FAILED,
}

open class TvSessionFailureException(
    val reason: TvFailureReason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    val failureCause: TvFailureReason
        get() = reason
}

data class TvNowPlayingMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val appName: String? = null,
    val appBundleId: String? = null,
    val playbackRate: Double? = null,
    val artworkFileTransferId: Int? = null,
) {
    val isPlaying: Boolean?
        get() = playbackRate?.let { it > 0.0 }
    val playing: Boolean
        get() = isPlaying == true
    val sourceApp: String?
        get() = appName
    val artworkTransferId: Int?
        get() = artworkFileTransferId
}

sealed interface TvSessionState {
    data object Idle : TvSessionState
    data class Searching(val detail: String) : TvSessionState
    data class Starting(val detail: String) : TvSessionState
    data class Authenticated(val detail: String) : TvSessionState
    data class Streaming(val detail: String) : TvSessionState
    data class Failed(
        val step: String,
        val detail: String,
        val cause: TvFailureReason? = null,
    ) : TvSessionState
}
