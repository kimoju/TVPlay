package app.tvplay.engine.network

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import app.tvplay.engine.protocol.iap2.Iap2WirelessSecurity
import app.tvplay.engine.session.TvFailureReason
import app.tvplay.engine.session.TvSessionFailureException
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.InvocationTargetException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

typealias WifiP2pGroupManager = WifiDirectGroupLink

internal fun WifiP2pManager.Channel.closeCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        close()
    }
}

internal fun LocationManager.isLocationEnabledCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        isLocationEnabled
    } else {
        isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

/** A Wi-Fi Direct band choice: the car picks the channel, but only inside [frequenciesMHz]. */
enum class WifiP2pBand(val label: String, val frequenciesMHz: IntRange) {
    TWO_GHZ("2.4 GHz", 2412..2484),
    FIVE_GHZ("5 GHz", 5150..5895);

    fun contains(frequencyMHz: Int?): Boolean = frequencyMHz != null && frequencyMHz in frequenciesMHz
}

/** Non-DFS channel choices. Android still enforces the radio's capabilities and country limits. */
object WifiP2pChannels {
    const val AUTO = 0
    /** Auto inside one band. Negative values never collide with a real channel number. */
    const val AUTO_5_GHZ = -5
    const val AUTO_2_4_GHZ = -2
    val bandChoices: List<Int> = listOf(AUTO_5_GHZ, AUTO_2_4_GHZ)
    val channels: List<Int> = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165) + (1..11)

    fun isValid(channel: Int): Boolean = channel == AUTO || channel in bandChoices || channel in channels

    /** The band an "Auto · band" choice is restricted to, or null for Auto and manual channels. */
    fun band(channel: Int): WifiP2pBand? = when (channel) {
        AUTO_5_GHZ -> WifiP2pBand.FIVE_GHZ
        AUTO_2_4_GHZ -> WifiP2pBand.TWO_GHZ
        else -> null
    }

    fun frequencyMhz(channel: Int): Int? {
        require(isValid(channel)) { "Unsupported Wi-Fi Direct channel: $channel" }
        return when {
            channel == AUTO || channel in bandChoices -> null
            channel <= 11 -> 2407 + channel * 5
            else -> 5000 + channel * 5
        }
    }

    /** Diagnostic and error text; never localized. */
    fun describe(channel: Int): String = when {
        channel == AUTO -> "auto"
        band(channel) != null -> "auto-${requireNotNull(band(channel)).label.replace(" ", "")}"
        else -> channel.toString()
    }
}

internal class P2pChannelUnavailableException(channel: Int, details: String, cause: Throwable? = null) :
    TvSessionFailureException(
        TvFailureReason.WIFI_DIRECT_CHANNEL_UNAVAILABLE,
        WifiP2pChannels.band(channel)?.let { "Wi-Fi Direct could not use the ${it.label} band. Choose Auto or another channel. $details" }
            ?: "Wi-Fi Direct could not use channel $channel. Choose Auto or another channel. $details",
        cause,
    )

internal object WifiP2pLegacyChannels {

    const val LISTEN_CHANNEL = 6

    private const val CALLBACK_TIMEOUT_MILLIS = 2_000L

    fun operatingChannelFor(frequencyMHz: Int): Int? = when {
        frequencyMHz in 5180..5240 || frequencyMHz in 5745..5825 ->
            wifiFrequencyMhzToChannel(frequencyMHz)
        frequencyMHz in 2412..2472 -> wifiFrequencyMhzToChannel(frequencyMHz)?.takeIf { it in 1..13 }
        else -> null
    }

    fun bandLabelFor(channel: Int): String = if (channel in 1..14) "2.4 GHz" else "5 GHz"

    fun apply(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        operatingChannel: Int,
        diagnostic: (String) -> Unit,
    ): Boolean {
        val method = try {
            WifiP2pManager::class.java.getMethod(
                "setWifiP2pChannels",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java,
            )
        } catch (missing: Throwable) {
            diagnostic("Wi-Fi P2P legacy channel selection unavailable failureClass=${failureClass(missing)}")
            return false
        }
        val settled = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                accepted.set(true)
                settled.countDown()
            }

            override fun onFailure(reason: Int) {
                diagnostic("Wi-Fi P2P legacy channel rejected code=$reason requestedChannel=$operatingChannel")
                settled.countDown()
            }
        }
        try {
            method.invoke(manager, channel, LISTEN_CHANNEL, operatingChannel, listener)
        } catch (failure: Throwable) {
            diagnostic("Wi-Fi P2P legacy channel call failed failureClass=${failureClass(failure)}")
            if (failure is InvocationTargetException) {
                throw LegacyChannelOutcomeUnknown("Wi-Fi Direct channel selection failed with an unknown outcome", failure.cause)
            }
            return false
        }
        val completed = try {
            settled.await(CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LegacyChannelOutcomeUnknown("Interrupted while selecting the Wi-Fi Direct channel", interrupted)
        }
        if (!completed) {
            diagnostic("Wi-Fi P2P legacy channel callback timeout requestedChannel=$operatingChannel")
            // The command may already have changed the shared supplicant state. A retry
            // cannot safely treat an unanswered asynchronous request as a rejection.
            throw LegacyChannelOutcomeUnknown("Wi-Fi Direct channel selection did not respond")
        }
        return completed && accepted.get()
    }

    private fun failureClass(failure: Throwable): String = (failure.cause ?: failure).javaClass.simpleName
}

