package app.tvplay.engine.protocol.carlink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ICCOA CarLink BLE advertiser and GATT Server (`com.share.connect.ble.BleAdvertisementBuilder`
 * & `com.share.connect.ble.BluetoothLeService`).
 *
 * Advertises service UUID `0xFCFB` with Car Info 1 (`0x0001`, 16/18 bytes) and Scan Response
 * Car Info 2 (`0x0002`, UTF-8 car name <= 16 bytes), rotates the 1-byte serial number every 30s
 * so Xiaomi HyperOS CarWith / vivo Jovi InCar / OPPO Car+ prompts the pairing dialog, and runs
 * the GATT Server (`2abcc850-9935-4f8a-ba84-123456789100`) to exchange Wi-Fi P2P / SoftAP / LAN
 * connection credentials with the phone.
 */
object CarLinkBleSpec {
    val ADV_SERVICE_UUID: UUID = UUID.fromString("0000FCFB-0000-1000-8000-00805F9B34FB")
    val ADV_CAR_INFO_1_UUID: UUID = UUID.fromString("00000001-0000-1000-8000-00805F9B34FB")
    val ADV_CAR_INFO_2_UUID: UUID = UUID.fromString("00000002-0000-1000-8000-00805F9B34FB")

    val SHARE_SERVICE_UUID: UUID = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789100")
    val CLIENT_INFO_CHARACTERISTIC_UUID: UUID = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789101")
    val SERVER_CONNECTION_INFO_CHARACTERISTIC_UUID: UUID = UUID.fromString("2abcc850-9935-4f8a-ba84-123456789102")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val DEFAULT_PROTOCOL_VERSION = "1.0"
    const val DEFAULT_RSSI_THRESHOLD = -60
    const val ROTATION_INTERVAL_MS = 30_000L

    data class ClientInfo(
        val id: String,
        val name: String,
        val model: String,
        val band: Int,
        val mac: String,
        val type: Int = 1001,
        val manufacturer: String? = null,
        val channel: Int = 0,
    )

    data class ServerConnectionInfo(
        val name: String,
        val ssid: String,
        val psk: String,
        val mac: String,
        val freq: Int,
        val port: Int = 0,
        val type: Int = 1,
    )

    fun encodeVersion(version: String = DEFAULT_PROTOCOL_VERSION): ByteArray {
        val parts = version.trim().split(".")
        require(parts.size == 2) { "Protocol version must be M.m: $version" }
        val major = parts[0].toInt()
        val minor = parts[1].toInt()
        require(major in 0..255 && minor in 0..255) { "Version octets out of range: $version" }
        return byteArrayOf(major.toByte(), minor.toByte())
    }

    fun decodeHexBytes(hex: String, expectedBytes: Int): ByteArray {
        val clean = hex.replace(":", "").replace("-", "").trim()
        require(clean.length == expectedBytes * 2) {
            "Expected ${expectedBytes * 2} hex chars, got '${hex}'"
        }
        return ByteArray(expectedBytes) { idx ->
            clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    }

    fun encodeCarName(carName: String): ByteArray =
        CarLinkAoaPreambleBuilder.truncateCarName(carName, maxBytes = 16).toByteArray(Charsets.UTF_8)

    fun encodePhoneBtMacCrc16(phoneBtMac: String?): ByteArray {
        val clean = phoneBtMac?.trim()?.takeIf { it.isNotEmpty() } ?: return ByteArray(0)
        val normalized = if (clean.contains(":")) {
            clean.uppercase(Locale.US)
        } else if (clean.length == 12) {
            clean.uppercase(Locale.US).chunked(2).joinToString(":")
        } else {
            clean
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        val crc = CarLinkCrc16.checksumShort(sha256, 0, sha256.size)
        return ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(crc).array()
    }

    /**
     * Builds the `ADV_CAR_INFO_1_UUID` (`0x0001`) service data byte array (16 or 18 bytes).
     */
    fun buildCarInfo1Payload(
        carIdHex12: String,
        serialNumber: Int,
        modelIdHex8: String = "00010001",
        vendorDataHex4: String = "0000",
        version: String = DEFAULT_PROTOCOL_VERSION,
        rssiThreshold: Int = DEFAULT_RSSI_THRESHOLD,
        targetPhoneBtMac: String? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream(18)
        out.write(encodeVersion(version))
        out.write(serialNumber and 0xFF)
        out.write(decodeHexBytes(carIdHex12, 6))
        out.write(decodeHexBytes(modelIdHex8, 4))
        out.write(decodeHexBytes(vendorDataHex4, 2))
        out.write(rssiThreshold and 0xFF)
        out.write(encodePhoneBtMacCrc16(targetPhoneBtMac))
        return out.toByteArray()
    }

    /**
     * Builds the `ADV_CAR_INFO_2_UUID` (`0x0002`) scan response service data byte array (<= 16 bytes).
     */
    fun buildCarInfo2Payload(carName: String): ByteArray = encodeCarName(carName)

    fun parseClientInfoJson(jsonString: String): ClientInfo {
        fun extractString(key: String, default: String = ""): String {
            val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"")
            return regex.find(jsonString)?.groupValues?.getOrNull(1) ?: default
        }
        fun extractInt(key: String, default: Int = 0): Int {
            val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?[0-9]+)")
            return regex.find(jsonString)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: default
        }
        return ClientInfo(
            id = extractString("id", ""),
            name = extractString("name", "Android Phone"),
            model = extractString("model", "Android"),
            band = extractInt("band", 5),
            mac = extractString("mac", ""),
            type = extractInt("type", 1001),
            manufacturer = extractString("manufactures", "").takeIf { it.isNotBlank() },
            channel = extractInt("channel", 0),
        )
    }

