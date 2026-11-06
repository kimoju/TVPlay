package app.tvplay.engine.protocol.hicar

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

/**
 * Huawei HiCar (`0xFE35` BLE Faster Pair advertisement & HarmonyOS session framing).
 *
 * Builds the BLE Nearby / Faster Pair service data payload on `0000FE35-0000-1000-8000-00805F9B34FB`
 * so Huawei / Honor HarmonyOS phones detect the head unit in HiCar Smart Mobility.
 */
object HiCarProtocol {
    val ADV_SERVICE_UUID: UUID = UUID.fromString("0000FE35-0000-1000-8000-00805F9B34FB")
    const val HICAR_SUB_SERVICE_ID: Byte = 0x01
    const val DEFAULT_TX_POWER: Byte = (-59).toByte()

    /**
     * Builds the Huawei HiCar `0xFE35` BLE ServiceData advertisement payload.
     *
     * Layout:
     * - `[0]`: Version & Sub-service (`0x11` = HiCar Faster Pair v1)
     * - `[1..3]`: 3-byte Model ID (e.g. `0x00, 0x04, 0x01`)
     * - `[4]`: Reference Tx Power (`-59 dBm` = `0xC5`)
     * - `[5..10]`: 6-byte Car Bluetooth MAC address
     */
    fun buildBleServiceData(
        carBtMacHex12: String,
        modelId3Bytes: Int = 0x000401,
        txPower: Byte = DEFAULT_TX_POWER,
    ): ByteArray {
        val cleanMac = carBtMacHex12.replace(":", "").replace("-", "").trim().uppercase(Locale.US)
        require(cleanMac.length == 12) { "carBtMacHex12 must be 12 hex characters: $carBtMacHex12" }
        val macBytes = ByteArray(6) { idx ->
            cleanMac.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
        val out = ByteArrayOutputStream(11)
        out.write(0x11)
        out.write((modelId3Bytes ushr 16) and 0xFF)
        out.write((modelId3Bytes ushr 8) and 0xFF)
        out.write(modelId3Bytes and 0xFF)
        out.write(txPower.toInt() and 0xFF)
        out.write(macBytes)
        return out.toByteArray()
    }
}
