package app.tvplay.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvQrCodeRendererTest {

    @Test
    fun `buildWifiQrPayload formats WPA and escapes special characters`() {
        val payload = TvQrCodeRenderer.buildWifiQrPayload("DIRECT-vs-TVPlay", "k9X2mP4q")
        assertEquals("WIFI:T:WPA;S:DIRECT-vs-TVPlay;P:k9X2mP4q;;", payload)

        val escaped = TvQrCodeRenderer.buildWifiQrPayload("My;Home:WiFi", "p\\ass;word")
        assertEquals("WIFI:T:WPA;S:My\\;Home\\:WiFi;P:p\\\\ass\\;word;;", escaped)
    }

    @Test
    fun `encodeMatrix generates valid ISO 18004 finder patterns and dimensions`() {
        val v1 = TvQrCodeRenderer.encodeMatrix("PIN:864209")
        assertNotNull(v1)
        assertEquals(21, v1!!.size)

        val wifiPayload = TvQrCodeRenderer.buildWifiQrPayload("DIRECT-vs-TVPlay", "86420912")
        val matrix = TvQrCodeRenderer.encodeMatrix(wifiPayload)
        assertNotNull(matrix)
        val size = matrix!!.size
        assertTrue(size in 25..37)

        // Top-left, top-right, bottom-left finder centers must be dark
        assertTrue(matrix[3][3])
        assertTrue(matrix[3][size - 4])
        assertTrue(matrix[size - 4][3])
        // Always-dark module at (8, size - 8)
        assertTrue(matrix[size - 8][8])
    }
}
