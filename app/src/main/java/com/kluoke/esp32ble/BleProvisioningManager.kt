package com.kluoke.esp32ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Wi-Fi 热点数据模型
 */
data class WifiAp(val ssid: String, val rssi: Int, val authMode: Int)

/**
 * BLE 配网连接状态
 */
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

/**
 * ESP32 BLE 配网管理器
 *
 * 使用 callbackFlow 将 BLE 回调转换为 Flow，
 * 消除 Handler/Listener 模式，便于与 ViewModel/Coroutines 集成。
 */
class BleProvisioningManager(context: Context) {

    companion object {
        const val DEVICE_NAME = "ESP32_BLE_DEVICE"
        private val SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
        private fun characteristicUuid(id: String) =
            UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")
        private val SSID_UUID = characteristicUuid("ff03")
        private val PASSWORD_UUID = characteristicUuid("ff04")
        private val SCAN_UUID = characteristicUuid("ff06")
        private val SCAN_RESULT_UUID = characteristicUuid("ff07")
        private val STATUS_UUID = characteristicUuid("ff08")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val appContext = context.applicationContext

    /** 连接状态 */
    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    /** 更新连接状态 */
    fun setConnectionState(state: BleConnectionState) {
        _connectionState.value = state
    }

    private var gatt: BluetoothGatt? = null
    private var pendingPassword: String? = null
    private var servicesDiscovered = false
    private val enabledNotifications = mutableSetOf<UUID>()

    /**
     * Wi-Fi 扫描结果流
     */
    fun wifiApFlow(): Flow<WifiAp> = callbackFlow {
        val listener = object : BleRawListener {
            override fun onWifiApReceived(ap: WifiAp) {
                trySend(ap)
            }
        }
        rawListeners.add(listener)
        awaitClose { rawListeners.remove(listener) }
    }.conflate()

    /**
     * 设备状态消息流
     */
    fun statusFlow(): Flow<String> = callbackFlow {
        val listener = object : BleRawListener {
            override fun onStatusChanged(status: String) {
                trySend(status)
            }
        }
        rawListeners.add(listener)
        awaitClose { rawListeners.remove(listener) }
    }.conflate()

    // 内部原始监听器集合，供 GattCallback 分发
    private val rawListeners = mutableSetOf<BleRawListener>()

    private interface BleRawListener {
        fun onWifiApReceived(ap: WifiAp) {}
        fun onStatusChanged(status: String) {}
    }

    // ==================== 公开 API ====================

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        close()
        _connectionState.value = BleConnectionState.Connecting(device.name ?: device.address)
        @Suppress("DEPRECATION")
        gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun scanWifi() {
        val characteristic = serviceCharacteristic(SCAN_UUID)
            ?: return updateError("设备未准备好")
        write(characteristic, "SCAN".toByteArray(StandardCharsets.UTF_8))
    }

    @SuppressLint("MissingPermission")
    fun provision(ssid: String, password: String) {
        if (ssid.isBlank()) return updateError("请选择或输入 Wi-Fi 名称")
        if (password.isBlank()) return updateError("请输入 Wi-Fi 密码")
        val characteristic = serviceCharacteristic(SSID_UUID)
            ?: return updateError("设备未准备好")
        pendingPassword = password
        _connectionState.value = BleConnectionState.Connected(
            (_connectionState.value as? BleConnectionState.Connected)?.deviceName ?: "设备"
        )
        write(characteristic, ssid.toByteArray(StandardCharsets.UTF_8))
    }

    @SuppressLint("MissingPermission")
    fun close() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        servicesDiscovered = false
        enabledNotifications.clear()
        pendingPassword = null
        _connectionState.value = BleConnectionState.Disconnected
    }

    // ==================== 内部实现 ====================

