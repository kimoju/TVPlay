package app.tvplay.runtime

import android.Manifest
import android.app.ActivityManager
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import app.tvplay.R
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID

/**
 * Unified diagnostics logger for TVPlay:
 * - In-memory ring buffer of redacted diagnostic lines
 * - Async bounded session log file ([SessionLogFile] / [AsyncDiagnosticLog])
 * - Diagnostic report assembly and export ([exportReport]) to `Downloads/TVPlay`
 * - ContentProvider ([DiagnosticReportProvider]) for sharing exported reports
 */
object TvDiagnosticsLogger {
    const val MAX_RING_LINES = 512
    private val ringLock = Any()
    private val ringBuffer = ArrayDeque<String>(MAX_RING_LINES)
    @Volatile var activeSessionLog: SessionLogFile? = null
    private val timeFormatThreadLocal = object : ThreadLocal<Pair<SimpleDateFormat, Date>>() {
        override fun initialValue(): Pair<SimpleDateFormat, Date> =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US) to Date()
    }

    internal fun formatTime(nowMillis: Long): String {
        val (formatter, date) = timeFormatThreadLocal.get()!!
        date.time = nowMillis
        return formatter.format(date)
    }

    fun record(message: String, nowMillis: Long = System.currentTimeMillis()) {
        val redacted = DiagnosticRedactor.redact(message) ?: return
        val formatted = "${formatTime(nowMillis)}  $redacted"
        synchronized(ringLock) {
            if (ringBuffer.size >= MAX_RING_LINES) ringBuffer.removeFirst()
            ringBuffer.addLast(formatted)
        }
        activeSessionLog?.append(formatted)
    }

    fun recentLines(): List<String> = synchronized(ringLock) { ringBuffer.toList() }

    fun clearRing() = synchronized(ringLock) { ringBuffer.clear() }

    fun openSessionLog(context: Context, header: String? = null): SessionLogFile {
        val logFile = File(File(context.filesDir, "logs"), "diplay.log")
        val sessionLog = SessionLogFile(logFile)
        val initialHeader = header ?: (
            "TVPlay log started " +
                "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                "pid=${android.os.Process.myPid()} path=${logFile.absolutePath}"
            )
        runCatching { sessionLog.reset(initialHeader) }
        activeSessionLog = sessionLog
        return sessionLog
    }

    fun buildReport(context: Context, extraHeader: String = ""): String = buildString {
        if (extraHeader.isNotBlank()) {
            appendLine(extraHeader.trimEnd())
        }
        appendLine(StartupDiagnosticSnapshot.report(context))
        appendLine(DisplayDiagnosticSnapshot.report(context))
        appendLine(ProcessExitDiagnostics.report(context))
        appendLine(CrashRecord.report(context))
        val logsDir = File(context.filesDir, "logs")
        for (name in SessionLogFile.REPORT_NAMES) {
            val file = File(logsDir, name)
            if (file.isFile && file.length() > 0L) {
                appendLine("--- $name ---")
                runCatching { append(file.readText(Charsets.UTF_8)) }
                if (!endsWith("\n")) appendLine()
            }
        }
    }

    fun exportReport(
        context: Context,
        extraHeader: String = "",
        fileName: String = defaultReportFileName(),
    ): DiagnosticExportStore.SavedReport {
        activeSessionLog?.awaitIdle(1_500L)
        val report = buildReport(context, extraHeader)
        return DiagnosticExportStore.saveWithoutPicker(context, fileName, report)
    }

    fun defaultReportFileName(nowMillis: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMillis))
        return "TVPlay-diagnostic-$stamp.txt"
    }
}

/** Serves only `<external-files-path>/diagnostic-reports` and `<files-path>/diagnostic-reports`. */
class DiagnosticReportProvider : FileProvider(R.xml.diagnostic_report_paths)

