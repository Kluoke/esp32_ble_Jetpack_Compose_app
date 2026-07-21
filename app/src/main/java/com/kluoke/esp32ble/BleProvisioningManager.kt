Exit code: 0
Wall time: 1.1 seconds
Output:
package com.kluoke.esp32ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class WifiAp(val ssid: String, val rssi: Int, val authMode: Int)

sealed interface BleConnectionState {
    data object Idle : BleConnectionState
    data class Scanning(val target: String) : BleConnectionState
    data class Connecting(val deviceName: String) : BleConnectionState
    data class NegotiatingMtu(val deviceName: String) : BleConnectionState
    data class Subscribing(val deviceName: String) : BleConnectionState
    data class Connected(val deviceName: String) : BleConnectionState
    data object Disconnected : BleConnectionState
    data class Error(val message: String) : BleConnectionState
}

sealed interface OtaState {
    data object Idle : OtaState
    data object Beginning : OtaState
    data object Receiving : OtaState
    data object Transmitting : OtaState
    data object Validating : OtaState
    data object Updating : OtaState
    data object Ending : OtaState
    data object Success : OtaState
    data object Error : OtaState
}

data class OtaStatus(val state: OtaState, val progress: Int = 0)

/** GATT transport only. Business operations are framed by [BleProtocol]. */
class BleProvisioningManager(context: Context) {
    companion object {
        const val DEVICE_NAME = "ESP32_BLE_DEVICE"
        const val DEFAULT_MTU = 23
        const val ATT_HEADER_SIZE = 3
        private val SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
        private val COMMAND_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
        private val EVENT_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")
        private val OTA_DATA_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef3")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val appContext = context.applicationContext
    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()
    private val _wifiAps = MutableSharedFlow<WifiAp>(extraBufferCapacity = 32)
    private val _statuses = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val _otaStatuses = MutableSharedFlow<OtaStatus>(extraBufferCapacity = 16)
    private val _writeCompletion = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val writeCompletionFlow: SharedFlow<Unit> = _writeCompletion.asSharedFlow()
    private val decoder = BleProtocol.Decoder()

    private var gatt: BluetoothGatt? = null
    private var servicesDiscovered = false
    private var effectiveMtuRaw = DEFAULT_MTU
    private var sequence = 1
    val effectiveMtu: Int get() = effectiveMtuRaw - ATT_HEADER_SIZE

    fun setConnectionState(state: BleConnectionState) { _connectionState.value = state }
    fun wifiApFlow(): Flow<WifiAp> = _wifiAps.asSharedFlow()
    fun statusFlow(): Flow<String> = _statuses.asSharedFlow()
    fun otaStatusFlow(): Flow<OtaStatus> = _otaStatuses.asSharedFlow()

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        close()
        _connectionState.value = BleConnectionState.Connecting(device.name ?: device.address)
        @Suppress("DEPRECATION")
        gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun scanWifi() = sendCommand(BleProtocol.Command.WIFI_SCAN)

    fun provision(ssid: String, password: String) {
        if (ssid.isBlank() || password.isBlank()) return updateError("Wi-Fi 鍚嶇О鍜屽瘑鐮佷笉鑳戒负绌?)
        try {
            sendCommand(BleProtocol.Command.SET_WIFI, BleProtocol.setWifiPayload(ssid, password))
        } catch (error: IllegalArgumentException) {
            updateError(error.message ?: "Wi-Fi 鍙傛暟鏃犳晥")
        }
    }

    /** Compatibility facade for existing OTA UI; OTA control itself is now binary. */
    fun sendOtaCommand(command: String) {
        when (command.substringBefore(',')) {
            "BEGIN" -> {
                val size = command.substringAfter(',', "").toLongOrNull()
                    ?: return updateError("OTA 鍥轰欢澶у皬鏃犳晥")
                if (size !in 1..UInt.MAX_VALUE.toLong()) return updateError("OTA 鍥轰欢杩囧ぇ")
                sendCommand(BleProtocol.Command.OTA_BEGIN,
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size.toInt()).array())
            }
            "END" -> sendCommand(BleProtocol.Command.OTA_END)
            "ABORT" -> sendCommand(BleProtocol.Command.OTA_ABORT)
            "REBOOT" -> sendCommand(BleProtocol.Command.REBOOT)
            "QUERY" -> sendCommand(BleProtocol.Command.OTA_QUERY)
            else -> updateError("鏈煡 OTA 鍛戒护")
        }
    }

    @SuppressLint("MissingPermission")
    fun sendOtaData(data: ByteArray) {
        val characteristic = characteristic(OTA_DATA_UUID) ?: return updateError("OTA 鏁版嵁閫氶亾涓嶅彲鐢?)
        write(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    fun queryOtaStatus() = sendCommand(BleProtocol.Command.OTA_QUERY)

    @SuppressLint("MissingPermission")
    fun close() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        servicesDiscovered = false
        effectiveMtuRaw = DEFAULT_MTU
        _connectionState.value = BleConnectionState.Disconnected
    }

    @SuppressLint("MissingPermission")
    private fun sendCommand(command: Byte, payload: ByteArray = ByteArray(0)) {
        val characteristic = characteristic(COMMAND_UUID) ?: return updateError("璁惧灏氭湭鍑嗗濂?)
        val requestSequence = sequence
        sequence = if (sequence == 0xFFFF) 1 else sequence + 1
        write(characteristic, BleProtocol.encode(command, requestSequence, payload), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic? =
        if (servicesDiscovered) gatt?.getService(SERVICE_UUID)?.getCharacteristic(uuid) else null

    @SuppressLint("MissingPermission")
    private fun write(characteristic: BluetoothGattCharacteristic, value: ByteArray, writeType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(characteristic, value, writeType)
        } else {
            @Suppress("DEPRECATION") characteristic.writeType = writeType
            @Suppress("DEPRECATION") characteristic.value = value
            @Suppress("DEPRECATION") gatt?.writeCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableEvents() {
        val characteristic = characteristic(EVENT_UUID) ?: return updateError("缂哄皯浜嬩欢閫氱煡閫氶亾")
        val currentGatt = gatt ?: return
        if (!currentGatt.setCharacteristicNotification(characteristic, true)) return updateError("鏃犳硶鍚敤閫氱煡")
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return updateError("缂哄皯 CCCD")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION") descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION") currentGatt.writeDescriptor(descriptor)
        }
    }

    private fun updateError(message: String) { _connectionState.value = BleConnectionState.Error(message) }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return updateError("杩炴帴澶辫触锛?status")
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                val name = gatt.device.name ?: gatt.device.address
                _connectionState.value = BleConnectionState.NegotiatingMtu(name)
                if (!gatt.requestMtu(128)) gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                servicesDiscovered = false
                _connectionState.value = BleConnectionState.Disconnected
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) effectiveMtuRaw = mtu
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt.getService(SERVICE_UUID) == null) {
                return updateError("鏈彂鐜?BLE Protocol v1 鏈嶅姟")
            }
            servicesDiscovered = true
            _connectionState.value = BleConnectionState.Subscribing(gatt.device.name ?: gatt.device.address)
            enableEvents()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return updateError("璁㈤槄浜嬩欢澶辫触锛?status")
            _connectionState.value = BleConnectionState.Connected(gatt.device.name ?: gatt.device.address)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) updateError("鍐欏叆澶辫触锛?status") else _writeCompletion.tryEmit(Unit)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") onEvent(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onEvent(characteristic.uuid, value)
        }
    }

