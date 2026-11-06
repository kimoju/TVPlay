package app.tvplay.engine.protocol.carlink

/**
 * ICCOA CarLink (`com.ucar.protocol.Crc16`) CRC-16/ARC implementation.
 *
 * Uses reflected polynomial `0xA001` (init `0x0000`), verified against `ucar-sdk` bytecode:
 * `crc = (crc ushr 8) xor TABLE[(crc xor (b and 0xFF)) and 0xFF]`.
 */
object CarLinkCrc16 {
    private val TABLE = IntArray(256) { index ->
        var crc = index
        repeat(8) {
            crc = if ((crc and 1) != 0) {
                (crc ushr 1) xor 0xA001
            } else {
                crc ushr 1
            }
        }
        crc and 0xFFFF
    }

    fun checksum(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "Invalid slice offset=$offset length=$length size=${data.size}"
        }
        var crc = 0
        for (i in offset until offset + length) {
            val idx = (crc xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = ((crc ushr 8) xor TABLE[idx]) and 0xFFFF
        }
        return crc and 0xFFFF
    }

    fun checksumShort(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Short =
        checksum(data, offset, length).toShort()
}