internal class LegacyChannelOutcomeUnknown(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** Reinstall-stable namespace; never treats a generic DIRECT-* name as ours. */
internal object P2pOwnership {
    fun prefix(packageName: String, scopedId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$packageName:$scopedId".toByteArray(Charsets.UTF_8))
        return "DIRECT-dp" + digest.take(6).joinToString("") { "%02x".format(it.toInt() and 255) } + "-"
    }

    fun canReclaim(isOwner: Boolean, ssid: String?, recorded: String?, prefix: String): Boolean =
        isOwner && !ssid.isNullOrBlank() &&
            (ssid == recorded || (ssid.startsWith(prefix) &&
                ssid.removePrefix(prefix).matches(Regex("[A-Za-z0-9]{4}"))))
}

class P2pResetRequiredException : IOException("An existing Wi-Fi Direct connection needs a reset")

internal enum class P2pCreationMode {
    ALIGNED_5_GHZ, ALIGNED_2_GHZ, FIXED_5_GHZ, FIXED_2_GHZ, SYSTEM_DEFAULT, PREFERRED_CHANNEL,
    /** Band-only request (API 29+): the driver picks the channel inside the band. */
    BAND_5_GHZ, BAND_2_GHZ,
}

internal data class P2pCreationRequest(val mode: P2pCreationMode, val frequencyMHz: Int? = null)

/** [replyMillis]: from createGroup to this answer, when known. */
internal class P2pCreateRejected(val reason: Int, message: String, val replyMillis: Long? = null) : IOException(message)

/**
 * Android 13 can ask the driver before Wi-Fi Direct stops another Wi-Fi role, such as the car
 * hotspot, and answers "no" with BUSY. Every further createGroup then asks again: one prompt per
 * channel in the plan and per retry. The prompt waits for a person; a firmware's own per-channel
 * BUSY does not, and keeps walking the plan.
 */
internal object P2pHotspotConflict {
    const val PROMPT_REPLY_MILLIS = 1_000L

    fun failure(sdk: Int, hotspotOn: Boolean?, rejection: P2pCreateRejected): WirelessStartupException? {
        if (sdk < 33 || hotspotOn != true || rejection.reason != WifiP2pManager.BUSY) return null
        if ((rejection.replyMillis ?: 0L) < PROMPT_REPLY_MILLIS) return null
        return WirelessStartupException(
            WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT,
            "Android was not allowed to turn the car hotspot off for Wi-Fi Direct",
        ).apply { initCause(rejection) }
    }
}

/** Raised only for a recognized framework defect before createGroup has been called. */
internal class P2pConfigBuildCompatibilityFailure(cause: NoSuchMethodError) :
    IOException("Wi-Fi P2P custom configuration unavailable on this Android 10 framework", cause)

/** Only a rejection or recognized pre-create defect permits retry; timeout may still create a group. */
internal object P2pStartupRecovery {
    fun rememberedFrequency(frequency: Int): P2pCreationRequest? = when {
        frequency in 2412..2462 && (frequency - 2412) % 5 == 0 ->
            P2pCreationRequest(P2pCreationMode.FIXED_2_GHZ, frequency)
        frequency in listOf(5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825) ->
            P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, frequency)
        else -> null
    }

    /**
     * A band-only request still needs channel selection, which some BYD drivers cannot do. Auto skips
     * 5 GHz when [fiveGhz] is false, so a 2.4 GHz-only head unit is not asked for channel 36 first (#582).
     */
    fun plan(
        stationFrequency: Int?,
        preferred: P2pCreationRequest? = null,
        preferredChannel: Int = WifiP2pChannels.AUTO,
        bandRequests: Boolean = true,
        fiveGhz: Boolean = true,
    ): List<P2pCreationRequest> = buildList {
        WifiP2pChannels.frequencyMhz(preferredChannel)?.let {
            add(P2pCreationRequest(P2pCreationMode.PREFERRED_CHANNEL, it))
            return@buildList
        }
        WifiP2pChannels.band(preferredChannel)?.let {
            addAll(bandPlan(stationFrequency, it, bandRequests))
            return@buildList
        }
        val aligned24 = stationFrequency != null && stationFrequency in 2412..2462 &&
            (stationFrequency - 2412) % 5 == 0
        val aligned5 = stationFrequency in listOf(5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805, 5825)
        val frequencies = mutableSetOf<Int>()
        fun channel(mode: P2pCreationMode, frequency: Int) {
            if (frequencies.add(frequency)) add(P2pCreationRequest(mode, frequency))
        }
        // A station on 5 GHz shares the radio with the group: another 5 GHz channel makes the radio
        // switch between the two, which on a BYD Tang (station 5200, group 5745) stalled video
        // for 0.6-2 s and starved music. A 2.4 GHz group runs alongside without switching.
        val stationOnFiveGhz = stationFrequency != null && stationFrequency in 5150..5895
        val remembered = preferred?.frequencyMHz?.let(::rememberedFrequency)
            ?.takeIf { fiveGhz || it.frequencyMHz!! !in 5150..5895 }
        val rememberedSwitches = stationOnFiveGhz && remembered != null &&
            remembered.frequencyMHz!! in 5150..5895 && remembered.frequencyMHz != stationFrequency
        // The default API does not pin a channel. A cached success cannot prove it will avoid
        // hopping beside the current 5 GHz station, so keep it behind explicit channels there.
        if (preferred?.mode == P2pCreationMode.SYSTEM_DEFAULT && preferred.frequencyMHz == null &&
            !stationOnFiveGhz) add(preferred)
        else if (remembered != null && !rememberedSwitches) channel(remembered.mode, remembered.frequencyMHz!!)
        if (aligned24) channel(P2pCreationMode.ALIGNED_2_GHZ, requireNotNull(stationFrequency))
        if (aligned5) channel(P2pCreationMode.ALIGNED_5_GHZ, requireNotNull(stationFrequency))
        fun twoGhz() = listOf(2437, 2412, 2462).forEach { channel(P2pCreationMode.FIXED_2_GHZ, it) }
        fun fiveGhzChannels() = listOf(5180, 5745).filter { fiveGhz }.forEach { channel(P2pCreationMode.FIXED_5_GHZ, it) }
        // Keep a shared radio on its existing station channel when possible. Otherwise prefer
        // explicit non-DFS 5 GHz, then channels 6/1/11, except next to a 5 GHz station, where
        // 2.4 GHz comes first (see above). The platform enforces regulatory limits.
        if (aligned24 || stationOnFiveGhz) { twoGhz(); fiveGhzChannels() } else { fiveGhzChannels(); twoGhz() }
        if (rememberedSwitches) channel(remembered!!.mode, remembered.frequencyMHz!!)
        // Some vendors only implement the default-configuration API. Use it last, after every
        // explicit-frequency option has been rejected, never before the 2.4 GHz attempts.
        if (none { it.mode == P2pCreationMode.SYSTEM_DEFAULT }) add(P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT))
    }

    /**
     * Auto inside one band: never leaves the band, never uses the remembered Auto result and
     * never ends with the unpinned default, which may pick the other band. The driver chooses
     * first (band request); explicit non-DFS channels follow for drivers that refuse it.
     */
    private fun bandPlan(stationFrequency: Int?, band: WifiP2pBand, bandRequests: Boolean): List<P2pCreationRequest> {
        val five = band == WifiP2pBand.FIVE_GHZ
        val aligned = stationFrequency?.let(::rememberedFrequency)?.takeIf { band.contains(it.frequencyMHz) }
        val fixedMode = if (five) P2pCreationMode.FIXED_5_GHZ else P2pCreationMode.FIXED_2_GHZ
        val fixed = if (five) listOf(5180, 5745, 5220, 5785) else listOf(2437, 2412, 2462)
        return buildList {
            // Same channel as the car's own Wi-Fi network: the shared radio does not hop.
            aligned?.let { add(P2pCreationRequest(if (five) P2pCreationMode.ALIGNED_5_GHZ else P2pCreationMode.ALIGNED_2_GHZ, it.frequencyMHz)) }
            if (bandRequests) add(P2pCreationRequest(if (five) P2pCreationMode.BAND_5_GHZ else P2pCreationMode.BAND_2_GHZ))
            fixed.filter { it != aligned?.frequencyMHz }.forEach { add(P2pCreationRequest(fixedMode, it)) }
        }
    }

    fun create(
        stationFrequency: Int?,
        beforeRetry: () -> Unit,
        preferred: P2pCreationRequest? = null,
        preferredChannel: Int = WifiP2pChannels.AUTO,
        bandRequests: Boolean = true,
        fiveGhz: Boolean = true,
        busyCause: (P2pCreateRejected) -> IOException? = { null },
        request: (P2pCreationRequest) -> Unit,
    ): P2pCreationRequest {
        val modes = plan(stationFrequency, preferred, preferredChannel, bandRequests, fiveGhz)
        var lastRejection: P2pCreateRejected? = null
        for ((index, mode) in modes.withIndex()) {
            var retriedBusy = false
            while (true) {
                try {
                    request(mode)
                    return mode
                } catch (failure: P2pConfigBuildCompatibilityFailure) {
                    if (preferredChannel != WifiP2pChannels.AUTO) {
                        throw P2pChannelUnavailableException(preferredChannel, failure.message.orEmpty(), failure)
                    }
                    if (mode.mode == P2pCreationMode.SYSTEM_DEFAULT) throw failure
                    // No creation request was issued. Keep the existing foreign-group and
                    // prerequisite interlock, then try the API 29 null-config overload once.
                    beforeRetry()
                    val systemDefault = P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT)
                    request(systemDefault)
                    // Its generated credentials must be read from the real returned group.
                    // A failed default request exits directly instead of reentering this plan.
                    return systemDefault
                } catch (failure: P2pCreateRejected) {
                    lastRejection = failure
                    // A BUSY with a known cause that no other channel removes ends the plan here.
                    if (failure.reason == WifiP2pManager.BUSY) busyCause(failure)?.let { throw it }
                    when {
                        // A rejection that is not tied to one channel cannot be fixed by asking
                        // for a different channel, and retrying would only stall the bring-up.
                        failure.reason == WifiP2pManager.NO_PERMISSION ||
                            failure.reason == WifiP2pManager.P2P_UNSUPPORTED -> throw failure
                        failure.reason == WifiP2pManager.BUSY && !retriedBusy -> {
                            retriedBusy = true
                            beforeRetry()
                        }
                        // BUSY is a per-channel outcome, not a verdict on Wi-Fi Direct: several
                        // firmwares reject every 5 GHz request while 2.4 GHz or the system
                        // default configuration still succeeds. Keep walking the plan instead
                        // of giving up on the first channel the driver refuses.
                        index < modes.lastIndex -> {
                            beforeRetry()
                            break
                        }
                        else -> if (preferredChannel != WifiP2pChannels.AUTO) {
                            throw P2pChannelUnavailableException(preferredChannel, failure.message.orEmpty(), failure)
                        } else throw failure
                    }
                }
            }
        }
        throw lastRejection ?: error("No Wi-Fi Direct creation mode attempted")
    }
}