/** Diagnostics describe state transitions; protocol payloads and credentials are never exported. */
object DiagnosticRedactor {
    private val secret = Regex("(?i)(pass(word|phrase)?|token|private.?key|certificate|pair.?record|ssid|body=|payload=|hex=)")
    private val mac = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])")
    private val identifier = Regex("(?i)\\b[0-9a-f]{24,}\\b|\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b|\\b[0-9a-f]{8}-[0-9a-f]{16}\\b")
    private val address = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val namedDevice = Regex("(?i)(phone|device|peer|host)?name[=:]")
    private val ipv6 = Regex("(?i)(?:[0-9a-f]{1,4}:)*[0-9a-f]{0,4}::[0-9a-f:]*(?:%[a-z0-9_.-]+)?|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}")

    /** Cheap check for lines [redact] always rejects, so a queue can refuse them before storing. */
    fun neverExported(line: String): Boolean =
        line.contains("TRACE ") || line.contains("PHONE ") || line.contains('\n') || line.contains('\r')

    fun redact(line: String): String? {
        if (neverExported(line)) return null
        if (secret.containsMatchIn(line) || namedDevice.containsMatchIn(line)) return null
        return line.replace(mac, "[address]").replace(identifier, "[identifier]")
            .replace(address, "[ip]").replace(ipv6, "[ip]").take(700)
    }
}

/** One daemon writer, with at most [maxPending] queued entries plus its active write. */
class BoundedDiagnosticWriter<T : Any>(
    private val maxPending: Int = 64,
    private val write: (T) -> Unit,
) : Closeable {
    private val monitor = Object()
    private val pending = ArrayDeque<T>()
    private var closed = false
    private var active = false
    private var dropped = 0L

    init {
        require(maxPending > 0)
        Thread(::drain, "diplay-diagnostic-writer").apply {
            isDaemon = true
            start()
        }
    }

    /** Never waits for disk. Under overload, discard the oldest pending entry. */
    fun enqueue(entry: T): Boolean = synchronized(monitor) {
        if (closed) return@synchronized false
        if (pending.size == maxPending) {
            pending.removeFirst()
            dropped++
        }
        pending.addLast(entry)
        monitor.notifyAll()
        true
    }

    val pendingCount: Int get() = synchronized(monitor) { pending.size }
    val droppedCount: Long get() = synchronized(monitor) { dropped }

    private fun drain() {
        while (true) {
            val next = synchronized(monitor) {
                while (pending.isEmpty() && !closed) monitor.wait()
                if (pending.isEmpty()) return
                active = true
                pending.removeFirst()
            }
            // Disk failures or a broken diagnostic callback must not stop later writes.
            try {
                runCatching { write(next) }
            } finally {
                synchronized(monitor) {
                    active = false
                    monitor.notifyAll()
                }
            }
        }
    }

    /** For a bounded best-effort flush outside transport/UI callbacks, or deterministic tests. */
    fun awaitIdle(timeoutMillis: Long): Boolean = synchronized(monitor) {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * 1_000_000L
        while (active || pending.isNotEmpty()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return@synchronized false
            monitor.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        }
        true
    }

    /** Stop accepting new entries; drain pending work without waiting on the caller. */
    override fun close() = synchronized(monitor) {
        closed = true
        monitor.notifyAll()
    }
}

/** Bounded, private diagnostics. Each write is redacted before touching storage. */
class SessionLogFile(val file: File) : Closeable {
    private val lock = Any()
    @Volatile private var closed = false
    private var size = UNKNOWN_SIZE
    // Raw lines queue here; the writer thread redacts, rotates and writes them in order.
    private val writer: BoundedDiagnosticWriter<String> = BoundedDiagnosticWriter<String>(MAX_PENDING, ::drain)
    private var reportedDrops = 0L

    fun reset(header: String) = synchronized(lock) {
        if (!closed) {
            file.parentFile?.mkdirs()
            rotate()
            file.writeText("")
            size = 0
            write(header)
        }
    }

    /** Never waits for disk. Under overload the oldest queued line is discarded. */
    fun append(line: String) {
        if (!closed && !DiagnosticRedactor.neverExported(line)) writer.enqueue(line)
    }

    /** Writer thread only. Lines queued before [close] are still written. */
    private fun drain(line: String): Unit = synchronized(lock) {
        val dropped = writer.droppedCount
        if (dropped > reportedDrops) {
            write("${dropped - reportedDrops} lines dropped")
            reportedDrops = dropped
        }
        write(line)
    }

    /** Synchronous write for callers that are already off the hot path. */
    fun appendNow(line: String) = synchronized(lock) { if (!closed) write(line) }

