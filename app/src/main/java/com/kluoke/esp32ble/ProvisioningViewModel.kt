package com.kluoke.esp32ble

import android.app.Application
import android.bluetooth.le.ScanResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 配网页面 UI 状态
 */
data class ProvisioningUiState(
    val statusText: String = "请连接 ESP32",
    val wifiAps: List<WifiAp> = emptyList(),
    val ssid: String = "",
    val password: String = "",
    val isScanning: Boolean = false,
    val isDeviceReady: Boolean = false
)

/**
 * 配网 ViewModel
 *
 * 集中管理 UI 状态和业务逻辑，
 * 通过 StateFlow 向 Compose UI 提供可观察的状态。
 * 配置变更（如屏幕旋转）时状态不会丢失。
 */
class ProvisioningViewModel(application: Application) : AndroidViewModel(application) {

    private val bleScanner = application.appContainer().bleScanner
    private val bleManager = application.appContainer().bleManager

    private val _uiState = MutableStateFlow(ProvisioningUiState())
    val uiState: StateFlow<ProvisioningUiState> = _uiState.asStateFlow()

    init {
        // 收集 BLE 连接状态
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                _uiState.value = when (state) {
                    is BleConnectionState.Idle -> _uiState.value.copy(statusText = "请连接 ESP32")
                    is BleConnectionState.Scanning -> _uiState.value.copy(
                        statusText = "正在扫描 ${state.target}...",
                        isScanning = true
                    )
                    is BleConnectionState.Connecting -> _uiState.value.copy(
                        statusText = "找到设备: ${state.deviceName}，正在连接...",
                        isScanning = false
                    )
                    is BleConnectionState.NegotiatingMtu -> _uiState.value.copy(
                        statusText = "已连接，协商传输大小..."
                    )
                    is BleConnectionState.Subscribing -> _uiState.value.copy(
                        statusText = "正在订阅设备通知..."
                    )
                    is BleConnectionState.Connected -> _uiState.value.copy(
                        statusText = "设备已准备好",
                        isDeviceReady = true
                    )
                    is BleConnectionState.Disconnected -> _uiState.value.copy(
                        statusText = "设备已断开",
                        isDeviceReady = false
                    )
                    is BleConnectionState.Error -> _uiState.value.copy(
                        statusText = state.message,
                        isDeviceReady = false
                    )
                }
            }
        }

        // 收集 Wi-Fi 扫描结果
        viewModelScope.launch {
            bleManager.wifiApFlow().collect { ap ->
                _uiState.value = _uiState.value.let { current ->
                    current.copy(
                        wifiAps = (current.wifiAps.filter { it.ssid != ap.ssid } + ap)
                            .sortedByDescending { it.rssi }
                    )
                }
            }
        }

        // 收集设备状态消息
        viewModelScope.launch {
            bleManager.statusFlow().collect { status ->
                _uiState.value = _uiState.value.copy(statusText = status)
            }
        }
    }

    // ==================== 用户操作 ====================

    /**
     * 开始扫描并连接 ESP32 设备
     */
    fun startScanAndConnect() {
        _uiState.value = _uiState.value.copy(
            statusText = "正在检查权限...",
            isScanning = true
        )
        viewModelScope.launch {
            bleScanner.scanForDevice(BleProvisioningManager.DEVICE_NAME)
                .collect { result ->
                    bleManager.connectionState.value = BleConnectionState.Connecting(
                        result.device.name ?: result.device.address
                    )
                    bleManager.connect(result.device)
                    return@collect // 找到第一个匹配设备即停止
                }
        }
    }

    /**
     * 扫描 Wi-Fi 热点
     */
    fun scanWifi() {
        bleManager.scanWifi()
    }

    /**
     * 发送 Wi-Fi 凭据进行配网
     */
    fun provision() {
        val state = _uiState.value
        bleManager.provision(state.ssid, state.password)
    }

    // ==================== 状态更新 ====================

    fun updateSsid(ssid: String) {
        _uiState.value = _uiState.value.copy(ssid = ssid)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.close()
    }
}