/** Only radio settings are stored: never network credentials, phone identifiers or addresses. */
internal class P2pConfigurationMemory(context: Context) {
    private val prefs = context.getSharedPreferences("carplay_wifi_p2p_success", Context.MODE_PRIVATE)
    data class Record(val encoded: String, val request: P2pCreationRequest, val stationMHz: Int?)

    fun read(): Record? = runCatching {
        val encoded = prefs.getString("confirmed", null) ?: return null
        val fields = encoded.split('|')
        if (fields.size != 5) return null
        val requested = fields[1].toInt()
        val request = when (fields[0]) {
            "system" -> if (requested == 0) P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT) else return null
            "frequency" -> P2pStartupRecovery.rememberedFrequency(requested) ?: return null
            else -> return null
        }
        if (wifiFrequencyMhzToChannel(fields[2].toInt()) == null) return null
        UUID.fromString(fields[4])
        Record(encoded, request, fields[3].toInt().takeIf { it > 0 })
    }.getOrNull()

    fun remember(request: P2pCreationRequest, actualMHz: Int, stationMHz: Int?): Boolean {
        val kind = if (request.mode == P2pCreationMode.SYSTEM_DEFAULT) "system" else "frequency"
        val encoded = "$kind|${request.frequencyMHz ?: 0}|$actualMHz|${stationMHz ?: 0}|${UUID.randomUUID()}"
        return synchronized(prefs) { prefs.edit().putString("confirmed", encoded).commit() }
    }

    /** A late close from an older manager must not erase a newer successful session. */
    fun forget(record: Record): Boolean = runCatching {
        synchronized(prefs) {
            if (prefs.getString("confirmed", null) != record.encoded) false
            else prefs.edit().remove("confirmed").commit()
        }
    }.getOrDefault(false)
}

/** Distinguishes config construction from group creation without reading credentials. */
internal object P2pConfigBuildDiagnostics {
    fun <T> build(
        sdkInt: Int,
        selection: P2pCreationRequest,
        diagnostic: (String) -> Unit,
        create: () -> T,
    ): T {
        emit(diagnostic) { context("begin", sdkInt, selection) }
        try {
            val config = create()
            emit(diagnostic) { context("complete", sdkInt, selection) }
            return config
        } catch (failure: Throwable) {
            emit(diagnostic) { context("failed", sdkInt, selection) + " " + describe(failure) }
            if (isKnownApi29BuilderFailure(sdkInt, failure)) {
                emit(diagnostic) {
                    context("compatibility", sdkInt, selection) + " reason=config_get_network_name fallback=guarded_system_default"
                }
                throw P2pConfigBuildCompatibilityFailure(failure as NoSuchMethodError)
            }
            // Logging failures never replace an unrecognized error or authorize a retry.
            throw failure
        }
    }

    /** Only the reported API 29 builder defect is safe to classify before createGroup. */
    internal fun isKnownApi29BuilderFailure(sdkInt: Int, error: Throwable): Boolean {
        if (sdkInt != 29 || error !is NoSuchMethodError) return false
        return runCatching {
            val message = error.message ?: return@runCatching false
            // Match the actual #15 ART error, including the return type and terminating
            // class descriptor. A similarly named method or class is a different failure.
            message.startsWith(
                "No virtual method getNetworkName()Ljava/lang/String; in class Landroid/net/wifi/p2p/WifiP2pConfig;",
            ) &&
                error.stackTrace.firstOrNull()?.className == "android.net.wifi.p2p.WifiP2pConfig\$Builder"
        }.getOrDefault(false)
    }

    private fun emit(diagnostic: (String) -> Unit, message: () -> String) {
        try {
            diagnostic(message())
        } catch (_: Throwable) {
            // Diagnostics are optional, including when firmware metadata access is broken.
        }
    }

    private fun context(stage: String, sdkInt: Int, selection: P2pCreationRequest): String =
        "P2P_CONFIG_DIAGNOSTIC stage=$stage api=$sdkInt mode=${selection.mode} " +
            "frequencyMHz=${selection.frequencyMHz ?: "unknown"}"

    private fun describe(error: Throwable): String {
        val failures = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (failures.size < 3) {
            val next = current ?: break
            if (failures.any { it === next }) break
            failures += next
            current = next.cause
        }
        val classes = failures.joinToString("/") { identifier(it.javaClass.simpleName, 64) }
        // One relevant location is sufficient; never export a stack or source filename.
        val frame = failures.asSequence().flatMap { it.stackTrace.asSequence() }.firstOrNull {
            it.className.startsWith("android.net.wifi.p2p.") ||
                it.className.startsWith("app.tvplay.network.") ||
                it.className.startsWith("app.tvplay.engine.network.")
        }
        val location = frame?.let {
            "${identifier(it.className, 128)}.${identifier(it.methodName, 64)}:${it.lineNumber}"
        } ?: "unavailable"
        return "failureClass=${identifier(error.javaClass.simpleName, 64)} causes=$classes at=$location"
    }

    private fun identifier(value: String, limit: Int): String =
        value.take(limit).filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '$' }
            .ifEmpty { "unknown" }
}

internal object HotspotAddressPolicy {
    const val FAMILY_IPV4 = "IPv4"
    const val FAMILY_IPV6 = "IPv6"

    fun parseFamily(raw: String?): String? = when (raw) {
        FAMILY_IPV4 -> FAMILY_IPV4
        FAMILY_IPV6 -> FAMILY_IPV6
        else -> null
    }

    fun flipFamily(family: String): String = if (family == FAMILY_IPV6) FAMILY_IPV4 else FAMILY_IPV6

    fun select(addresses: List<InetAddress>): InetAddress? {
        var linkLocal: InetAddress? = null
        for (address in addresses) {
            if (address is Inet4Address && usable(address)) {
                return address
            }
            if (address is Inet6Address && address.isLinkLocalAddress && linkLocal == null) {
                linkLocal = address
            }
        }
        return linkLocal
    }

    fun select(addresses: List<InetAddress>, preferredFamily: String?): InetAddress? {
        if (preferredFamily != FAMILY_IPV6) {
            return select(addresses)
        }
        for (address in addresses) {
            if (address is Inet6Address && address.isLinkLocalAddress) {
                return address
            }
        }
        return select(addresses)
    }

    fun alternateFamilyCandidate(addresses: List<InetAddress>, published: InetAddress): InetAddress? {
        val publishedIsIpv6 = published is Inet6Address
        var linkLocal: InetAddress? = null
        for (address in addresses) {
            if (address is Inet4Address && usable(address) && publishedIsIpv6) {
                return address
            }
            if (address is Inet6Address && address.isLinkLocalAddress && !publishedIsIpv6 && linkLocal == null) {
                linkLocal = address
            }
        }
        return if (publishedIsIpv6) null else linkLocal
    }

    private fun usable(address: InetAddress): Boolean =
        !address.isLoopbackAddress && !address.isAnyLocalAddress && !address.isLinkLocalAddress &&
            !address.isMulticastAddress
}

