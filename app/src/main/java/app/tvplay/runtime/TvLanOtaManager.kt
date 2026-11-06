package app.tvplay.runtime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.tvplay.config.TvPreferencesStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.CRC32

/**
 * Discovery metadata returned by `fnos-lan-ota` over UDP 9988 (`DISCOVER_OTA`)
 * or `GET /api/v1/ota/discover`.
 */
data class LanOtaDiscoveryResult(
    val cmd: String,
    val enabled: Boolean,
    val service: String,
    val version: String,
    val title: String?,
    val host: String,
    val port: Int,
    val baseUrl: String,
    val checkApi: String,
    val publishApi: String,
    val reportApi: String,
)

/**
 * Release metadata returned by `/api/v1/ota/check`, `/api/v1/ota/latest`, or `/api/v1/ota/releases`.
 */
data class LanOtaRelease(
    val releaseId: Long,
    val versionName: String,
    val versionCode: Long,
    val channel: String,
    val minSdk: Int,
    val targetSdk: Int,
    val abis: List<String>,
    val isMandatory: Boolean,
    val forceUpdate: Boolean,
    val fileName: String,
    val fileSize: Long,
    val sha256: String?,
    val md5: String?,
    val crc32: String?,
    val changelog: String,
    val downloadUrl: String,
    val shortcutUrl: String?,
    val pageUrl: String?,
    val publishedAt: String?,
)

/**
 * Result of checking `/api/v1/ota/check` or `/api/v1/ota/latest`.
 */
data class LanOtaCheckResult(
    val hasUpdate: Boolean,
    val forceUpdate: Boolean,
    val appKey: String,
    val release: LanOtaRelease?,
    val serverBaseUrl: String,
    val message: String? = null,
)

/**
 * Upgrade status values reported to `POST /api/v1/ota/report`.
 */
enum class LanOtaReportStatus(val wireValue: String) {
    DOWNLOADING("downloading"),
    INSTALLING("installing"),
    SUCCESS("success"),
    FAILED("failed"),
}

data class OtaDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Int,
    val resumedFromBytes: Long = 0L,
)

data class ChecksumVerificationResult(
    val verified: Boolean,
    val algorithm: String,
    val expected: String?,
    val actual: String?,
)

/**
 * Pure protocol parser, URL builder, and checksum verifier for the `fnos-lan-ota` system.
 */
object LanOtaProtocol {
    const val DISCOVER_PORT = 9988
    const val DISCOVER_PAYLOAD = "DISCOVER_OTA"
    const val DISCOVER_ACK_CMD = "DISCOVER_OTA_ACK"

    fun parseDiscoveryJson(raw: String, fallbackHost: String? = null): LanOtaDiscoveryResult? {
        val root = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return null
        val payload = root.optJSONObject("data") ?: root
        val cmd = payload.optString("cmd", "")
        val service = payload.optString("service", "")
        if (cmd != DISCOVER_ACK_CMD && service != "fnos-lan-ota") {
            return null
        }
        val enabled = payload.optBoolean("enabled", true)
        if (!enabled) return null

        val host = payload.optString("host", "").trim().ifEmpty { fallbackHost.orEmpty().trim() }
        val port = payload.optInt("port", 8080).takeIf { it in 1..65535 } ?: 8080
        val rawBaseUrl = payload.optString("base_url", "").trim().trimEnd('/')
        val baseUrl = when {
            rawBaseUrl.isNotEmpty() -> TvPreferencesStore.normalizeOtaServerUrl(rawBaseUrl)
            host.isNotEmpty() -> "http://$host:$port"
            else -> return null
        }
        val checkApi = payload.optString("check_api", "").trim().ifEmpty { "$baseUrl/api/v1/ota/check" }
        val publishApi = payload.optString("publish_api", "").trim().ifEmpty { "$baseUrl/api/v1/ota/publish" }
        val reportApi = payload.optString("report_api", "").trim().ifEmpty { "$baseUrl/api/v1/ota/report" }
        val title = payload.optString("title", "").trim().takeIf { it.isNotEmpty() }

        return LanOtaDiscoveryResult(
            cmd = cmd.ifEmpty { DISCOVER_ACK_CMD },
            enabled = true,
            service = service.ifEmpty { "fnos-lan-ota" },
            version = payload.optString("version", "1.0.0"),
            title = title,
            host = host.ifEmpty { runCatching { URI(baseUrl).host.orEmpty() }.getOrDefault("") },
            port = port,
            baseUrl = baseUrl,
            checkApi = checkApi,
            publishApi = publishApi,
            reportApi = reportApi,
        )
    }