    fun buildServerInfoJson(info: ServerConnectionInfo): String {
        fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return buildString {
            append('{')
            append("\"name\":\"").append(escape(info.name)).append("\",")
            append("\"ssid\":\"").append(escape(info.ssid)).append("\",")
            append("\"psk\":\"").append(escape(info.psk)).append("\",")
            append("\"mac\":\"").append(escape(info.mac)).append("\",")
            append("\"freq\":").append(info.freq).append(',')
            append("\"port\":").append(info.port).append(',')
            append("\"type\":").append(info.type)
            append('}')
        }
    }
}

@SuppressLint("MissingPermission")
class CarLinkBleAdvertiser(
    private val context: Context,
    private val carIdHex12: String,
    private val carName: String,
    private val modelIdHex8: String = "00010001",
    private val vendorDataHex4: String = "0000",
    private val pinCodeProvider: () -> String = { "864209" },
    private val isSessionActiveProvider: () -> Boolean = { false },
    private val serverInfoProvider: () -> CarLinkBleSpec.ServerConnectionInfo?,
    private val onClientConnected: (BluetoothDevice, CarLinkBleSpec.ClientInfo) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val serialCounter = AtomicInteger(1)
    private val handler = Handler(Looper.getMainLooper())
    private val writeFragments = ConcurrentHashMap<String, ByteArrayOutputStream>()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var activeAdvertiseCallback: AdvertiseCallback? = null

    private val rotateTask = object : Runnable {
        override fun run() {
            if (closed.get()) return
            if (!isSessionActiveProvider()) {
                serialCounter.updateAndGet { current -> (current + 1) and 0xFF }
                restartAdvertisingInternal()
            }
            handler.postDelayed(this, CarLinkBleSpec.ROTATION_INTERVAL_MS)
        }
    }

    /**
     * Immediately stops and restarts BLE advertising with an incremented `serialNumber`
     * so Xiaomi CarWith / OPPO Car+ / vivo Jovi InCar re-triggers the pairing prompt.
     */
    fun refreshPairingAdvertisement() {
        if (closed.get() || isSessionActiveProvider()) return
        serialCounter.updateAndGet { current -> (current + 1) and 0xFF }
        handler.removeCallbacks(rotateTask)
        restartAdvertisingInternal()
        handler.postDelayed(rotateTask, CarLinkBleSpec.ROTATION_INTERVAL_MS)
    }

    fun start(): Boolean {
        if (closed.get()) return false
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        val adapter = btManager.adapter ?: return false
        if (!adapter.isEnabled) {
            onLog("CarLink BLE: Bluetooth adapter disabled")
            return false
        }
        startGattServer(btManager)
        val started = restartAdvertisingInternal(adapter)
        handler.removeCallbacks(rotateTask)
        handler.postDelayed(rotateTask, CarLinkBleSpec.ROTATION_INTERVAL_MS)
        return started
    }

    private fun startGattServer(btManager: BluetoothManager) {
        if (gattServer != null) return
        val server = runCatching {
            btManager.openGattServer(context, gattCallback)
        }.onFailure { e ->
            onLog("CarLink BLE: openGattServer failed: ${e.message}")
        }.getOrNull() ?: return

        val service = BluetoothGattService(
            CarLinkBleSpec.SHARE_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val clientInfoChar = BluetoothGattCharacteristic(
            CarLinkBleSpec.CLIENT_INFO_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val serverInfoChar = BluetoothGattCharacteristic(
            CarLinkBleSpec.SERVER_CONNECTION_INFO_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val cccd = BluetoothGattDescriptor(
            CarLinkBleSpec.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        serverInfoChar.addDescriptor(cccd)
        service.addCharacteristic(clientInfoChar)
        service.addCharacteristic(serverInfoChar)
        runCatching { server.addService(service) }
            .onFailure { e -> onLog("CarLink BLE: addService failed: ${e.message}") }
        gattServer = server
    }

    private fun restartAdvertisingInternal(
        adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter,
    ): Boolean {
        if (closed.get() || adapter == null || !adapter.isEnabled) return false
        val leAdvertiser = runCatching { adapter.bluetoothLeAdvertiser }.getOrNull() ?: return false
        advertiser = leAdvertiser
        activeAdvertiseCallback?.let { cb ->
            runCatching { leAdvertiser.stopAdvertising(cb) }
        }

        val serial = serialCounter.get() and 0xFF
        val info1 = CarLinkBleSpec.buildCarInfo1Payload(
            carIdHex12 = carIdHex12,
            serialNumber = serial,
            modelIdHex8 = modelIdHex8,
            vendorDataHex4 = vendorDataHex4,
        )
        val info2 = CarLinkBleSpec.buildCarInfo2Payload(carName)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(CarLinkBleSpec.ADV_SERVICE_UUID))
            .addServiceData(ParcelUuid(CarLinkBleSpec.ADV_CAR_INFO_1_UUID), info1)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(CarLinkBleSpec.ADV_CAR_INFO_2_UUID), info2)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                onLog("CarLink BLE: advertising 0xFCFB (serial=$serial, carId=$carIdHex12, name=$carName)")
            }

            override fun onStartFailure(errorCode: Int) {
                onLog("CarLink BLE: advertising start failed errorCode=$errorCode")
            }
        }
        activeAdvertiseCallback = callback
        return runCatching {
            leAdvertiser.startAdvertising(settings, advertiseData, scanResponse, callback)
            true
        }.getOrElse { e ->
            onLog("CarLink BLE: startAdvertising exception: ${e.message}")
            false
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                writeFragments.remove(device.address)
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                onLog("CarLink BLE: phone GATT connected ${device.address}")
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }
            if (descriptor.uuid == CarLinkBleSpec.CCCD_UUID) {
                notifyServerConnectionInfo(device)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == CarLinkBleSpec.SERVER_CONNECTION_INFO_CHARACTERISTIC_UUID) {
                val payload = buildServerInfoBytes()
                val slice = if (offset >= payload.size) ByteArray(0) else payload.copyOfRange(offset, payload.size)
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
                }
            } else {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val bytes = value ?: ByteArray(0)
            if (characteristic.uuid == CarLinkBleSpec.CLIENT_INFO_CHARACTERISTIC_UUID) {
                if (preparedWrite) {
                    val stream = writeFragments.getOrPut(device.address) { ByteArrayOutputStream() }
                    stream.write(bytes)
                } else {
                    val combined = writeFragments.remove(device.address)?.let { stream ->
                        stream.write(bytes)
                        stream.toByteArray()
                    } ?: bytes
                    handleClientInfoBytes(device, combined)
                }
            }
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes)
                }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buffer = writeFragments.remove(device.address)
            if (execute && buffer != null) {
                handleClientInfoBytes(device, buffer.toByteArray())
            }
            runCatching {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    private fun handleClientInfoBytes(device: BluetoothDevice, bytes: ByteArray) {
        val jsonStr = bytes.toString(Charsets.UTF_8).trim()
        onLog("CarLink BLE: received ClientInfo from ${device.address}: $jsonStr")
        val clientInfo = runCatching { CarLinkBleSpec.parseClientInfoJson(jsonStr) }.getOrNull()
        if (clientInfo != null) {
            onClientConnected(device, clientInfo)
        }
        notifyServerConnectionInfo(device)
    }

    private fun buildServerInfoBytes(): ByteArray {
        val info = serverInfoProvider() ?: return ByteArray(0)
        return CarLinkBleSpec.buildServerInfoJson(info).toByteArray(Charsets.UTF_8)
    }

    @Suppress("DEPRECATION")
    fun notifyServerConnectionInfo(device: BluetoothDevice) {
        val server = gattServer ?: return
        val service = server.getService(CarLinkBleSpec.SHARE_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CarLinkBleSpec.SERVER_CONNECTION_INFO_CHARACTERISTIC_UUID) ?: return
        val payload = buildServerInfoBytes()
        if (payload.isEmpty()) return
        runCatching {
            characteristic.value = payload
            server.notifyCharacteristicChanged(device, characteristic, false)
            onLog("CarLink BLE: notified ServerConnectionInfo (${payload.size}B) to ${device.address}")
        }.onFailure { e ->
            onLog("CarLink BLE: notifyServerConnectionInfo failed: ${e.message}")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacks(rotateTask)
        val cb = activeAdvertiseCallback
        activeAdvertiseCallback = null
        if (cb != null) {
            runCatching { advertiser?.stopAdvertising(cb) }
        }
        advertiser = null
        runCatching { gattServer?.close() }
        gattServer = null
        writeFragments.clear()
    }
}