/** Addresses can appear after the framework reports a newly created group ready. */
internal class HotspotAddressRecovery(
    private val sample: () -> List<InetAddress>,
    private val cancelled: () -> Boolean,
    private val pause: (Long) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) {
    fun awaitCandidates(
        initial: List<InetAddress>,
        preferredFamily: String?,
        timeoutMillis: Long = WirelessStartupPolicy.HOTSPOT_READY_MILLIS,
    ): List<InetAddress> {
        require(timeoutMillis >= 0)
        val started = nowMillis()
        val deadline = started + timeoutMillis
        var candidates = initial
        if (preferredFamily == HotspotAddressPolicy.FAMILY_IPV6 && !candidates.any(::scopedLinkLocal)) {
            log("wireless address waiting preference=IPv6 timeoutMs=$timeoutMillis")
        }
        while (true) {
            checkActive()
            if (preferredFamily != HotspotAddressPolicy.FAMILY_IPV6) return candidates
            if (candidates.any(::scopedLinkLocal)) {
                log("wireless address preference=IPv6 ready elapsedMs=${nowMillis() - started}")
                return candidates.filter { it !is Inet6Address || !it.isLinkLocalAddress || scopedLinkLocal(it) }
            }
            val remaining = deadline - nowMillis()
            if (remaining <= 0) {
                log("wireless address preference=IPv6 unavailable elapsedMs=${nowMillis() - started}; using available family")
                return candidates
            }
            pause(minOf(WirelessStartupPolicy.INTERFACE_POLL_MILLIS, remaining))
            checkActive()
            candidates = sample()
            checkActive()
        }
    }

    /** Re-sample at timeout; a missing alternate at group creation is not a single-family AP. */
    fun nextFamily(published: InetAddress): String? {
        if (cancelled()) return null
        val candidates = sample().filter { it !is Inet6Address || scopedLinkLocal(it) }
        if (cancelled()) return null
        if (HotspotAddressPolicy.alternateFamilyCandidate(candidates, published) == null) return null
        return HotspotAddressPolicy.flipFamily(
            if (published is Inet6Address) HotspotAddressPolicy.FAMILY_IPV6 else HotspotAddressPolicy.FAMILY_IPV4,
        )
    }

    private fun checkActive() {
        if (cancelled()) throw InterruptedIOException("Hotspot address recovery cancelled")
    }

    private fun scopedLinkLocal(address: InetAddress): Boolean =
        address is Inet6Address && address.isLinkLocalAddress && address.scopeId != 0
}

/**
 * Creates a temporary Wi-Fi Direct group owner, preferring 5 GHz, that can also be joined as a legacy AP.
 *
 * Android 10+ creates a temporary group. Android 9's public overload can create or reuse a
 * persistent system profile; [close] removes the active group without deleting saved profiles.
 */