    fun parseCheckJson(
        raw: String,
        serverBaseUrl: String,
        currentVersionCode: Long,
        defaultAppKey: String = TvPreferencesStore.DEFAULT_OTA_APP_KEY,
    ): LanOtaCheckResult {
        val normalizedBase = TvPreferencesStore.normalizeOtaServerUrl(serverBaseUrl)
        val root = try {
            JSONObject(raw.trim())
        } catch (error: Exception) {
            throw IOException("无法解析 OTA 响应 JSON: ${error.message}", error)
        }
        val code = root.optInt("code", 0)
        val message = root.optString("message", "").trim().takeIf { it.isNotEmpty() }
        if (code == 404) {
            return LanOtaCheckResult(
                hasUpdate = false,
                forceUpdate = false,
                appKey = defaultAppKey,
                release = null,
                serverBaseUrl = normalizedBase,
                message = message ?: "服务端暂无已发布更新版本",
            )
        }
        val success = root.optBoolean("success", code == 0)
        if (!success && code != 0) {
            throw IOException(message ?: "OTA 检查失败 (code=$code)")
        }

        val dataObj = root.optJSONObject("data")
        val container = if (dataObj != null && (dataObj.has("has_update") || dataObj.has("release"))) {
            dataObj
        } else {
            root
        }

        val appObj = container.optJSONObject("app") ?: root.optJSONObject("app")
        val appKey = appObj?.optString("app_key", "")?.trim()?.takeIf { it.isNotEmpty() } ?: defaultAppKey

        val releaseObj = container.optJSONObject("release")
            ?: dataObj?.takeIf { it.has("version_code") || it.has("release_id") }
            ?: root.takeIf { it.has("version_code") && it.has("release_id") }

        val release = releaseObj?.let { parseReleaseObject(it, normalizedBase, appKey) }
        val explicitHasUpdate = if (container.has("has_update")) {
            container.optBoolean("has_update", false)
        } else {
            release != null && release.versionCode > currentVersionCode
        }
        val hasUpdate = explicitHasUpdate && release != null
        val forceUpdate = hasUpdate && (
            container.optBoolean("force_update", false) ||
                release?.forceUpdate == true ||
                release?.isMandatory == true
            )

        return LanOtaCheckResult(
            hasUpdate = hasUpdate,
            forceUpdate = forceUpdate,
            appKey = appKey,
            release = release,
            serverBaseUrl = normalizedBase,
            message = message,
        )
    }

    fun parseReleaseObject(
        obj: JSONObject,
        serverBaseUrl: String,
        defaultAppKey: String = TvPreferencesStore.DEFAULT_OTA_APP_KEY,
    ): LanOtaRelease {
        val normalizedBase = TvPreferencesStore.normalizeOtaServerUrl(serverBaseUrl)
        val releaseId = obj.optLong("release_id", obj.optLong("id", 0L))
        val versionName = obj.optString("version_name", "").trim().ifEmpty { "unknown" }
        val versionCode = obj.optLong("version_code", 0L)
        val channel = obj.optString("channel", TvPreferencesStore.DEFAULT_OTA_CHANNEL).trim()
            .ifEmpty { TvPreferencesStore.DEFAULT_OTA_CHANNEL }
        val minSdk = obj.optInt("min_sdk", 24)
        val targetSdk = obj.optInt("target_sdk", 34)
        val abis = mutableListOf<String>()
        val abisArr = obj.optJSONArray("abis")
        if (abisArr != null) {
            for (i in 0 until abisArr.length()) {
                val abi = abisArr.optString(i, "").trim()
                if (abi.isNotEmpty()) abis.add(abi)
            }
        }
        val isMandatory = obj.optBooleanCompat("is_mandatory", false)
        val forceUpdate = obj.optBooleanCompat("force_update", isMandatory)
        val fileName = obj.optString("file_name", "TVPlay-$versionName.apk").trim()
            .ifEmpty { "TVPlay-$versionName.apk" }
        val fileSize = obj.optLong("file_size", 0L)
        val sha256 = obj.optString("sha256", "").trim().lowercase(Locale.US).takeIf { it.isNotEmpty() }
        val md5 = obj.optString("md5", "").trim().lowercase(Locale.US).takeIf { it.isNotEmpty() }
        val crc32 = obj.optString("crc32", "").trim().uppercase(Locale.US).takeIf { it.isNotEmpty() }
        val changelog = obj.optString("changelog", "").trim()
        val rawDownloadUrl = obj.optString("download_url", "").trim()
        val rawShortcutUrl = obj.optString("shortcut_url", "").trim()
        val rawPageUrl = obj.optString("page_url", "").trim()

        val downloadUrl = when {
            rawDownloadUrl.isNotEmpty() -> resolveUrl(normalizedBase, rawDownloadUrl)
            releaseId > 0L -> "$normalizedBase/api/v1/ota/download/$releaseId"
            rawShortcutUrl.isNotEmpty() -> resolveUrl(normalizedBase, rawShortcutUrl)
            else -> "$normalizedBase/d/$defaultAppKey"
        }
        val shortcutUrl = rawShortcutUrl.takeIf { it.isNotEmpty() }?.let { resolveUrl(normalizedBase, it) }
        val pageUrl = rawPageUrl.takeIf { it.isNotEmpty() }?.let { resolveUrl(normalizedBase, it) }
        val publishedAt = obj.optString("published_at", "").trim().takeIf { it.isNotEmpty() }

        return LanOtaRelease(
            releaseId = releaseId,
            versionName = versionName,
            versionCode = versionCode,
            channel = channel,
            minSdk = minSdk,
            targetSdk = targetSdk,
            abis = abis,
            isMandatory = isMandatory,
            forceUpdate = forceUpdate,
            fileName = fileName,
            fileSize = fileSize,
            sha256 = sha256,
            md5 = md5,
            crc32 = crc32,
            changelog = changelog,
            downloadUrl = downloadUrl,
            shortcutUrl = shortcutUrl,
            pageUrl = pageUrl,
            publishedAt = publishedAt,
        )
    }