    private fun write(line: String) {
        val safe = DiagnosticRedactor.redact(line) ?: return
        runCatching {
            val bytes = (safe + "\n").toByteArray()
            if (size == UNKNOWN_SIZE) size = file.length()
            if (size > MAX_BYTES) {
                rotate()
                file.writeText("")
                size = 0
            }
            file.appendBytes(bytes)
            size += bytes.size
        }.onFailure { size = UNKNOWN_SIZE }
    }

    fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)

    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
    }

    /** Refuses new lines and waits a bounded time; the writer thread keeps draining what is queued. */
    override fun close() {
        closed = true
        writer.close()
        writer.awaitIdle(CLOSE_FLUSH_MILLIS)
    }

    companion object {
        const val MAX_BYTES = 512 * 1024L
        private const val MAX_PENDING = 4096
        private const val CLOSE_FLUSH_MILLIS = 250L
        private const val UNKNOWN_SIZE = -1L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"
    }
}

/** Queued entries retain only their exact file target and a redacted, bounded metadata string. */
object AsyncDiagnosticLog {
    private data class Entry(val target: SessionLogFile, val line: String)
    private val writer = BoundedDiagnosticWriter<Entry> { it.target.appendNow(it.line) }

    fun append(target: SessionLogFile?, message: String, nowMillis: Long = System.currentTimeMillis()) {
        if (target == null) return
        val safe = DiagnosticRedactor.redact(message) ?: return
        val timestamp = TvDiagnosticsLogger.formatTime(nowMillis)
        runCatching { writer.enqueue(Entry(target, "$timestamp  $safe")) }
    }

    fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)
}

/** Saves an app-owned report without depending on an OEM's document-picker activity. */
object DiagnosticExportStore {
    private const val TAG = "DiagnosticExport"

    data class SavedReport(
        val uri: Uri,
        val savedInApp: Boolean = false,
        val savedPath: String? = null,
    ) {
        val displayPath: String
            get() = savedPath ?: uri.toString()
    }