class WifiDirectGroupLink(
    context: Context,
    private val diagnostic: (String) -> Unit = {},
    private val preferredChannel: Int = WifiP2pChannels.AUTO,
) : WirelessHotspotManager {
    init {
        require(WifiP2pChannels.isValid(preferredChannel)) { "Unsupported Wi-Fi Direct channel: $preferredChannel" }
    }
    private val appContext = context.applicationContext
    private val p2pManager = appContext.getSystemService(WifiP2pManager::class.java)
        ?: throw IllegalStateException("WifiP2pManager is unavailable")
    private val stateLock = Object()
    // The legacy channel API changes shared supplicant state, rather than this Channel object.
    // Keep selection, compensation and Channel.close ordered even when startup is cancelled.
    private val legacyChannelLock = Any()
    private var legacyRestrictionOwned = false
    private val random = SecureRandom()
    private val configurationMemory = P2pConfigurationMemory(appContext)
    private var rememberedAttempt: P2pConfigurationMemory.Record? = null
    private var pendingSuccess: (() -> Boolean)? = null
    private var carPlayConfirmed = false
    private val ownership = appContext.getSharedPreferences("carplay_wifi_p2p", Context.MODE_PRIVATE)
    private val ssidPrefix by lazy {
        // ANDROID_ID is scoped to the signing key, user and device on our supported OS versions.
        val id = runCatching { Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: ownership.getString("fallback_id", null)
            ?: UUID.randomUUID().toString().also {
                check(ownership.edit().putString("fallback_id", it).commit())
            }
        P2pOwnership.prefix(appContext.packageName, id)
    }

    private var channel: WifiP2pManager.Channel? = null
    private var callbackThread: HandlerThread? = null
    private var created = false
    private var closed = false
    private var startAttempt: StartAttempt? = null
    @Volatile private var observedCreatedName: String? = null
    @Volatile private var requestedName: String? = null

    override fun connectionDiagnosticSnapshot(): String {
        val current = synchronized(stateLock) { if (closed) null else channel }
            ?: return "p2pGroup=unavailable association=unknown"
        val result = AtomicReference<WifiP2pGroup?>()
        val latch = CountDownLatch(1)
        return try {
            p2pManager.requestGroupInfo(current) { result.set(it); latch.countDown() }
            if (!latch.await(500, TimeUnit.MILLISECONDS)) {
                "p2pGroup=callback_timeout association=unknown"
            } else {
                val group = result.get()
                if (group == null) "p2pGroup=absent association=unknown"
                else "p2pGroup=present owner=${group.isGroupOwner} " +
                    "sameGroup=${group.networkName == observedCreatedName} " +
                    "reportedP2pClients=${group.clientList.size} association=unknown legacyClients=not_exposed"
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            "p2pGroup=interrupted association=unknown"
        } catch (error: RuntimeException) {
            "p2pGroup=unavailable failureClass=${error.javaClass.simpleName} association=unknown"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        val legacyChannels = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        if (legacyChannels) {
            diagnostic("Wi-Fi P2P legacy channel API sdk=${Build.VERSION.SDK_INT}")
        }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "WifiP2pGroupManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val attempt = StartAttempt()
        synchronized(stateLock) {
            check(!closed) { "WifiP2pGroupManager is closed" }
            check(startAttempt == null && !created) {
                "A Wi-Fi P2P group is already starting or active"
            }
            startAttempt = attempt
        }

        val thread = HandlerThread("xcertplay-wifi-p2p").apply { start() }
        attempt.thread = thread
        val deadlineNanos = deadlineAfter(timeoutMillis)
        val credentials = randomCredentials()

        try {
            val station = readStation()
            val stationFrequency = station.alignmentFrequency
            val fiveGhz = checkPrerequisites(station)
            if (!fiveGhz && preferredChannel == WifiP2pChannels.AUTO) {
                diagnostic("Wi-Fi P2P 5 GHz skipped: the Wi-Fi chip reports no 5 GHz band")
            }
            val remembered = configurationMemory.read()
            val preferred = remembered?.takeIf {
                preferredChannel == WifiP2pChannels.AUTO && it.stationMHz == stationFrequency
            }
            diagnostic("Wi-Fi P2P channel preference=${WifiP2pChannels.describe(preferredChannel)} " +
                "frequencyMHz=${WifiP2pChannels.frequencyMhz(preferredChannel) ?: "auto"}")
            diagnostic(when {
                WifiP2pChannels.band(preferredChannel) != null -> "Wi-Fi P2P remembered skipped=band_preference"
                preferredChannel != WifiP2pChannels.AUTO -> "Wi-Fi P2P remembered skipped=manual_channel"
                preferred != null -> {
                    val position = if (P2pStartupRecovery.plan(stationFrequency, preferred.request).first() == preferred.request)
                        "first" else "deferred"
                    "Wi-Fi P2P remembered $position mode=${preferred.request.mode} frequencyMHz=${preferred.request.frequencyMHz ?: "auto"}"
                }
                remembered != null -> "Wi-Fi P2P remembered skipped=station_channel_changed"
                else -> "Wi-Fi P2P remembered unavailable"
            })
            val p2pChannel = p2pManager.initialize(
                appContext,
                thread.looper,
                createChannelListener(attempt),
            )
            attempt.channel = p2pChannel
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                channel = p2pChannel
                callbackThread = thread
            }

            if (!legacyChannels) logP2pState(attempt, p2pChannel)
            // Preferences disappear on reinstall, but the scoped namespace survives.
            val existing = requestGroupInfo(attempt, p2pChannel, REQUEST_POLL_NANOS, requireResponse = true)
            diagnostic("Wi-Fi P2P existingGroup=${existing != null}")
            if (existing != null) {
                if (!P2pOwnership.canReclaim(existing.isGroupOwner, existing.networkName,
                        ownership.getString("owned_ssid", null), ssidPrefix)) {
                    throw P2pResetRequiredException()
                }
                diagnostic("Wi-Fi P2P reclaiming retained owned group")
                val reclaimStarted = System.nanoTime()
                removeGroupBlocking(p2pChannel, existing.networkName)
                val removalDeadline = minOf(deadlineNanos, deadlineAfter(REMOVE_GROUP_TIMEOUT_MILLIS))
                // The framework can hold group info back until the old interface is down: about
                // 0.6 s on a Galaxy Note20 (Android 13), longer than one poll. Wait up to the
                // removal deadline for each answer instead of calling the wait a failure.
                while (requestGroupInfo(attempt, p2pChannel, remainingNanos(removalDeadline), requireResponse = true) != null) {
                    if (remainingNanos(removalDeadline) == 0L) throw IOException("Wi-Fi Direct reset did not finish")
                    synchronized(stateLock) { waitNanos(TimeUnit.MILLISECONDS.toNanos(100)) }
                }
                diagnostic("Wi-Fi P2P retained group removed afterMs=${(System.nanoTime() - reclaimStarted) / NANOS_PER_MILLISECOND}")
            }

            val creation = P2pStartupRecovery.create(
                stationFrequency = stationFrequency,
                preferred = preferred?.request,
                preferredChannel = preferredChannel,
                // Band-only requests need the API 29 WifiP2pConfig; the legacy path pins channels.
                bandRequests = !legacyChannels,
                fiveGhz = fiveGhz,
                beforeRetry = {
                    ensureStartActive(attempt)
                    // Do not cancel discovery, toggle Wi-Fi, or remove a newly observed group.
                    // A competing app may have acquired the radio since our rejected request.
                    synchronized(stateLock) { waitNanos(TimeUnit.MILLISECONDS.toNanos(500)) }
                    ensureStartActive(attempt)
                    checkPrerequisites(readStation())
                    if (requestGroupInfo(attempt, p2pChannel, REQUEST_POLL_NANOS, requireResponse = true) != null) {
                        throw P2pResetRequiredException()
                    }
                },
                busyCause = { rejection ->
                    P2pHotspotConflict.failure(Build.VERSION.SDK_INT, null, rejection)
                },
                request = { selection ->
                    ensureStartActive(attempt)
                    if (remainingNanos(deadlineNanos) == 0L) throw IOException("Wi-Fi Direct startup timed out")
                    if (legacyChannels) requestLegacyOperatingChannel(attempt, p2pChannel, selection)
                    val config = if (legacyChannels || selection.mode == P2pCreationMode.SYSTEM_DEFAULT) null else {
                        P2pConfigBuildDiagnostics.build(Build.VERSION.SDK_INT, selection, diagnostic) {
                            val builder = WifiP2pConfig.Builder()
                                .setNetworkName(credentials.ssid)
                                .setPassphrase(credentials.passphrase)
                            when (selection.mode) {
                                P2pCreationMode.BAND_5_GHZ -> builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                                P2pCreationMode.BAND_2_GHZ -> builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                                else -> builder.setGroupOperatingFrequency(requireNotNull(selection.frequencyMHz))
                            }
                            builder.build()
                        }
                    }
                    if (config != null && !ownership.edit().putString("owned_ssid", credentials.ssid).commit()) {
                        throw IOException("Could not record Wi-Fi P2P group ownership")
                    }
                    val request = CreateRequest()
                    // Keep the requested SSID from the value we supplied. Some Android 10
                    // vendor frameworks omit WifiP2pConfig.getNetworkName(), even though they
                    // support the API 29 Builder methods used above. Reading config.networkName
                    // therefore throws NoSuchMethodError on those devices.
                    requestedName = if (config == null) null else credentials.ssid
                    synchronized(stateLock) {
                        ensureStartActiveLocked(attempt)
                        attempt.request = request
                    }
                    diagnostic("Wi-Fi P2P create mode=${selection.mode} frequencyMHz=${selection.frequencyMHz ?: "auto"}")
                    // The API 29 overload with null config uses system-generated credentials
                    // without requesting a persistent group, unlike the older two-argument API.
                    val usingRemembered = preferred?.request == selection
                    if (usingRemembered) synchronized(stateLock) { rememberedAttempt = preferred }
                    try {
                        if (legacyChannels) {
                            p2pManager.createGroup(p2pChannel, createActionListener(attempt, request))
                        } else {
                            p2pManager.createGroup(p2pChannel, config, createActionListener(attempt, request))
                        }
                        awaitGroupCreated(attempt, request, deadlineNanos, timeoutMillis)
                    } catch (failure: P2pCreateRejected) {
                        if (usingRemembered && failure.reason == WifiP2pManager.ERROR && preferred != null) {
                            if (configurationMemory.forget(preferred)) diagnostic("Wi-Fi P2P remembered cleared=create_rejected")
                        }
                        throw failure
                    }
                },
            )
            val group = if (legacyChannels) {
                awaitUsableGroupLegacy(attempt, p2pChannel, creation, deadlineNanos, timeoutMillis)
            } else {
                awaitUsableGroup(
                    attempt = attempt,
                    channel = p2pChannel,
                    credentials = if (creation.mode == P2pCreationMode.SYSTEM_DEFAULT) null else credentials,
                    deadlineNanos = deadlineNanos,
                    timeoutMillis = timeoutMillis,
                )
            }
            if (!ownership.edit().putString("owned_ssid", group.ssid).commit()) {
                throw IOException("Could not record Wi-Fi P2P group ownership")
            }
            diagnostic("Wi-Fi P2P ready mode=${creation.mode} band=${group.bandLabel} channel=${group.channel} " +
                "frequencyMHz=${group.frequencyMHz} verified=${!legacyChannels}")
            if (legacyChannels) {
                diagnostic("Wi-Fi P2P channel requestedMHz=${creation.frequencyMHz ?: "auto"} " +
                    "actualMHz=unavailable matched=unverified")
            } else {
                diagnostic("Wi-Fi P2P channel requestedMHz=${creation.frequencyMHz ?: "auto"} actualMHz=${group.frequencyMHz} matched=${creation.frequencyMHz?.let { it == group.frequencyMHz } ?: "system_selected"}")
                val band = WifiP2pChannels.band(preferredChannel)
                if (band != null) {
                    // Any channel inside the chosen band is acceptable; the other band is not.
                    if (!band.contains(group.frequencyMHz)) {
                        throw P2pChannelUnavailableException(preferredChannel,
                            "The car selected channel ${group.channel} (${group.bandLabel}) instead.")
                    }
                } else if (preferredChannel != WifiP2pChannels.AUTO && group.frequencyMHz != creation.frequencyMHz) {
                    throw P2pChannelUnavailableException(preferredChannel,
                        "The car selected channel ${group.channel} instead.")
                }
            }
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                created = true
                startAttempt = null
                // Manual experiments must not replace the proven automatic configuration.
                pendingSuccess = if (!legacyChannels && preferredChannel == WifiP2pChannels.AUTO) {
                    {
                        configurationMemory.remember(creation, requireNotNull(group.frequencyMHz), stationFrequency)
                    }
                } else null
            }
            return group
        } catch (failure: Exception) {
            diagnostic("Wi-Fi P2P startup stopped type=${failure.javaClass.simpleName}")
            cleanupFailedStart(attempt)
            throw failure
        }
    }

    override fun onCarPlayConfirmed() = synchronized(stateLock) {
        if (closed || !created || carPlayConfirmed) return@synchronized
        val save = pendingSuccess ?: return@synchronized
        // Storage failure must not interrupt a working CarPlay session.
        carPlayConfirmed = true
        val saved = runCatching { save() }.getOrDefault(false)
        diagnostic("Wi-Fi P2P remembered saved=$saved proof=authenticated_first_frame")
    }

    override fun close() {
        val attempt: StartAttempt?
        val activeChannel: WifiP2pManager.Channel?
        val activeThread: HandlerThread?
        val removeGroup: Boolean
        synchronized(stateLock) {
            if (closed) return
            closed = true
            if (!carPlayConfirmed) rememberedAttempt?.let {
                if (configurationMemory.forget(it)) diagnostic("Wi-Fi P2P remembered cleared=session_unconfirmed")
            }
            pendingSuccess = null
            attempt = startAttempt
            attempt?.stopped = true
            stateLock.notifyAll()
            activeChannel = channel ?: attempt?.channel
            activeThread = callbackThread ?: attempt?.thread
            removeGroup = created || attempt?.createSucceeded == true
            channel = null
            callbackThread = null
            startAttempt = null
        }

        if (removeGroup && activeChannel != null) {
            removeGroupBlocking(activeChannel)
        }
        synchronized(legacyChannelLock) {
            if (activeChannel != null) releaseLegacyChannelRestriction(activeChannel)
            activeChannel?.closeCompat()
        }
        activeThread?.quitSafely()
    }

    private fun createChannelListener(
        attempt: StartAttempt,
    ): WifiP2pManager.ChannelListener = object : WifiP2pManager.ChannelListener {
        override fun onChannelDisconnected() {
            failAttempt(attempt, IOException("Wi-Fi P2P channel disconnected"))
        }
    }

    private fun createActionListener(
        attempt: StartAttempt,
        request: CreateRequest,
    ): WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            val activeChannel = attempt.channel
            val removeDetachedGroup = synchronized(stateLock) {
                if (request.completed) return
                request.completed = true
                attempt.createSucceeded = true
                if (startAttempt === attempt && attempt.request === request && !attempt.stopped && !closed) {
                    created = true
                    stateLock.notifyAll()
                    false
                } else true
            }
            if (removeDetachedGroup && activeChannel != null) {
                removeGroup(activeChannel, waitForCallback = false)
            }
        }

        override fun onFailure(reason: Int) {
            val replyMillis = (System.nanoTime() - request.createdNanos) / NANOS_PER_MILLISECOND
            synchronized(stateLock) {
                if (request.completed || attempt.request !== request) return
                request.completed = true
                request.failure = P2pCreateRejected(reason,
                    "Wi-Fi P2P createGroup failed: ${failureReason(reason)} (code=$reason)", replyMillis)
                stateLock.notifyAll()
            }
            diagnostic("Wi-Fi P2P create rejected code=$reason reason=${failureReason(reason)} replyMs=$replyMillis")
        }
    }

    private fun failAttempt(attempt: StartAttempt, failure: IOException) {
        synchronized(stateLock) {
            if (startAttempt === attempt && !attempt.stopped && !closed) {
                if (attempt.failure == null) attempt.failure = failure
                stateLock.notifyAll()
            }
        }
    }

    private fun requestLegacyOperatingChannel(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        selection: P2pCreationRequest,
    ) {
        synchronized(legacyChannelLock) {
            ensureStartActive(attempt)
            val requested = selection.frequencyMHz
            if (requested == null) {
                // A failed creation can leave our successful pin behind. Default creation
                // must not inherit it, or reset a restriction this manager never applied.
                if (legacyRestrictionOwned && !clearLegacyChannelRestriction(channel)) {
                    throw IOException("Wi-Fi Direct could not clear its previous channel restriction")
                }
                return
            }
            val operatingChannel = WifiP2pLegacyChannels.operatingChannelFor(requested)
                ?: throw P2pCreateRejected(
                    WifiP2pManager.ERROR,
                    "Wi-Fi Direct cannot request ${requested}MHz on this Android release",
                )
            val accepted = try {
                WifiP2pLegacyChannels.apply(p2pManager, channel, operatingChannel, diagnostic)
            } catch (unknown: LegacyChannelOutcomeUnknown) {
                legacyRestrictionOwned = true
                throw unknown
            }
            if (!accepted) {
                throw P2pCreateRejected(
                    WifiP2pManager.ERROR,
                    "Wi-Fi Direct could not pin channel $operatingChannel on this Android release",
                )
            }
            legacyRestrictionOwned = true
            ensureStartActive(attempt)
            diagnostic("Wi-Fi P2P legacy channel applied channel=$operatingChannel frequencyMHz=$requested")
        }
    }

    /** Called with [legacyChannelLock] held before closing the callback channel. */
    private fun releaseLegacyChannelRestriction(channel: WifiP2pManager.Channel) {
        if (!legacyRestrictionOwned) return
        // Startup interruption must not prevent compensation of its submitted command.
        // Restore the caller's interrupt status after the bounded cleanup attempt.
        val wasInterrupted = Thread.interrupted()
        val result = AtomicReference<WifiP2pGroup?>()
        val reply = CountDownLatch(1)
        try {
            p2pManager.requestGroupInfo(channel) { result.set(it); reply.countDown() }
            if (!await(reply, REQUEST_POLL_NANOS)) {
                diagnostic("Wi-Fi P2P legacy channel cleanup skipped=group_unknown")
                return
            }
            val current = result.get()
            val expectedName = observedCreatedName ?: requestedName
            if (current != null && (!current.isGroupOwner || expectedName.isNullOrBlank() ||
                    current.networkName != expectedName)) {
                diagnostic("Wi-Fi P2P legacy channel cleanup skipped=another_app_owns_group")
                return
            }
            clearLegacyChannelRestriction(channel)
        } catch (failure: Exception) {
            diagnostic("Wi-Fi P2P legacy channel cleanup failed failureClass=${failure.javaClass.simpleName}")
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun clearLegacyChannelRestriction(channel: WifiP2pManager.Channel): Boolean {
        val cleared = WifiP2pLegacyChannels.apply(p2pManager, channel, 0, diagnostic)
        if (cleared) legacyRestrictionOwned = false
        diagnostic("Wi-Fi P2P legacy channel restriction cleared=$cleared")
        return cleared
    }

    private fun awaitGroupCreated(
        attempt: StartAttempt,
        request: CreateRequest,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ) {
        synchronized(stateLock) {
            while (true) {
                ensureStartActiveLocked(attempt)
                attempt.failure?.let { throw it }
                request.failure?.let { throw it }
                if (attempt.createSucceeded) return

                val remainingNanos = remainingNanos(deadlineNanos)
                if (remainingNanos <= 0) {
                    throw IOException(
                        "Timed out after ${timeoutMillis}ms waiting for Wi-Fi P2P group creation",
                    )
                }
                waitNanos(remainingNanos)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun awaitUsableGroup(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        credentials: Credentials?,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ): WirelessHotspotInfo {
        var lastReason = "group information was not available"
        while (true) {
            ensureStartActive(attempt)
            val remainingNanos = remainingNanos(deadlineNanos)
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for a usable Wi-Fi P2P group: " +
                        lastReason,
                )
            }

            val group = requestGroupInfo(
                attempt = attempt,
                channel = channel,
                timeoutNanos = minOf(remainingNanos, REQUEST_POLL_NANOS),
            )
            if (group == null) continue
            if (!group.isGroupOwner) {
                throw IOException("Wi-Fi P2P device became a group client instead of owner")
            }

            val networkName = group.networkName?.takeIf { it.isNotBlank() }
            if (credentials != null && networkName != null && networkName != credentials.ssid) {
                throw IOException("Wi-Fi Direct returned an unexpected group")
            }
            val passphrase = group.passphrase?.takeIf { it.isNotBlank() }
                ?: credentials?.passphrase
            val interfaceName = group.getInterface()?.takeIf { it.isNotBlank() }
            val frequencyMHz = group.frequency
            val channelNumber = wifiFrequencyMhzToChannel(frequencyMHz)
            if (
                networkName == null || passphrase == null ||
                interfaceName == null ||
                frequencyMHz <= 0 ||
                channelNumber == null
            ) {
                lastReason = "incomplete group details frequencyMHz=$frequencyMHz"
                continue
            }
            val band = when {
                is5Ghz(frequencyMHz) -> "5 GHz"
                frequencyMHz in 2412..2484 -> "2.4 GHz"
                else -> throw IOException("Wi-Fi P2P returned an unsupported band at ${frequencyMHz}MHz")
            }

            val hostAddress = awaitInterfaceAddress(attempt, interfaceName, deadlineNanos)
                ?: requestConnectionAddress(
                    attempt = attempt,
                    channel = channel,
                    timeoutNanos = minOf(remainingNanos(deadlineNanos), REQUEST_POLL_NANOS),
                )
            if (hostAddress == null) {
                lastReason = "interface $interfaceName has no usable IPv6 or IPv4 address"
                continue
            }

            return WirelessHotspotInfo(
                ssid = networkName,
                passphrase = passphrase,
                security = groupSecurity(group),
                channel = channelNumber,
                frequencyMHz = frequencyMHz,
                bssid = interfaceHardwareAddress(interfaceName)
                    ?: group.owner?.deviceAddress?.takeIf { it.isNotBlank() },
                interfaceName = interfaceName,
                hostAddress = hostAddress,
                bandLabel = band,
                backend = WirelessHotspotBackend.WIFI_P2P,
            )
        }
    }

    private fun awaitUsableGroupLegacy(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        creation: P2pCreationRequest,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ): WirelessHotspotInfo {
        var lastReason = "group information was not available"
        while (true) {
            ensureStartActive(attempt)
            val remainingNanos = remainingNanos(deadlineNanos)
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for a usable Wi-Fi P2P group: " +
                        lastReason,
                )
            }

            val group = requestGroupInfo(
                attempt = attempt,
                channel = channel,
                timeoutNanos = minOf(remainingNanos, REQUEST_POLL_NANOS),
            )
            if (group == null) continue
            if (!group.isGroupOwner) {
                throw IOException("Wi-Fi P2P device became a group client instead of owner")
            }

            val networkName = group.networkName?.takeIf { it.isNotBlank() }
            val passphrase = group.passphrase?.takeIf { it.isNotBlank() }
            val interfaceName = legacyGroupInterface(group)
            if (networkName == null || passphrase == null || interfaceName == null) {
                lastReason = "incomplete group details ssid=${networkName != null} " +
                    "passphrase=${passphrase != null} interface=${interfaceName != null}"
                continue
            }

            val hostAddress = awaitInterfaceAddress(attempt, interfaceName, deadlineNanos)
                ?: requestConnectionAddress(
                    attempt = attempt,
                    channel = channel,
                    timeoutNanos = minOf(remainingNanos(deadlineNanos), REQUEST_POLL_NANOS),
                )
            if (hostAddress == null) {
                lastReason = "interface $interfaceName has no usable IPv6 or IPv4 address"
                continue
            }

            val requestedChannel = creation.frequencyMHz
                ?.let(WifiP2pLegacyChannels::operatingChannelFor) ?: UNKNOWN_CHANNEL
            return WirelessHotspotInfo(
                ssid = networkName,
                passphrase = passphrase,
                security = groupSecurity(group),
                channel = requestedChannel,
                frequencyMHz = creation.frequencyMHz,
                bssid = interfaceHardwareAddress(interfaceName)
                    ?: group.owner?.deviceAddress?.takeIf { it.isNotBlank() },
                interfaceName = interfaceName,
                hostAddress = hostAddress,
                bandLabel = if (requestedChannel == UNKNOWN_CHANNEL) "Auto"
                    else WifiP2pLegacyChannels.bandLabelFor(requestedChannel),
                backend = WirelessHotspotBackend.WIFI_P2P,
            )
        }
    }

    // WifiP2pGroup.getInterface is available on Android 9. An arbitrary p2p interface
    // can belong to a different group, so missing exact group identity must fail closed.
    private fun legacyGroupInterface(group: WifiP2pGroup): String? =
        runCatching { group.getInterface()?.takeIf { it.isNotBlank() } }.getOrNull()

    private fun requestGroupInfo(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        timeoutNanos: Long,
        requireResponse: Boolean = false,
    ): WifiP2pGroup? {
        val result = AtomicReference<WifiP2pGroup?>()
        val latch = CountDownLatch(1)
        p2pManager.requestGroupInfo(channel) {
            // Keep the first identity returned after our successful creation. A later global
            // broadcast may describe a replacement group belonging to another app.
            if (attempt.createSucceeded && observedCreatedName == null && it?.isGroupOwner == true &&
                (requestedName == null || it.networkName == requestedName)) {
                observedCreatedName = it.networkName
            }
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, timeoutNanos)) {
            if (requireResponse) throw IOException("Wi-Fi Direct did not respond")
            return null
        }
        ensureStartActive(attempt)
        return result.get()
    }

    private fun requestConnectionAddress(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        timeoutNanos: Long,
    ): InetAddress? {
        val result = AtomicReference<WifiP2pInfo?>()
        val latch = CountDownLatch(1)
        p2pManager.requestConnectionInfo(channel) {
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, timeoutNanos)) return null
        ensureStartActive(attempt)
        val info = result.get() ?: return null
        if (!info.groupFormed) return null
        return info.groupOwnerAddress?.takeUnless(InetAddress::isAnyLocalAddress)
    }

    private fun await(latch: CountDownLatch, timeoutNanos: Long): Boolean = try {
        val waitNanos = timeoutNanos.coerceAtLeast(1L)
        latch.await(waitNanos, TimeUnit.NANOSECONDS)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while waiting for Wi-Fi P2P", interrupted)
    }

    private fun interfaceAddress(interfaceName: String): InetAddress? {
        val networkInterface = networkInterface(interfaceName) ?: return null
        val address = HotspotAddressPolicy.select(Collections.list(networkInterface.inetAddresses))
            ?: return null
        if (address !is Inet6Address || address.scopeId == networkInterface.index) return address
        return runCatching { Inet6Address.getByAddress(null, address.address, networkInterface) }
            .getOrDefault(address)
    }

    private fun awaitInterfaceAddress(
        attempt: StartAttempt,
        interfaceName: String,
        startupDeadlineNanos: Long,
    ): InetAddress? {
        val addressDeadline = minOf(startupDeadlineNanos, deadlineAfter(2_000))
        while (true) {
            ensureStartActive(attempt)
            val address = interfaceAddress(interfaceName)
            if (address is Inet4Address) return address
            // Publish only the current interface snapshot. A link-local address seen earlier
            // may disappear while DHCP/group configuration is still changing.
            if (remainingNanos(addressDeadline) <= 0) return address
            Thread.sleep(100)
        }
    }

    private data class Station(val state: SupplicantState?, val reportedFrequency: Int?) {
        // A vendor may retain the last frequency after disconnecting. Only align to an
        // established Wi-Fi association; internet validation is deliberately irrelevant.
        val alignmentFrequency: Int?
            get() = reportedFrequency?.takeIf { state == SupplicantState.COMPLETED && it > 0 }
    }

    @Suppress("DEPRECATION")
    private fun readStation(): Station = runCatching {
        val info = appContext.getSystemService(WifiManager::class.java)?.connectionInfo
        Station(info?.supplicantState, info?.frequency?.takeIf { it > 0 })
    }.getOrDefault(Station(null, null))

    /** Returns false only when Wi-Fi is on and reports no 5 GHz band; it also reads false while Wi-Fi is off. */
    private fun checkPrerequisites(station: Station): Boolean {
        val wifi = appContext.getSystemService(WifiManager::class.java)
        val fiveGhzSupported = runCatching { wifi?.is5GHzBandSupported }.getOrNull()
        val wifiEnabled = runCatching { wifi?.isWifiEnabled }.getOrNull()
        val locationEnabled = runCatching {
            appContext.getSystemService(LocationManager::class.java)?.isLocationEnabledCompat()
        }.getOrNull()
        val required = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
        val granted = appContext.checkSelfPermission(required) == PackageManager.PERMISSION_GRANTED
        val locationAccessMode = if (Build.VERSION.SDK_INT in 29..32) runCatching {
            appContext.getSystemService(AppOpsManager::class.java)?.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_FINE_LOCATION, android.os.Process.myUid(), appContext.packageName)
        }.getOrNull() else null
        diagnostic("Wi-Fi P2P preflight wifiEnabled=$wifiEnabled locationEnabled=$locationEnabled permissionGranted=$granted locationAccessMode=${locationAccessMode ?: "unknown"} stationMHz=${station.alignmentFrequency ?: "unknown"} stationState=${station.state ?: "unknown"} reportedStationMHz=${station.reportedFrequency ?: "unknown"} fiveGhzSupported=${fiveGhzSupported ?: "unknown"}")
        if (!granted) throw IOException(if (Build.VERSION.SDK_INT >= 33)
            "Allow Nearby devices for DiPlay in the head unit's app permissions"
            else "Allow precise Location for DiPlay in the head unit's app permissions")
        if (wifiEnabled == false) throw IOException("Turn on Wi-Fi in the head unit's settings, then reconnect")
        // Location mode is diagnostic only: AOSP createGroup does not require it to be on.
        // Do not block firmware where Wi-Fi Direct works with Location services disabled.
        return !(wifiEnabled == true && fiveGhzSupported == false)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun logP2pState(attempt: StartAttempt, channel: WifiP2pManager.Channel) {
        val result = AtomicReference<Int?>()
        val latch = CountDownLatch(1)
        p2pManager.requestP2pState(channel) { state -> result.set(state); latch.countDown() }
        await(latch, REQUEST_POLL_NANOS)
        ensureStartActive(attempt)
        diagnostic("Wi-Fi P2P frameworkState=${result.get() ?: "unknown"}")
    }

    private fun interfaceHardwareAddress(interfaceName: String): String? =
        networkInterface(interfaceName)
            ?.hardwareAddress
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun networkInterface(interfaceName: String): NetworkInterface? = try {
        NetworkInterface.getByName(interfaceName)
    } catch (_: SocketException) {
        null
    }

    private fun groupSecurity(group: WifiP2pGroup): Iap2WirelessSecurity {
        if (Build.VERSION.SDK_INT < 36) return Iap2WirelessSecurity.WPA_WPA2
        return when (group.securityType) {
            WifiP2pGroup.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
            WifiP2pGroup.SECURITY_TYPE_WPA3_COMPATIBILITY ->
                Iap2WirelessSecurity.WPA3_TRANSITION
            WifiP2pGroup.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
            // Several vendor supplicants report an authentication key management the framework
            // cannot classify, which surfaces as UNKNOWN rather than as a concrete cipher suite.
            // The group itself is still the plain WPA2-PSK group this device creates by default,
            // so keep the assumption every older Android release relies on instead of aborting a
            // bring-up that has already resolved its SSID, band, interface and host address.
            WifiP2pGroup.SECURITY_TYPE_UNKNOWN -> {
                diagnostic("Wi-Fi P2P security type not reported by the framework; assuming WPA2-PSK")
                Iap2WirelessSecurity.WPA_WPA2
            }
            else -> throw IOException(
                "Unsupported Wi-Fi P2P security type: ${group.securityType}",
            )
        }
    }

    private fun randomCredentials(): Credentials = Credentials(
        ssid = "$ssidPrefix${randomToken(4)}",
        passphrase = randomToken(16),
    )

    private fun randomToken(length: Int): String =
        buildString(length) {
            repeat(length) {
                append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)])
            }
        }

    private fun ensureStartActive(attempt: StartAttempt) {
        synchronized(stateLock) {
            ensureStartActiveLocked(attempt)
        }
    }

    private fun ensureStartActiveLocked(attempt: StartAttempt) {
        if (closed) throw IOException("WifiP2pGroupManager closed while starting")
        if (startAttempt !== attempt) throw IOException("Wi-Fi P2P startup was cancelled")
        if (attempt.stopped) throw IOException("Wi-Fi P2P group stopped before startup completed")
        attempt.failure?.let { throw it }
    }

    private fun cleanupFailedStart(attempt: StartAttempt) {
        val failedChannel: WifiP2pManager.Channel?
        val failedThread: HandlerThread?
        val removeGroup: Boolean
        synchronized(stateLock) {
            if (startAttempt === attempt) startAttempt = null
            attempt.stopped = true
            stateLock.notifyAll()
            failedChannel = attempt.channel
            failedThread = attempt.thread
            removeGroup = attempt.createSucceeded
            created = false
            if (channel === failedChannel) channel = null
            if (callbackThread === failedThread) callbackThread = null
        }
        if (removeGroup && failedChannel != null) {
            removeGroupBlocking(failedChannel)
        }
        synchronized(legacyChannelLock) {
            if (failedChannel != null) releaseLegacyChannelRestriction(failedChannel)
            failedChannel?.closeCompat()
        }
        failedThread?.quitSafely()
    }

    private fun removeGroupBlocking(channel: WifiP2pManager.Channel, expectedName: String? = observedCreatedName ?: requestedName) {
        removeGroup(channel, waitForCallback = true, expectedName = expectedName)
    }

    private fun removeGroup(
        channel: WifiP2pManager.Channel,
        waitForCallback: Boolean,
        expectedName: String? = observedCreatedName ?: requestedName,
    ) {
        val latch = CountDownLatch(1)
        try {
            p2pManager.requestGroupInfo(channel) { current ->
                if (current == null) {
                    latch.countDown()
                    return@requestGroupInfo
                }
                // Cleanup needs this attempt's exact identity. The reinstall namespace used at
                // startup is broader and could also match a newer DiPlay session.
                val ours = current.isGroupOwner && !expectedName.isNullOrBlank() &&
                    current.networkName == expectedName
                if (!ours) {
                    diagnostic("Wi-Fi P2P cleanup skipped=another_app_owns_group")
                    latch.countDown()
                    return@requestGroupInfo
                }
                p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        latch.countDown()
                    }

                    override fun onFailure(reason: Int) {
                        diagnostic("Wi-Fi P2P remove rejected code=$reason reason=${failureReason(reason)}")
                        latch.countDown()
                    }
                })
            }
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Wi-Fi P2P removeGroup could not be issued", failure)
            latch.countDown()
        }
        if (!waitForCallback) return
        try {
            latch.await(REMOVE_GROUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun waitNanos(nanos: Long) {
        val millis = nanos / NANOS_PER_MILLISECOND
        val remainder = (nanos % NANOS_PER_MILLISECOND).toInt()
        try {
            stateLock.wait(millis, remainder)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for Wi-Fi P2P", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private fun failureReason(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi P2P is unsupported"
        WifiP2pManager.BUSY -> "Wi-Fi P2P is busy"
        WifiP2pManager.ERROR -> "generic error"
        WifiP2pManager.NO_PERMISSION -> "permission denied"
        else -> "reason $reason"
    }

    private fun is5Ghz(frequencyMHz: Int): Boolean = frequencyMHz in 5150..5895

    private class StartAttempt {
        var channel: WifiP2pManager.Channel? = null
        var thread: HandlerThread? = null
        var createSucceeded = false
        var request: CreateRequest? = null
        var failure: IOException? = null
        var stopped = false
    }

    private class CreateRequest {
        val createdNanos = System.nanoTime()
        var completed = false
        var failure: P2pCreateRejected? = null
    }

    private class Credentials(
        val ssid: String,
        val passphrase: String,
    )

    private companion object {
        const val TAG = "xcertplay-usb"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val REMOVE_GROUP_TIMEOUT_MILLIS = 2_000L
        const val UNKNOWN_CHANNEL = 0
        val REQUEST_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(500)
        const val TOKEN_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    }
}