    private fun onEvent(uuid: UUID, bytes: ByteArray) {
        if (uuid != EVENT_UUID) return
        decoder.append(bytes).forEach { packet ->
            when (packet.command) {
                BleProtocol.Command.WIFI_STATUS -> _statuses.tryEmit(displayStatus(packet.payload.decodeToString()))
                BleProtocol.Command.WIFI_SCAN_RESULT -> parseAccessPoint(packet.payload)?.let(_wifiAps::tryEmit)
                BleProtocol.Command.OTA_STATUS -> if (packet.payload.size >= 2) _otaStatuses.tryEmit(
                    OtaStatus(mapOtaState(packet.payload[0]), packet.payload[1].toInt().and(0xFF).coerceIn(0, 100)))
                BleProtocol.Command.ERROR -> updateError("璁惧鎷掔粷鍛戒护锛?{packet.payload.getOrNull(1)?.toInt()?.and(0xFF) ?: -1}")
            }
        }
    }

    private fun parseAccessPoint(payload: ByteArray): WifiAp? {
        val ssidLength = payload.firstOrNull()?.toInt()?.and(0xFF) ?: return null
        if (payload.size != ssidLength + 3) return null
        return WifiAp(payload.copyOfRange(1, 1 + ssidLength).decodeToString(),
            payload[1 + ssidLength].toInt(), payload[2 + ssidLength].toInt().and(0xFF))
    }

    private fun mapOtaState(value: Byte): OtaState = when (value.toInt().and(0xFF)) {
        0 -> OtaState.Idle; 2 -> OtaState.Receiving; 3 -> OtaState.Validating
        4 -> OtaState.Updating; 5 -> OtaState.Success; 6 -> OtaState.Error
        else -> OtaState.Idle
    }

    private fun displayStatus(status: String): String = when (status) {
        "scanning" -> "璁惧姝ｅ湪鎵弿 Wi-Fi"
        "scan_done" -> "鎵弿瀹屾垚"
        "scan_busy" -> "璁惧姝ｅ湪鎵弿锛岃绋嶅€?
        "scan_failed" -> "Wi-Fi 鎵弿澶辫触"
        "connecting" -> "璁惧姝ｅ湪杩炴帴 Wi-Fi"
        else -> status
    }
}