    @SuppressLint("MissingPermission")
    private fun write(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(characteristic)
        }
    }

    private fun serviceCharacteristic(uuid: UUID): BluetoothGattCharacteristic? =
        if (servicesDiscovered) gatt?.getService(SERVICE_UUID)?.getCharacteristic(uuid) else null

    @SuppressLint("MissingPermission")
    private fun enableNextNotification() {
        val next = listOf(SCAN_RESULT_UUID, STATUS_UUID).firstOrNull { it !in enabledNotifications }
            ?: return onReady()
        val characteristic = serviceCharacteristic(next)
            ?: return updateError("缺少通知特征 $next")
        if (!gatt!!.setCharacteristicNotification(characteristic, true)) {
            return updateError("无法启用通知")
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
            ?: return updateError("通知描述符不存在")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt!!.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt!!.writeDescriptor(descriptor)
        }
    }

    private fun onReady() {
        val name = (_connectionState.value as? BleConnectionState.NegotiatingMtu)?.deviceName ?: "设备"
        _connectionState.value = BleConnectionState.Connected(name)
    }

    private fun updateError(message: String) {
        _connectionState.value = BleConnectionState.Error(message)
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateError("连接失败，错误码 $status")
                return
            }
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    val name = gatt.device.name ?: gatt.device.address
                    _connectionState.value = BleConnectionState.NegotiatingMtu(name)
                    if (!gatt.requestMtu(128)) gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    servicesDiscovered = false
                    _connectionState.value = BleConnectionState.Disconnected
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt.getService(SERVICE_UUID) == null) {
                updateError("未发现 ESP32 配网服务；请确认固件已更新")
                return
            }
            servicesDiscovered = true
            val name = gatt.device.name ?: gatt.device.address
            _connectionState.value = BleConnectionState.Subscribing(name)
            enableNextNotification()
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateError("订阅通知失败，错误码 $status")
                return
            }
            enabledNotifications += descriptor.characteristic.uuid
            enableNextNotification()
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateError("写入失败，错误码 $status")
                return
            }
            if (characteristic.uuid == SSID_UUID) {
                val password = pendingPassword ?: return
                pendingPassword = null
                val passwordCharacteristic = serviceCharacteristic(PASSWORD_UUID)
                    ?: return updateError("缺少密码特征")
                write(passwordCharacteristic, password.toByteArray(StandardCharsets.UTF_8))
            }
        }

        @Deprecated("Deprecated in Java")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value?.toString(StandardCharsets.UTF_8).orEmpty()
            handleCharacteristicChanged(characteristic.uuid, value)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val stringValue = value.toString(StandardCharsets.UTF_8)
            handleCharacteristicChanged(characteristic.uuid, stringValue)
        }
    }

    private fun handleCharacteristicChanged(uuid: UUID, value: String) {
        when (uuid) {
            SCAN_RESULT_UUID -> parseAccessPoint(value)?.let { ap ->
                rawListeners.forEach { it.onWifiApReceived(ap) }
            }
            STATUS_UUID -> {
                val display = displayStatus(value)
                rawListeners.forEach { it.onStatusChanged(display) }
            }
        }
    }

    private fun parseAccessPoint(payload: String): WifiAp? {
        val authSeparator = payload.lastIndexOf(',')
        val rssiSeparator = payload.lastIndexOf(',', authSeparator - 1)
        if (authSeparator <= 0 || rssiSeparator <= 0) return null
        val ssid = payload.substring(0, rssiSeparator)
        val rssi = payload.substring(rssiSeparator + 1, authSeparator).toIntOrNull() ?: return null
        val auth = payload.substring(authSeparator + 1).toIntOrNull() ?: return null
        return WifiAp(ssid, rssi, auth)
    }

    private fun displayStatus(status: String): String = when (status) {
        "scanning" -> "设备正在扫描 Wi-Fi"
        "scan_done" -> "扫描完成"
        "scan_busy" -> "设备正在扫描，请稍候"
        "scan_failed" -> "Wi-Fi 扫描失败"
        "connecting" -> "设备正在连接 Wi-Fi"
        "connected" -> "Wi-Fi 已连接"
        "retrying" -> "Wi-Fi 连接失败，正在重试"
        "failed" -> "Wi-Fi 配网失败"
        else -> status
    }
}