    /**
     * Saves to the visible Downloads/TVPlay folder whenever the device allows it, so the
     * report can be reached from a file manager or USB. Order:
     *  1. Android 10+: MediaStore Downloads (no permission needed).
     *  2. Direct file in public Downloads/TVPlay: Android 11+ allows it without permission;
     *     Android 9 needs WRITE_EXTERNAL_STORAGE.
     *  3. App-specific external storage (Android/data/<package>).
     *  4. Internal app storage, shared through the report viewer.
     */
    fun saveWithoutPicker(context: Context, fileName: String, report: String): SavedReport {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return SavedReport(saveToDownloads(context.contentResolver, fileName, report))
            } catch (error: Exception) {
                // Preserve the report even when the OEM's public storage provider is absent.
                Log.w(TAG, "MediaStore Downloads unavailable; trying the public folder directly", error)
            }
        }
        if (canWritePublicDownloads(context)) {
            try {
                return saveToPublicDownloads(context, fileName, report)
            } catch (error: Exception) {
                Log.w(TAG, "Public Downloads folder unavailable; saving in app storage", error)
            }
        }
        try {
            // Use Android's package-specific directory, including debug application IDs.
            // No storage permission or document-picker activity is needed.
            val externalFiles = context.getExternalFilesDir(null)
            if (externalFiles != null) {
                return saveInDirectory(context, File(externalFiles, "diagnostic-reports"), fileName, report)
            }
        } catch (_: Exception) {
            // A missing, read-only or full external volume must not prevent export.
        }
        return saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true)
    }

    private fun saveInDirectory(
        context: Context,
        directory: File,
        fileName: String,
        report: String,
        savedInApp: Boolean = false,
    ): SavedReport {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Report storage is unavailable")
        // Each export has a new URI: an earlier share grant cannot read a later report.
        val file = File.createTempFile(fileName.removeSuffix(".txt") + "-", ".txt", directory)
        try {
            file.writeText(report, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
            // Retain only the newest eight reports; never prune the export being returned.
            directory.listFiles()?.filter { it != file && it.isFile }
                ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
            return SavedReport(uri, savedInApp = savedInApp, savedPath = if (savedInApp) null else file.absolutePath)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    /** Android 11+ lets any app create files in Downloads; older versions need the permission. */
    fun canWritePublicDownloads(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.R ||
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /**
     * True only on Android 9, the one version where storage access is what puts the report in
     * the visible Downloads folder. Android 10+ saves there through MediaStore without asking.
     */
    fun needsStoragePermission(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt < Build.VERSION_CODES.Q && !canWritePublicDownloads(context, sdkInt)

    @Suppress("DEPRECATION")
    fun publicDownloadsDirectory(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TVPlay")

    fun saveToPublicDownloads(context: Context, fileName: String, report: String): SavedReport {
        val directory = publicDownloadsDirectory()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Downloads/TVPlay is unavailable")
        val base = fileName.removeSuffix(".txt")
        var file = File(directory, fileName)
        var copy = 1
        while (file.exists()) file = File(directory, "$base-${copy++}.txt")
        try {
            file.writeText(report, Charsets.UTF_8)
            if (file.length() == 0L && report.isNotEmpty()) throw IOException("Downloads/TVPlay did not keep the report")
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        // The report is saved; nothing below may undo that. The user's Downloads folder is never pruned.
        runCatching {
            MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.absolutePath), arrayOf("text/plain"), null)
        }
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
        } catch (error: IllegalArgumentException) {
            // Some storage layouts resolve Downloads outside the provider's external root.
            // Share a private copy instead; the visible file in Downloads/TVPlay stays.
            Log.w(TAG, "Downloads/TVPlay is not shareable through the provider; sharing a private copy", error)
            privateShareCopy(context, fileName, report)
        }
        return SavedReport(uri, savedPath = file.absolutePath)
    }

    private fun privateShareCopy(context: Context, fileName: String, report: String): Uri {
        val external = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        if (external != null) {
            runCatching { return saveInDirectory(context, File(external, "diagnostic-reports"), fileName, report).uri }
        }
        return saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true).uri
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(resolver: ContentResolver, fileName: String, report: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/TVPlay")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads could not create the report")
        try {
            write(resolver, uri, report)
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Downloads could not publish the report")
            return uri
        } catch (error: Exception) {
            // Only remove the entry created by this call; never leave a partial report behind.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    fun write(resolver: ContentResolver, uri: Uri, report: String) {
        val stream = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Report destination is unavailable")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
    }
}

/**
 * Keeps TVPlay's last uncaught exception so the next diagnostic report shows where it crashed.
 */
object CrashRecord {
    private const val FILE = "logs/last-crash.txt"
    private const val MAX_CAUSES = 4
    private const val MAX_FRAMES = 12
    private const val MAX_OWN_FRAMES = 6
    private const val MAX_MESSAGE = 120
    private var installed = false

    @Synchronized fun install(context: Context) {
        if (installed) return
        installed = true
        val file = File(context.applicationContext.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Recording must never replace the crash itself.
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(format(thread.name, error, System.currentTimeMillis()))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun format(threadName: String, error: Throwable, atMillis: Long): String = buildString {
        appendLine("crash atEpochMs=$atMillis thread=${threadName.take(40)}")
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        while (current != null && seen.size < MAX_CAUSES && seen.add(current)) {
            val message = current.message?.lineSequence()?.firstOrNull()?.take(MAX_MESSAGE)
            appendLine((if (seen.size == 1) "" else "caused by ") + current.javaClass.name + (message?.let { ": $it" } ?: ""))
            val frames = current.stackTrace
            frames.take(MAX_FRAMES).forEach { appendLine("  at $it") }
            val rest = frames.drop(MAX_FRAMES)
            val own = rest.filter(::ownFrame).take(MAX_OWN_FRAMES)
            if (own.isNotEmpty()) {
                appendLine("  ... DiPlay frames further down:")
                own.forEach { appendLine("  at $it") }
            }
            if (rest.size > own.size) appendLine("  ... ${rest.size - own.size} more")
            current = current.cause
        }
    }

    private fun ownFrame(frame: StackTraceElement): Boolean =
        frame.className.startsWith("app.tvplay.") || frame.className.startsWith("com.shilapi.") || frame.className.startsWith("com.shihab.")

    fun report(context: Context): String {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return "No crash recorded by this version."
        return runCatching { file.readLines().mapNotNull(DiagnosticRedactor::redact).joinToString("\n") }
            .getOrElse { "Crash record unreadable: ${it.javaClass.simpleName}" }
    }
}

/** Installs [CrashRecord] when the process starts, before any activity, receiver or service runs. */
class CrashRecordInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(CrashRecord::install)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/** Own-app numeric exit metadata only; never read traces, descriptions or process state blobs. */
object ProcessExitDiagnostics {
    private const val MAX_RECORDS = 3
    private const val UNSUPPORTED = "Process exits available=false requiresApi=30"

    data class Record(
        val timestampMillis: Long,
        val reason: Int,
        val status: Int,
        val importance: Int,
        val pssKiB: Long,
        val rssKiB: Long,
    )

    fun report(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return UNSUPPORTED
        return report(Build.VERSION.SDK_INT, System.currentTimeMillis()) {
            val manager = context.getSystemService(ActivityManager::class.java)
                ?: throw IllegalStateException()
            manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS).take(MAX_RECORDS).map {
                Record(it.timestamp, it.reason, it.status, it.importance, it.pss, it.rss)
            }
        }
    }

    fun report(sdkInt: Int, nowMillis: Long, read: () -> List<Record>): String {
        if (sdkInt < Build.VERSION_CODES.R) return UNSUPPORTED
        val records = try {
            read().take(MAX_RECORDS)
        } catch (error: Exception) {
            return unavailable(error)
        } catch (error: LinkageError) {
            return unavailable(error)
        }
        if (records.isEmpty()) return "Process exits available=true count=0; no retained exit record."
        return buildString {
            append("Process exits available=true count=${records.size}; historical records may predate this session.")
            records.forEachIndexed { index, record ->
                val age = if (record.timestampMillis > 0 && record.timestampMillis <= nowMillis) {
                    (nowMillis - record.timestampMillis).toString()
                } else "unknown"
                append("\nProcess exit index=$index ageMs=$age reason=${reason(record.reason)} " +
                    "reasonCode=${record.reason} status=${record.status} importance=${record.importance} " +
                    "pssKiB=${record.pssKiB.coerceAtLeast(0)} rssKiB=${record.rssKiB.coerceAtLeast(0)}")
            }
        }
    }

    private fun unavailable(error: Throwable): String {
        val type = error.javaClass.simpleName.take(80).replace(Regex("[^A-Za-z0-9_$]"), "?")
        return "Process exits available=false error=$type"
    }

    // Stable public Android 11 reason codes. Keep newer/unknown values visible as numbers.
    private fun reason(code: Int) = when (code) {
        0 -> "unknown"
        1 -> "self_exit"
        2 -> "signal"
        3 -> "low_memory"
        4 -> "java_crash"
        5 -> "native_crash"
        6 -> "anr"
        7 -> "initialization_failure"
        8 -> "permission_change"
        9 -> "excessive_resources"
        10 -> "user_requested"
        11 -> "user_stopped"
        12 -> "dependency_died"
        13 -> "other"
        else -> "unknown"
    }
}

/** Only the latest received boot event and launch result; no Intent extras or device identifiers. */
object StartupDiagnosticSnapshot {
    private const val PREFS = "diplay_startup_diagnostics"

    fun received(context: Context, launchEnabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("receivedAt", System.currentTimeMillis())
                .putBoolean("launchEnabled", launchEnabled)
                .putString("launchResult", if (launchEnabled) "pending" else "disabled")
                .remove("failureClass").apply()
        }
    }

    fun launchResult(context: Context, failure: RuntimeException? = null) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("launchResult", if (failure == null) "startActivity-returned" else "failed")
                .putString("failureClass", failure?.javaClass?.simpleName?.take(80)
                    ?.replace(Regex("[^A-Za-z0-9_$]"), "?")).apply()
        }
    }

    fun report(context: Context): String = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val receivedAt = prefs.getLong("receivedAt", 0)
        if (receivedAt == 0L) {
            "Boot diagnostics received=false; no boot delivery has been recorded by this version."
        } else {
            "Boot diagnostics received=true receivedAtEpochMs=$receivedAt " +
                "launchEnabledAtBoot=${prefs.getBoolean("launchEnabled", false)} " +
                "launchResult=${prefs.getString("launchResult", "unknown")} " +
                "failureClass=${prefs.getString("failureClass", "none") ?: "none"}; " +
                "startActivity-returned does not prove the OEM displayed the app."
        }
    }.getOrDefault("Boot diagnostics unavailable.")
}

