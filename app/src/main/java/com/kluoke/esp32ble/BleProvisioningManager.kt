package com.kluoke.esp32ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.nio.charset.StandardCharsets
import java.util.UUID

data class WifiAp(val ssid: String, val rssi: Int, val authMode: Int)

interface BleProvisioningListener {
    fun onStatusChanged(status: String)
    fun onWifiApReceived(accessPoint: WifiAp)
    fun onReady()
    fun onError(message: String)
}

class BleProvisioningManager(
    context: Context,
    private val listener: BleProvisioningListener
) {
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var gatt: BluetoothGatt? = null
    private var pendingPassword: String? = null
    private var servicesDiscovered = false
    private val enabledNotifications = mutableSetOf<UUID>()

    fun bluetoothAvailable(): Boolean = adapter != null && adapter.isEnabled

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        close()
        listener.onStatusChanged("正在连接 ${device.name ?: device.address}")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, callback)
        }
    }

    @SuppressLint("MissingPermission")
    fun scanWifi() {
        val characteristic = serviceCharacteristic(SCAN_UUID)
            ?: return listener.onError("设备未准备好")
        write(characteristic, "SCAN".toByteArray(StandardCharsets.UTF_8))
    }

    @SuppressLint("MissingPermission")
    fun provision(ssid: String, password: String) {
        if (ssid.isBlank()) return listener.onError("请选择或输入 Wi-Fi 名称")
        if (password.isBlank()) return listener.onError("请输入 Wi-Fi 密码")
        val characteristic = serviceCharacteristic(SSID_UUID)
            ?: return listener.onError("设备未准备好")
        pendingPassword = password
        listener.onStatusChanged("正在发送 Wi-Fi 名称")
        write(characteristic, ssid.toByteArray(StandardCharsets.UTF_8))
    }

    @SuppressLint("MissingPermission")
    fun close() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        servicesDiscovered = false
        enabledNotifications.clear()
    }

    @SuppressLint("MissingPermission")
    private fun write(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = value
        if (gatt?.writeCharacteristic(characteristic) != true) {
            listener.onError("写入 BLE 特征失败")
        }
    }

    private fun serviceCharacteristic(uuid: UUID): BluetoothGattCharacteristic? =
        if (servicesDiscovered) gatt?.getService(SERVICE_UUID)?.getCharacteristic(uuid) else null

    @SuppressLint("MissingPermission")
    private fun enableNextNotification() {
        val next = listOf(SCAN_RESULT_UUID, STATUS_UUID).firstOrNull { it !in enabledNotifications }
            ?: return listener.onReady()
        val characteristic = serviceCharacteristic(next)
            ?: return listener.onError("缺少通知特征 $next")
        if (!gatt!!.setCharacteristicNotification(characteristic, true)) {
            return listener.onError("无法启用通知")
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
            ?: return listener.onError("通知描述符不存在")
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt!!.writeDescriptor(descriptor)) listener.onError("写入通知描述符失败")
    }

    private fun post(block: () -> Unit) = mainHandler.post(block)

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                post { listener.onError("连接失败，错误码 $status") }
                return
            }
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                post { listener.onStatusChanged("已连接，协商传输大小") }
                if (!gatt.requestMtu(128)) gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                servicesDiscovered = false
                post { listener.onStatusChanged("设备已断开") }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt.getService(SERVICE_UUID) == null) {
                post { listener.onError("未发现 ESP32 配网服务；请确认固件已更新") }
                return
            }
            servicesDiscovered = true
            post { listener.onStatusChanged("正在订阅设备通知") }
            enableNextNotification()
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                post { listener.onError("订阅通知失败，错误码 $status") }
                return
            }
            enabledNotifications += descriptor.characteristic.uuid
            enableNextNotification()
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                post { listener.onError("写入失败，错误码 $status") }
                return
            }
            if (characteristic.uuid == SSID_UUID) {
                val password = pendingPassword ?: return
                pendingPassword = null
                val passwordCharacteristic = serviceCharacteristic(PASSWORD_UUID)
                    ?: return post { listener.onError("缺少密码特征") }
                post { listener.onStatusChanged("正在发送 Wi-Fi 密码") }
                write(passwordCharacteristic, password.toByteArray(StandardCharsets.UTF_8))
            } else if (characteristic.uuid == PASSWORD_UUID) {
                post { listener.onStatusChanged("凭据已发送，等待设备连接") }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value?.toString(StandardCharsets.UTF_8).orEmpty()
            when (characteristic.uuid) {
                SCAN_RESULT_UUID -> parseAccessPoint(value)?.let { ap -> post { listener.onWifiApReceived(ap) } }
                STATUS_UUID -> post { listener.onStatusChanged(displayStatus(value)) }
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
