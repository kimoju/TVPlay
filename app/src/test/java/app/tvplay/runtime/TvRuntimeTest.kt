package app.tvplay.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRuntimeTest {

    @Test
    fun diagnosticRedactorStripsPrivateAddressesAndCredentialsFromLogs() {
        TvDiagnosticsLogger.clearRing()
        TvDiagnosticsLogger.record("Connected to 192.168.1.42 over AA:BB:CC:DD:EE:FF")
        val lines = TvDiagnosticsLogger.recentLines()
        assertEquals(1, lines.size)
        val line = lines.first()
        assertFalse("192.168.1.42" in line)
        assertFalse("AA:BB:CC:DD:EE:FF" in line)
    }

    @Test
    fun diagnosticRedactorRetainsProtocolMilestones() {
        val redacted = DiagnosticRedactor.redact("RTSP SETUP completed on stream 110")
        assertNotNull(redacted)
        assertTrue("RTSP SETUP" in redacted!!)
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