/** Small, redacted snapshot retained separately from the rotating session logs. */
object DisplayDiagnosticSnapshot {
    private const val PREFS = "diplay_display_diagnostics"
    private val fields = listOf("selection", "request", "capability", "effective", "phone", "decoder", "output")
    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    @Synchronized
    fun selection(context: Context, previous: Int, selected: Int, reconnect: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("selection",
            "${stamp()} Size setting previous=$previous% selected=$selected% reconnect=$reconnect").apply()
    }

    @Synchronized
    fun begin(context: Context, request: String, capability: String, effective: String): String {
        val attempt = UUID.randomUUID().toString()
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        fields.filter { it != "selection" }.forEach(editor::remove)
        editor.putString("attempt", attempt)
        for ((key, line) in listOf("request" to request, "capability" to capability, "effective" to effective)) {
            DiagnosticRedactor.redact(line)?.let { editor.putString(key, "${stamp()} $it") }
        }
        editor.apply()
        return attempt
    }

    @Synchronized
    fun currentAttempt(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("attempt", null)

    @Synchronized
    fun record(context: Context, attempt: String?, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (attempt == null || prefs.getString("attempt", null) != attempt) return
        val field = when {
            message.startsWith("Phone display peer ") -> "phone"
            message.startsWith("Video: decoder=") || message.startsWith("Video: decoder configuration failed") -> "decoder"
            message.startsWith("Video: output format ") -> "output"
            else -> return
        }
        val safe = DiagnosticRedactor.redact(message) ?: return
        prefs.edit().putString(field, "${stamp()} $safe").apply()
    }

    @Synchronized
    fun report(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return fields.mapNotNull { prefs.getString(it, null)?.let(DiagnosticRedactor::redact) }
            .joinToString("\n").ifEmpty { "No display negotiation recorded yet." }
    }
}

/** Samples only Android's existing theme signal; never infers a theme from vehicle state. */
class ThemeModeDiagnostics {
    enum class Source(val label: String, val force: Boolean = false) {
        CARPLAY_MODE("carplay-mode"),
        CREATE("create", true),
        START("start", true),
        RESUME("resume", true),
        STOP("stop", true),
        POLL("poll"),
        CALLBACK("configuration-callback"),
        CONFIGURATION_CHANGED("configuration-callback"),
        WINDOW_FOCUS("window-focus"),
        SESSION_ACTIVE("session-active", true),
    }

