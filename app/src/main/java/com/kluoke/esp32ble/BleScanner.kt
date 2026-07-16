package com.kluoke.esp32ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * BLE 设备扫描器
 *
 * 封装 BLE 扫描逻辑，通过 Flow 暴露扫描结果，
 * 实现与 BLE Manager 的解耦。
 */
class BleScanner(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun scanForDevice(targetName: String): Flow<ScanResult> = callbackFlow {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            close()
            return@callbackFlow
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            close()
            return@callbackFlow
        }

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val deviceName = result.device.name ?: "Unknown"
                if (deviceName == targetName || deviceName.contains("ESP32", ignoreCase = true)) {
                    trySend(result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close()
            }
        }

        scanner.startScan(scanCallback)

        awaitClose {
            scanner.stopScan(scanCallback)
        }
    }
}
