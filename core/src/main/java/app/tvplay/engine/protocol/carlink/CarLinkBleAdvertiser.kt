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
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

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

    const val DEFAULT_PROTOCOL_VERSION = "1.5"
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

    const val WIFI_DEFAULT_MAC_ADDRESS = "02:00:00:00:00:00"
    const val WIFI_TYPE_P2P = 1001
    const val WIFI_TYPE_SOFT_AP = 1002

    data class ServerConnectionInfo(
        val name: String,
        val ssid: String,
        val psk: String,
        val mac: String = WIFI_DEFAULT_MAC_ADDRESS,
        val freq: Int,
        val port: Int = 0,
        val type: Int = WIFI_TYPE_P2P,
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

    /**
     * Encodes the car name into a fixed 16-byte UTF-8 array padded with trailing `0x00` bytes
     * (`BleAdvertisementBuilder.encodeCarName`).
     */
    fun encodeCarName(carName: String): ByteArray {
        val raw = CarLinkAoaPreambleBuilder.truncateCarName(carName, maxBytes = 16).toByteArray(Charsets.UTF_8)
        val padded = ByteArray(16)
        System.arraycopy(raw, 0, padded, 0, minOf(raw.size, padded.size))
        return padded
    }

    /**
     * Computes `BleUtils.getCrc16(SHA-256(phoneBtMac))` (lower 16 bits of `CRC32` in big-endian).
     */
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
        val crc32 = CRC32().apply { update(sha256) }
        val crcValue = crc32.value.toInt()
        return byteArrayOf(
            ((crcValue ushr 8) and 0xFF).toByte(),
            (crcValue and 0xFF).toByte(),
        )
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
    private val carName: String = CarLinkProtoCodec.DEFAULT_CAR_PRODUCT_NAME,
    private val carShortName: String = CarLinkProtoCodec.DEFAULT_CAR_SHORT_NAME,
    private val modelIdHex8: String = "00010001",
    private val vendorDataHex4: String = "0000",
    private val pinCodeProvider: () -> String = { "864209" },
    private val isSessionActiveProvider: () -> Boolean = { false },
    private val serverInfoProvider: () -> CarLinkBleSpec.ServerConnectionInfo?,
    private val onClientConnected: (BluetoothDevice, CarLinkBleSpec.ClientInfo) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {},
) : Closeable {
    private class PreparedWriteBuffer {
        val data = ByteArray(1024)
        var length = 0

        fun append(offset: Int, bytes: ByteArray) {
            if (offset < 0 || offset >= data.size || bytes.isEmpty()) return
            val copyLen = minOf(bytes.size, data.size - offset)
            System.arraycopy(bytes, 0, data, offset, copyLen)
            length = maxOf(length, offset + copyLen)
        }

        fun toByteArray(): ByteArray = data.copyOfRange(0, length)
    }

    private val closed = AtomicBoolean(false)
    private val serialPrefs by lazy {
        runCatching { context.getSharedPreferences("serial_num_prefs", Context.MODE_PRIVATE) }.getOrNull()
    }
    private val serialCounter = AtomicInteger(initSerialNumber())
    private val handler = Handler(Looper.getMainLooper())
    private val writeFragments = ConcurrentHashMap<String, PreparedWriteBuffer>()
    private val pendingClients = ConcurrentHashMap<String, BluetoothDevice>()
    private val cccdSubscribedDevices = ConcurrentHashMap.newKeySet<String>()
    private val clientInfoReceivedDevices = ConcurrentHashMap.newKeySet<String>()
    private val notifiedClients = ConcurrentHashMap.newKeySet<String>()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var activeAdvertiseCallback: AdvertiseCallback? = null
    private var activeAdvertisingSetCallback: AdvertisingSetCallback? = null

    private fun initSerialNumber(): Int {
        val prefs = runCatching { context.getSharedPreferences("serial_num_prefs", Context.MODE_PRIVATE) }.getOrNull()
        val last = prefs?.getInt("last_serial_num", 0) ?: 0
        var next = (last + 1) and 0xFF
        if (next == 0) next = 1
        runCatching { prefs?.edit()?.putInt("last_serial_num", next)?.apply() }
        return next
    }

    private fun advanceSerialNumber(): Int {
        val next = serialCounter.updateAndGet { current ->
            val n = (current + 1) and 0xFF
            if (n == 0) 1 else n
        }
        runCatching { serialPrefs?.edit()?.putInt("last_serial_num", next)?.apply() }
        return next
    }

    private val rotateTask = object : Runnable {
        override fun run() {
            if (closed.get()) return
            if (!isSessionActiveProvider()) {
                advanceSerialNumber()
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
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (btManager != null && gattServer == null) {
            startGattServer(btManager)
        }
        advanceSerialNumber()
        handler.removeCallbacks(rotateTask)
        restartAdvertisingInternal(btManager?.adapter)
        handler.postDelayed(rotateTask, CarLinkBleSpec.ROTATION_INTERVAL_MS)
    }

    /**
     * Notifies any connected BLE GATT clients that were waiting for the Wi-Fi Direct / SoftAP
     * hotspot credentials to become ready.
     */
    fun notifyPendingClients() {
        if (closed.get() || pendingClients.isEmpty()) return
        handler.post {
            if (closed.get()) return@post
            for (device in pendingClients.values.toList()) {
                notifyServerConnectionInfo(device, force = true)
            }
        }
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
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val serverInfoChar = BluetoothGattCharacteristic(
            CarLinkBleSpec.SERVER_CONNECTION_INFO_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE or
                BluetoothGattCharacteristic.PROPERTY_READ,
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

    private fun stopCurrentAdvertising(leAdvertiser: BluetoothLeAdvertiser?) {
        val setCb = activeAdvertisingSetCallback
        activeAdvertisingSetCallback = null
        if (setCb != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { leAdvertiser?.stopAdvertisingSet(setCb) }
        }
        val legacyCb = activeAdvertiseCallback
        activeAdvertiseCallback = null
        if (legacyCb != null) {
            runCatching { leAdvertiser?.stopAdvertising(legacyCb) }
        }
    }

    private fun restartAdvertisingInternal(
        adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter,
    ): Boolean {
        if (closed.get() || adapter == null || !adapter.isEnabled) return false
        val leAdvertiser = runCatching { adapter.bluetoothLeAdvertiser }.getOrNull() ?: return false
        advertiser = leAdvertiser
        stopCurrentAdvertising(leAdvertiser)

        val serial = serialCounter.get() and 0xFF
        val info1 = CarLinkBleSpec.buildCarInfo1Payload(
            carIdHex12 = carIdHex12,
            serialNumber = serial,
            modelIdHex8 = modelIdHex8,
            vendorDataHex4 = vendorDataHex4,
        )
        val info2 = CarLinkBleSpec.buildCarInfo2Payload(carShortName)

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val startedSet = runCatching {
                val paramsBuilder = AdvertisingSetParameters.Builder()
                    .setConnectable(true)
                    .setScannable(true)
                    .setLegacyMode(true)
                    .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                runCatching {
                    val intervalField = paramsBuilder.javaClass.getDeclaredField("mInterval")
                    intervalField.isAccessible = true
                    intervalField.setInt(paramsBuilder, 48)
                }
                val params = paramsBuilder.build()
                val setCallback = object : AdvertisingSetCallback() {
                    override fun onAdvertisingSetStarted(
                        advertisingSet: AdvertisingSet?,
                        txPower: Int,
                        status: Int,
                    ) {
                        if (status == ADVERTISE_SUCCESS) {
                            onLog("CarLink BLE: advertising set 0xFCFB (serial=$serial, carId=$carIdHex12, name=$carName)")
                        } else {
                            onLog("CarLink BLE: advertising set failed status=$status, falling back to legacy advertise")
                            startLegacyAdvertising(leAdvertiser, advertiseData, scanResponse, serial)
                        }
                    }
                }
                activeAdvertisingSetCallback = setCallback
                leAdvertiser.startAdvertisingSet(params, advertiseData, scanResponse, null, null, setCallback)
                true
            }.getOrDefault(false)
            if (startedSet) return true
        }

        return startLegacyAdvertising(leAdvertiser, advertiseData, scanResponse, serial)
    }

    private fun startLegacyAdvertising(
        leAdvertiser: BluetoothLeAdvertiser,
        advertiseData: AdvertiseData,
        scanResponse: AdvertiseData,
        serial: Int,
    ): Boolean {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
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
                pendingClients.remove(device.address)
                cccdSubscribedDevices.remove(device.address)
                clientInfoReceivedDevices.remove(device.address)
                notifiedClients.remove(device.address)
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                onLog("CarLink BLE: phone GATT connected ${device.address}")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            onLog("CarLink BLE: phone GATT MTU changed ${device.address} mtu=$mtu")
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
                cccdSubscribedDevices.add(device.address)
                if (clientInfoReceivedDevices.contains(device.address)) {
                    handler.postDelayed({ notifyServerConnectionInfo(device, force = true) }, 40L)
                    handler.postDelayed({ notifyServerConnectionInfo(device, force = true) }, 250L)
                    handler.postDelayed({ notifyServerConnectionInfo(device, force = true) }, 700L)
                }
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
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes)
                }
            }
            if (characteristic.uuid == CarLinkBleSpec.CLIENT_INFO_CHARACTERISTIC_UUID) {
                if (preparedWrite) {
                    val buffer = writeFragments.getOrPut(device.address) { PreparedWriteBuffer() }
                    buffer.append(offset, bytes)
                } else {
                    val combined = writeFragments.remove(device.address)?.let { buffer ->
                        buffer.append(offset, bytes)
                        buffer.toByteArray()
                    } ?: bytes
                    handleClientInfoBytes(device, combined)
                }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buffer = writeFragments.remove(device.address)
            runCatching {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (execute && buffer != null && buffer.length > 0) {
                handleClientInfoBytes(device, buffer.toByteArray())
            }
        }
    }

    private fun handleClientInfoBytes(device: BluetoothDevice, bytes: ByteArray) {
        val jsonStr = bytes.toString(Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
        onLog("CarLink BLE: received ClientInfo from ${device.address}: $jsonStr")
        pendingClients[device.address] = device
        clientInfoReceivedDevices.add(device.address)
        notifiedClients.remove(device.address)
        val clientInfo = runCatching { CarLinkBleSpec.parseClientInfoJson(jsonStr) }.getOrNull()
        if (clientInfo != null) {
            onClientConnected(device, clientInfo)
        }
        handler.postDelayed({ notifyServerConnectionInfo(device, force = true) }, 80L)
        handler.postDelayed({ notifyServerConnectionInfo(device, force = true) }, 350L)
        handler.postDelayed({ notifyServerConnectionInfo(device, force = true) }, 800L)
        handler.postDelayed({ notifyServerConnectionInfo(device, force = true) }, 1600L)
    }

    private fun buildServerInfoBytes(): ByteArray {
        val info = serverInfoProvider() ?: return ByteArray(0)
        return CarLinkBleSpec.buildServerInfoJson(info).toByteArray(Charsets.UTF_8)
    }

    @Suppress("DEPRECATION")
    fun notifyServerConnectionInfo(device: BluetoothDevice, force: Boolean = false) {
        if (closed.get()) return
        if (!pendingClients.containsKey(device.address)) return
        if (!force && notifiedClients.contains(device.address)) return
        val server = gattServer ?: return
        val service = server.getService(CarLinkBleSpec.SHARE_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CarLinkBleSpec.SERVER_CONNECTION_INFO_CHARACTERISTIC_UUID) ?: return
        val payload = buildServerInfoBytes()
        if (payload.isEmpty()) {
            pendingClients[device.address] = device
            onLog("CarLink BLE: deferring ServerConnectionInfo until Wi-Fi P2P hotspot is ready for ${device.address}")
            return
        }
        runCatching {
            characteristic.value = payload
            // Always send GATT Notification (confirm = false) matching BluetoothLeService.sendMessageToClient
            val sent = server.notifyCharacteristicChanged(device, characteristic, false)
            if (sent) {
                notifiedClients.add(device.address)
                onLog("CarLink BLE: notified ServerConnectionInfo (${payload.size}B) to ${device.address}")
            } else {
                onLog("CarLink BLE: notifyCharacteristicChanged returned false for ${device.address}")
            }
        }.onFailure { e ->
            onLog("CarLink BLE: notifyServerConnectionInfo failed: ${e.message}")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        stopCurrentAdvertising(advertiser)
        advertiser = null
        runCatching { gattServer?.close() }
        gattServer = null
        writeFragments.clear()
        pendingClients.clear()
        cccdSubscribedDevices.clear()
        clientInfoReceivedDevices.clear()
        notifiedClients.clear()
    }
}