    fun parseReleasesJson(
        raw: String,
        serverBaseUrl: String,
        defaultAppKey: String = TvPreferencesStore.DEFAULT_OTA_APP_KEY,
    ): List<LanOtaRelease> {
        val root = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return emptyList()
        val array: JSONArray = root.optJSONArray("data")
            ?: root.optJSONArray("releases")
            ?: root.optJSONObject("data")?.optJSONArray("releases")
            ?: return emptyList()
        val result = ArrayList<LanOtaRelease>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            result.add(parseReleaseObject(item, serverBaseUrl, defaultAppKey))
        }
        return result
    }

    fun buildCheckUrl(
        serverBaseUrl: String,
        appKey: String,
        packageName: String,
        versionCode: Long,
        versionName: String,
        deviceId: String,
        channel: String,
        sdkInt: Int,
        abi: String,
    ): String {
        val base = TvPreferencesStore.normalizeOtaServerUrl(serverBaseUrl)
        val params = linkedMapOf(
            "app_key" to appKey,
            "package_name" to packageName,
            "version_code" to versionCode.toString(),
            "version_name" to versionName,
            "device_id" to deviceId,
            "channel" to channel,
            "sdk_int" to sdkInt.toString(),
            "abi" to abi,
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$base/api/v1/ota/check?$query"
    }

    fun buildReportJson(
        deviceId: String,
        appKey: String,
        releaseId: Long,
        fromVersion: String,
        toVersion: String,
        status: LanOtaReportStatus,
        progress: Int,
        message: String,
    ): String = JSONObject().apply {
        put("device_id", deviceId)
        put("app_key", appKey)
        if (releaseId > 0L) {
            put("release_id", releaseId)
        }
        put("from_version", fromVersion)
        put("to_version", toVersion)
        put("status", status.wireValue)
        put("progress", progress.coerceIn(0, 100))
        put("message", message)
    }.toString()

    fun sha256Hex(file: File): String = digestHex(file, "SHA-256")

    fun md5Hex(file: File): String = digestHex(file, "MD5")

    fun crc32Hex(file: File): String {
        val crc = CRC32()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
            }
        }
        return "%08X".format(Locale.US, crc.value and 0xffffffffL)
    }

    fun verifyChecksums(
        file: File,
        expectedSha256: String?,
        expectedMd5: String?,
        expectedCrc32: String?,
    ): ChecksumVerificationResult {
        val cleanSha256 = expectedSha256?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        if (cleanSha256 != null) {
            val actual = sha256Hex(file)
            return ChecksumVerificationResult(
                verified = actual.equals(cleanSha256, ignoreCase = true),
                algorithm = "SHA-256",
                expected = cleanSha256,
                actual = actual,
            )
        }
        val cleanMd5 = expectedMd5?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        if (cleanMd5 != null) {
            val actual = md5Hex(file)
            return ChecksumVerificationResult(
                verified = actual.equals(cleanMd5, ignoreCase = true),
                algorithm = "MD5",
                expected = cleanMd5,
                actual = actual,
            )
        }
        val cleanCrc32 = expectedCrc32?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() }
        if (cleanCrc32 != null) {
            val actual = crc32Hex(file)
            return ChecksumVerificationResult(
                verified = actual.equals(cleanCrc32, ignoreCase = true),
                algorithm = "CRC32",
                expected = cleanCrc32,
                actual = actual,
            )
        }
        return ChecksumVerificationResult(
            verified = true,
            algorithm = "NONE",
            expected = null,
            actual = null,
        )
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "未知大小"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.2f MB", mb)
        } else {
            val kb = bytes.toDouble() / 1024.0
            String.format(Locale.US, "%.1f KB", kb)
        }
    }

    private fun digestHex(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun resolveUrl(baseUrl: String, pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http://", ignoreCase = true) ||
            pathOrUrl.startsWith("https://", ignoreCase = true)
        ) {
            return pathOrUrl
        }
        val prefix = baseUrl.trimEnd('/')
        val suffix = if (pathOrUrl.startsWith('/')) pathOrUrl else "/$pathOrUrl"
        return "$prefix$suffix"
    }

    private fun JSONObject.optBooleanCompat(key: String, defaultValue: Boolean): Boolean {
        if (!has(key) || isNull(key)) return defaultValue
        val raw = opt(key)
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.equals("true", ignoreCase = true) || raw == "1"
            else -> defaultValue
        }
    }
}