    private data class State(val uiMode: Int, val appliedNight: Boolean, val sessionActive: Boolean)

    private var lastLoggedState: State? = null
    private var lastLoggedAt = 0L
    private var polls = 0
    private var callbacks = 0

    fun observe(
        source: Source,
        uiMode: Int,
        appliedNight: Boolean,
        sessionActive: Boolean,
        elapsedMillis: Long = android.os.SystemClock.elapsedRealtime(),
    ): String? {
        if (source == Source.POLL) polls++
        if (source == Source.CALLBACK || source == Source.CONFIGURATION_CHANGED) callbacks++
        val state = State(uiMode, appliedNight, sessionActive)
        if (!source.force && state == lastLoggedState && elapsedMillis - lastLoggedAt < 60_000L) return null

        val reported = when (nightModeOrNull(uiMode)) {
            true -> "dark"
            false -> "light"
            null -> "undefined"
        }
        val nightMask = uiMode and Configuration.UI_MODE_NIGHT_MASK
        val message = "THEME_DIAGNOSTIC sample source=${source.label} uiMode=0x${uiMode.toString(16)} " +
            "nightMask=0x${nightMask.toString(16)} reported=$reported " +
            "applied=${if (appliedNight) "dark" else "light"} sessionActive=$sessionActive " +
            "pollsSinceSample=$polls callbacksSinceSample=$callbacks"
        lastLoggedState = state
        lastLoggedAt = elapsedMillis
        polls = 0
        callbacks = 0
        return message
    }
}

internal fun nightModeOrNull(uiMode: Int): Boolean? =
    when (uiMode and Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_YES -> true
        Configuration.UI_MODE_NIGHT_NO -> false
        else -> null
    }
