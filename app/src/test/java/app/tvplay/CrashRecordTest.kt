package app.tvplay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CrashRecordTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun theRecordNamesTheExceptionChainAndWhereItWasThrown() {
        val cause = IllegalStateException("surface released at 192.168.1.20")
        val crash = RuntimeException("Unable to start activity\nsecond line", cause)
        crash.stackTrace = Array(20) { StackTraceElement("app.tvplay.TvPlayWorkbenchActivity", "onCreate$it", "TvPlayWorkbenchActivity.kt", it) }

        val record = CrashRecord.format("main", crash, 1_000L)

        val lines = record.lines()
        assertEquals("crash atEpochMs=1000 thread=main", lines[0])
        assertEquals("java.lang.RuntimeException: Unable to start activity", lines[1])
        assertEquals("  at app.tvplay.TvPlayWorkbenchActivity.onCreate0(TvPlayWorkbenchActivity.kt:0)", lines[2])
        assertTrue(record.contains("  at app.tvplay.TvPlayWorkbenchActivity.onCreate17(TvPlayWorkbenchActivity.kt:17)"))
        assertTrue(record.contains("  ... 2 more"))
        assertTrue(record.contains("caused by java.lang.IllegalStateException: surface released at 192.168.1.20"))
        assertFalse(record.contains("second line"))
    }

    // #463: Android's startActivity frames filled the record and hid the DiPlay call that crashed.
    @Test fun ourFramesBelowTheFirstOnesAreKept() {
        val framework = Array(14) { StackTraceElement("android.app.Activity", "startActivity$it", "Activity.java", it) }
        val ours = Array(9) { StackTraceElement("app.tvplay.TvPlayWorkbenchActivity", "open$it", "TvPlayWorkbenchActivity.kt", it) }
        val crash = IllegalArgumentException("There is no running activity in rootTask")
        crash.stackTrace = framework + ours + StackTraceElement("android.os.Looper", "loop", "Looper.java", 1)

        val record = CrashRecord.format("main", crash, 0)

        assertTrue(record.contains("  at android.app.Activity.startActivity11(Activity.java:11)"))
        assertFalse(record.contains("startActivity12"))
        assertTrue(record.contains("  ... DiPlay frames further down:"))
        assertTrue(record.contains("  at app.tvplay.TvPlayWorkbenchActivity.open0(TvPlayWorkbenchActivity.kt:0)"))
        assertTrue(record.contains("  at app.tvplay.TvPlayWorkbenchActivity.open5(TvPlayWorkbenchActivity.kt:5)"))
        assertFalse(record.contains("open6"))
        assertTrue(record.contains("  ... 6 more"))
    }

    @Test fun aCauseLoopEndsTheChain() {
        val first = RuntimeException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)

        assertEquals(2, CrashRecord.format("worker", first, 0).lines().count { it.startsWith("java.") || it.startsWith("caused by") })
    }

    @Test fun theReportShowsTheRedactedRecordOrSaysThereIsNone() {
        val file = File(context.filesDir, "logs/last-crash.txt")
        file.delete()
        assertEquals("No crash recorded by this version.", CrashRecord.report(context))

        file.parentFile?.mkdirs()
        file.writeText(CrashRecord.format("main", IllegalStateException("surface released at 192.168.1.20"), 5L))
        val report = CrashRecord.report(context)
        assertTrue(report, report.startsWith("crash atEpochMs=5 thread=main\njava.lang.IllegalStateException: surface released at [ip]"))
    }
}