/**
 * End-to-end LAN OTA client manager for TVPlay (`fnos-lan-ota`):
 * - UDP 9988 (`DISCOVER_OTA`) + HTTP `/api/v1/ota/discover` auto-discovery
 * - Version check (`/api/v1/ota/check` & `/api/v1/ota/latest`)
 * - HTTP `Range` resumable APK download (`/api/v1/ota/download/{id}`) with SHA-256 / MD5 / CRC32 verification
 * - Real-time status reporting (`POST /api/v1/ota/report`: `downloading`, `installing`, `success`, `failed`)
 * - Android TV package installer launch via `DiagnosticReportProvider` (`FileProvider`)
 */
object TvLanOtaManager {
    private const val CONNECT_TIMEOUT_MILLIS = 6_000
    private const val READ_TIMEOUT_MILLIS = 15_000
    private const val REPORT_THROTTLE_MILLIS = 1_500L
    private val backgroundExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "tvplay-lan-ota").apply { isDaemon = true }
    }

    @Volatile
    private var pendingInstallApkFile: File? = null

    @Volatile
    private var pendingInstallCheckResult: LanOtaCheckResult? = null

    data class InstalledAppVersion(
        val appKey: String,
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
    )

    sealed interface InstallLaunchResult {
        data object InstallerLaunched : InstallLaunchResult
        data object UnknownSourcesPermissionRequested : InstallLaunchResult
        data class Failed(val message: String) : InstallLaunchResult
    }

    @Suppress("DEPRECATION")
    fun currentAppVersion(context: Context): InstalledAppVersion {
        val pkgName = context.packageName
        val canonicalAppKey = pkgName.removeSuffix(".debug")
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                context.packageManager.getPackageInfo(pkgName, 0)
            }
        }.getOrNull()
        val versionCode = if (info == null) {
            1L
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        val rawVersionName = info?.versionName?.removeSuffix("-debug")?.trim().orEmpty().ifEmpty { "1.0.0" }
        return InstalledAppVersion(
            appKey = canonicalAppKey,
            packageName = pkgName,
            versionCode = versionCode,
            versionName = rawVersionName,
        )
    }

    /**
     * Discovers the `fnos-lan-ota` server on the local network using UDP 9988 broadcast
     * (`DISCOVER_OTA`) and falls back to `GET /api/v1/ota/discover` on the configured URL.
     */
    fun discoverServer(
        context: Context,
        timeoutMillis: Int = 1_200,
    ): LanOtaDiscoveryResult? {
        val configuredUrl = TvPreferencesStore.loadOtaServerUrl(context)
        val configuredHost = runCatching { URI(configuredUrl).host }.getOrNull()

        val udpResult = discoverViaUdp(configuredHost, timeoutMillis)
        if (udpResult != null) {
            TvPreferencesStore.saveOtaServerUrl(context, udpResult.baseUrl)
            TvDiagnosticsLogger.record("LAN OTA server discovered via UDP 9988")
            return udpResult
        }

        val httpResult = discoverViaHttp(configuredUrl, timeoutMillis.coerceAtLeast(2_000))
        if (httpResult != null) {
            TvPreferencesStore.saveOtaServerUrl(context, httpResult.baseUrl)
            TvDiagnosticsLogger.record("LAN OTA server discovered via HTTP")
            return httpResult
        }
        return null
    }

    fun discoverViaUdp(
        configuredHost: String? = null,
        timeoutMillis: Int = 1_200,
    ): LanOtaDiscoveryResult? {
        val payload = LanOtaProtocol.DISCOVER_PAYLOAD.toByteArray(StandardCharsets.UTF_8)
        val targets = linkedSetOf<InetAddress>()
        runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (iface in Collections.list(interfaces)) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (ifaceAddr in iface.interfaceAddresses) {
                        val bcast = ifaceAddr.broadcast
                        if (bcast is Inet4Address) {
                            targets.add(bcast)
                        }
                    }
                }
            }
        }
        if (!configuredHost.isNullOrBlank()) {
            runCatching { targets.add(InetAddress.getByName(configuredHost)) }
        }

        return runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = timeoutMillis.coerceAtLeast(300)
                for (target in targets) {
                    runCatching {
                        val packet = DatagramPacket(
                            payload,
                            payload.size,
                            InetSocketAddress(target, LanOtaProtocol.DISCOVER_PORT),
                        )
                        socket.send(packet)
                    }
                }
                val recvBuf = ByteArray(4096)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPacket)
                val responseText = String(
                    recvPacket.data,
                    recvPacket.offset,
                    recvPacket.length,
                    StandardCharsets.UTF_8,
                )
                LanOtaProtocol.parseDiscoveryJson(
                    raw = responseText,
                    fallbackHost = recvPacket.address?.hostAddress,
                )
            }
        }.getOrNull()
    }

    fun discoverViaHttp(
        serverBaseUrl: String,
        timeoutMillis: Int = 2_500,
    ): LanOtaDiscoveryResult? {
        val normalized = TvPreferencesStore.normalizeOtaServerUrl(serverBaseUrl)
        val url = "$normalized/api/v1/ota/discover"
        val fallbackHost = runCatching { URI(normalized).host }.getOrNull()
        return runCatching {
            val body = httpGetText(url, timeoutMillis, timeoutMillis)
            LanOtaProtocol.parseDiscoveryJson(body, fallbackHost)
        }.getOrNull()
    }

    /**
     * Checks `/api/v1/ota/check` (after optional UDP/HTTP server discovery).
     */
    fun checkForUpdate(
        context: Context,
        tryDiscoveryFirst: Boolean = true,
    ): LanOtaCheckResult {
        val discovered = if (tryDiscoveryFirst) {
            discoverServer(context, timeoutMillis = 900)
        } else {
            null
        }
        val baseUrl = discovered?.baseUrl ?: TvPreferencesStore.loadOtaServerUrl(context)
        val installed = currentAppVersion(context)
        val deviceId = TvPreferencesStore.loadOtaDeviceId(context)
        val channel = TvPreferencesStore.loadOtaChannel(context)
        val primaryAbi = Build.SUPPORTED_ABIS?.firstOrNull().orEmpty().ifEmpty { "arm64-v8a" }

        val checkUrl = LanOtaProtocol.buildCheckUrl(
            serverBaseUrl = baseUrl,
            appKey = installed.appKey,
            packageName = installed.packageName,
            versionCode = installed.versionCode,
            versionName = installed.versionName,
            deviceId = deviceId,
            channel = channel,
            sdkInt = Build.VERSION.SDK_INT,
            abi = primaryAbi,
        )
        val responseBody = httpGetText(checkUrl, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS)
        return LanOtaProtocol.parseCheckJson(
            raw = responseBody,
            serverBaseUrl = baseUrl,
            currentVersionCode = installed.versionCode,
            defaultAppKey = installed.appKey,
        )
    }

    /**
     * Queries `/api/v1/ota/latest?app_key=...&channel=...` directly without version comparison parameters.
     */
    fun fetchLatestRelease(context: Context): LanOtaRelease? {
        val baseUrl = TvPreferencesStore.loadOtaServerUrl(context)
        val installed = currentAppVersion(context)
        val channel = TvPreferencesStore.loadOtaChannel(context)
        val url = "$baseUrl/api/v1/ota/latest?app_key=${URLEncoder.encode(installed.appKey, "UTF-8")}" +
            "&channel=${URLEncoder.encode(channel, "UTF-8")}"
        val body = httpGetText(url, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS)
        val check = LanOtaProtocol.parseCheckJson(
            raw = body,
            serverBaseUrl = baseUrl,
            currentVersionCode = 0L,
            defaultAppKey = installed.appKey,
        )
        return check.release
    }

    /**
     * Queries `/api/v1/ota/releases?app_key=...` for all enabled releases of TVPlay.
     */
    fun fetchReleaseList(context: Context): List<LanOtaRelease> {
        val baseUrl = TvPreferencesStore.loadOtaServerUrl(context)
        val installed = currentAppVersion(context)
        val url = "$baseUrl/api/v1/ota/releases?app_key=${URLEncoder.encode(installed.appKey, "UTF-8")}"
        val body = httpGetText(url, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS)
        return LanOtaProtocol.parseReleasesJson(body, baseUrl, installed.appKey)
    }

    /**
     * Downloads the release APK with HTTP `Range` resume support (`206 Partial Content`),
     * verifies `SHA-256` / `MD5` / `CRC32` checksums, and reports progress to `/api/v1/ota/report`.
     */
    fun downloadRelease(
        context: Context,
        checkResult: LanOtaCheckResult,
        onProgress: (OtaDownloadProgress) -> Unit = {},
    ): File {
        val release = requireNotNull(checkResult.release) { "No OTA release to download" }
        val installed = currentAppVersion(context)
        val deviceId = TvPreferencesStore.loadOtaDeviceId(context)
        val otaDir = File(context.cacheDir, "ota").apply { mkdirs() }

        // Clean up older OTA files from previous versions
        otaDir.listFiles()?.forEach { file ->
            if (!file.name.contains("${release.releaseId}-${release.versionCode}")) {
                runCatching { file.delete() }
            }
        }

        val finalFile = File(otaDir, "TVPlay-${release.releaseId}-${release.versionCode}.apk")
        val partFile = File(otaDir, "TVPlay-${release.releaseId}-${release.versionCode}.apk.part")

        if (finalFile.isFile && finalFile.length() > 0L) {
            val verify = LanOtaProtocol.verifyChecksums(
                file = finalFile,
                expectedSha256 = release.sha256,
                expectedMd5 = release.md5,
                expectedCrc32 = release.crc32,
            )
            if (verify.verified && (release.fileSize <= 0L || finalFile.length() == release.fileSize)) {
                onProgress(
                    OtaDownloadProgress(
                        downloadedBytes = finalFile.length(),
                        totalBytes = finalFile.length(),
                        percent = 100,
                    ),
                )
                reportStatusAsync(
                    serverBaseUrl = checkResult.serverBaseUrl,
                    deviceId = deviceId,
                    appKey = checkResult.appKey,
                    releaseId = release.releaseId,
                    fromVersion = installed.versionName,
                    toVersion = release.versionName,
                    status = LanOtaReportStatus.DOWNLOADING,
                    progress = 100,
                    message = "更新包已就绪并完成校验",
                )
                return finalFile
            }
            runCatching { finalFile.delete() }
        }

        return try {
            val downloaded = performResumableDownload(
                downloadUrl = appendDeviceIdIfNeeded(release.downloadUrl, deviceId),
                partFile = partFile,
                finalFile = finalFile,
                release = release,
                onProgress = { progress ->
                    onProgress(progress)
                },
                onReportProgress = { percent ->
                    reportStatusAsync(
                        serverBaseUrl = checkResult.serverBaseUrl,
                        deviceId = deviceId,
                        appKey = checkResult.appKey,
                        releaseId = release.releaseId,
                        fromVersion = installed.versionName,
                        toVersion = release.versionName,
                        status = LanOtaReportStatus.DOWNLOADING,
                        progress = percent,
                        message = "正在下载更新包 ($percent%)",
                    )
                },
            )
            downloaded
        } catch (error: Throwable) {
            reportStatusAsync(
                serverBaseUrl = checkResult.serverBaseUrl,
                deviceId = deviceId,
                appKey = checkResult.appKey,
                releaseId = release.releaseId,
                fromVersion = installed.versionName,
                toVersion = release.versionName,
                status = LanOtaReportStatus.FAILED,
                progress = 0,
                message = "下载或校验失败: ${error.message ?: error.javaClass.simpleName}",
            )
            throw error
        }
    }

    private fun performResumableDownload(
        downloadUrl: String,
        partFile: File,
        finalFile: File,
        release: LanOtaRelease,
        onProgress: (OtaDownloadProgress) -> Unit,
        onReportProgress: (Int) -> Unit,
    ): File {
        var existingBytes = if (partFile.isFile) partFile.length().coerceAtLeast(0L) else 0L
        if (release.fileSize > 0L && existingBytes > release.fileSize) {
            runCatching { partFile.delete() }
            existingBytes = 0L
        }

        var connection = openDownloadConnection(downloadUrl, existingBytes)
        var status = connection.responseCode
        if (status == 416 && existingBytes > 0L) {
            connection.disconnect()
            runCatching { partFile.delete() }
            existingBytes = 0L
            connection = openDownloadConnection(downloadUrl, 0L)
            status = connection.responseCode
        }

        try {
            if (status !in 200..299) {
                throw IOException("OTA 下载失败 (HTTP $status)")
            }

            val appendMode = status == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
            val startBytes = if (appendMode) existingBytes else 0L
            val contentLength = connection.contentLengthLong
            val totalBytes = when {
                release.fileSize > 0L -> release.fileSize
                contentLength > 0L -> startBytes + contentLength
                else -> -1L
            }

            val headerSha256 = connection.getHeaderField("X-Checksum-SHA256")?.trim()
            val headerMd5 = connection.getHeaderField("X-Checksum-MD5")?.trim()
            val headerCrc32 = connection.getHeaderField("X-Checksum-CRC32")?.trim()

            onReportProgress(0)
            var lastReportedPercent = 0
            var lastReportedTimeMillis = System.currentTimeMillis()
            var lastUiProgressPercent = -1
            var lastUiProgressTimeMillis = 0L
            var currentBytes = startBytes

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partFile, appendMode).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        currentBytes += read
                        val percent = if (totalBytes > 0L) {
                            ((currentBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        val now = System.currentTimeMillis()
                        if (percent != lastUiProgressPercent ||
                            currentBytes == totalBytes ||
                            now - lastUiProgressTimeMillis >= 80L
                        ) {
                            lastUiProgressPercent = percent
                            lastUiProgressTimeMillis = now
                            onProgress(
                                OtaDownloadProgress(
                                    downloadedBytes = currentBytes,
                                    totalBytes = totalBytes,
                                    percent = percent,
                                    resumedFromBytes = startBytes,
                                ),
                            )
                        }
                        if (percent >= lastReportedPercent + 20 || now - lastReportedTimeMillis >= REPORT_THROTTLE_MILLIS) {
                            lastReportedPercent = percent
                            lastReportedTimeMillis = now
                            if (percent in 1..99) {
                                onReportProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }

            val verify = LanOtaProtocol.verifyChecksums(
                file = partFile,
                expectedSha256 = release.sha256 ?: headerSha256,
                expectedMd5 = release.md5 ?: headerMd5,
                expectedCrc32 = release.crc32 ?: headerCrc32,
            )
            if (!verify.verified) {
                runCatching { partFile.delete() }
                throw IOException(
                    "安装包 ${verify.algorithm} 校验不匹配 (期望 ${verify.expected}，实际 ${verify.actual})",
                )
            }

            if (finalFile.exists()) {
                runCatching { finalFile.delete() }
            }
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                runCatching { partFile.delete() }
            }
            onProgress(
                OtaDownloadProgress(
                    downloadedBytes = finalFile.length(),
                    totalBytes = finalFile.length(),
                    percent = 100,
                    resumedFromBytes = startBytes,
                ),
            )
            onReportProgress(100)
            return finalFile
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Records pending upgrade state, reports `installing` to `/api/v1/ota/report`, and launches
     * the system package installer (or unknown sources settings page if required on API 26+).
     */
    fun installApk(
        activity: Activity,
        apkFile: File,
        checkResult: LanOtaCheckResult,
        requestCodeUnknownSources: Int,
    ): InstallLaunchResult {
        val release = checkResult.release ?: return InstallLaunchResult.Failed("缺少版本信息")
        val installed = currentAppVersion(activity)
        val deviceId = TvPreferencesStore.loadOtaDeviceId(activity)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canInstall = runCatching { activity.packageManager.canRequestPackageInstalls() }.getOrDefault(true)
            if (!canInstall) {
                pendingInstallApkFile = apkFile
                pendingInstallCheckResult = checkResult
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                )
                val opened = runCatching {
                    activity.startActivityForResult(settingsIntent, requestCodeUnknownSources)
                    true
                }.getOrDefault(false)
                if (opened) {
                    return InstallLaunchResult.UnknownSourcesPermissionRequested
                }
            }
        }

        return launchSystemApkInstaller(activity, apkFile, checkResult, release, installed, deviceId)
    }

    fun resumePendingInstallIfReady(activity: Activity): Boolean {
        val apkFile = pendingInstallApkFile ?: return false
        val checkResult = pendingInstallCheckResult ?: return false
        if (!apkFile.isFile) {
            pendingInstallApkFile = null
            pendingInstallCheckResult = null
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canInstall = runCatching { activity.packageManager.canRequestPackageInstalls() }.getOrDefault(true)
            if (!canInstall) return false
        }
        pendingInstallApkFile = null
        pendingInstallCheckResult = null
        val release = checkResult.release ?: return false
        val installed = currentAppVersion(activity)
        val deviceId = TvPreferencesStore.loadOtaDeviceId(activity)
        return launchSystemApkInstaller(
            activity,
            apkFile,
            checkResult,
            release,
            installed,
            deviceId,
        ) is InstallLaunchResult.InstallerLaunched
    }

    private fun launchSystemApkInstaller(
        activity: Activity,
        apkFile: File,
        checkResult: LanOtaCheckResult,
        release: LanOtaRelease,
        installed: InstalledAppVersion,
        deviceId: String,
    ): InstallLaunchResult {
        TvPreferencesStore.savePendingOtaUpgrade(
            activity,
            TvPreferencesStore.PendingOtaUpgradeRecord(
                releaseId = release.releaseId,
                fromVersion = installed.versionName,
                toVersion = release.versionName,
                toVersionCode = release.versionCode,
                appKey = checkResult.appKey,
                serverBaseUrl = checkResult.serverBaseUrl,
            ),
        )
        reportStatusAsync(
            serverBaseUrl = checkResult.serverBaseUrl,
            deviceId = deviceId,
            appKey = checkResult.appKey,
            releaseId = release.releaseId,
            fromVersion = installed.versionName,
            toVersion = release.versionName,
            status = LanOtaReportStatus.INSTALLING,
            progress = 100,
            message = "正在拉起系统安装程序升级至 ${release.versionName}",
        )

        return try {
            val authority = "${activity.packageName}.diagnostic-reports"
            val apkUri = FileProvider.getUriForFile(activity, authority, apkFile)
            @Suppress("DEPRECATION")
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = apkUri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
            }
            val started = runCatching {
                activity.startActivity(installIntent)
                true
            }.getOrElse {
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(viewIntent)
                true
            }
            if (started) {
                InstallLaunchResult.InstallerLaunched
            } else {
                InstallLaunchResult.Failed("未能启动系统 APK 安装器")
            }
        } catch (error: Throwable) {
            TvPreferencesStore.clearPendingOtaUpgrade(activity)
            reportStatusAsync(
                serverBaseUrl = checkResult.serverBaseUrl,
                deviceId = deviceId,
                appKey = checkResult.appKey,
                releaseId = release.releaseId,
                fromVersion = installed.versionName,
                toVersion = release.versionName,
                status = LanOtaReportStatus.FAILED,
                progress = 100,
                message = "启动安装器失败: ${error.message ?: error.javaClass.simpleName}",
            )
            InstallLaunchResult.Failed(error.message ?: "启动系统 APK 安装器失败")
        }
    }

    /**
     * Checks if a previous OTA installation has completed and reports `status = "success"`
     * to `POST /api/v1/ota/report`.
     */
    fun reportPendingUpgradeSuccessAsync(context: Context) {
        val appContext = context.applicationContext ?: context
        val pending = TvPreferencesStore.loadPendingOtaUpgrade(appContext) ?: return
        val installed = currentAppVersion(appContext)
        if (installed.versionCode < pending.toVersionCode) {
            return
        }
        TvPreferencesStore.clearPendingOtaUpgrade(appContext)
        val deviceId = TvPreferencesStore.loadOtaDeviceId(appContext)
        reportStatusAsync(
            serverBaseUrl = pending.serverBaseUrl,
            deviceId = deviceId,
            appKey = pending.appKey,
            releaseId = pending.releaseId,
            fromVersion = pending.fromVersion,
            toVersion = installed.versionName,
            status = LanOtaReportStatus.SUCCESS,
            progress = 100,
            message = "升级成功 (${pending.fromVersion} -> ${installed.versionName})",
        )
    }

    fun reportStatusAsync(
        serverBaseUrl: String,
        deviceId: String,
        appKey: String,
        releaseId: Long,
        fromVersion: String,
        toVersion: String,
        status: LanOtaReportStatus,
        progress: Int,
        message: String,
    ) {
        backgroundExecutor.execute {
            runCatching {
                reportStatusSync(
                    serverBaseUrl = serverBaseUrl,
                    deviceId = deviceId,
                    appKey = appKey,
                    releaseId = releaseId,
                    fromVersion = fromVersion,
                    toVersion = toVersion,
                    status = status,
                    progress = progress,
                    message = message,
                )
            }
        }
    }

    fun reportStatusSync(
        serverBaseUrl: String,
        deviceId: String,
        appKey: String,
        releaseId: Long,
        fromVersion: String,
        toVersion: String,
        status: LanOtaReportStatus,
        progress: Int,
        message: String,
    ): Boolean {
        val base = TvPreferencesStore.normalizeOtaServerUrl(serverBaseUrl)
        val url = "$base/api/v1/ota/report"
        val payload = LanOtaProtocol.buildReportJson(
            deviceId = deviceId,
            appKey = appKey,
            releaseId = releaseId,
            fromVersion = fromVersion,
            toVersion = toVersion,
            status = status,
            progress = progress,
            message = message,
        ).toByteArray(StandardCharsets.UTF_8)

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = CONNECT_TIMEOUT_MILLIS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setFixedLengthStreamingMode(payload.size)
        }
        return try {
            connection.outputStream.use { out ->
                out.write(payload)
                out.flush()
            }
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }

    fun runInBackground(task: () -> Unit) {
        backgroundExecutor.execute(task)
    }

    private fun appendDeviceIdIfNeeded(downloadUrl: String, deviceId: String): String {
        if (downloadUrl.contains("device_id=")) return downloadUrl
        val sep = if ('?' in downloadUrl) "&" else "?"
        return "$downloadUrl${sep}device_id=${URLEncoder.encode(deviceId, "UTF-8")}"
    }

    private fun openDownloadConnection(downloadUrl: String, rangeStartBytes: Long): HttpURLConnection {
        var currentUrl = downloadUrl
        repeat(5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                if (rangeStartBytes > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStartBytes-")
                }
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("OTA 下载重定向缺少 Location 响应头")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
            } else {
                return conn
            }
        }
        throw IOException("OTA 下载重定向次数过多")
    }

    private fun httpGetText(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: throw IOException("HTTP $status")
            }
            stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
