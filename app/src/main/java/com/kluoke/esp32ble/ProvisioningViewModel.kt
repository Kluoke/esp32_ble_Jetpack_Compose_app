package com.kluoke.esp32ble

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val isDeviceReady: Boolean = false,
    val connectionState: BleConnectionState = BleConnectionState.Idle
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

    private var scanJob: Job? = null

    init {
        // 收集 BLE 连接状态
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                _uiState.value = when (state) {
                    is BleConnectionState.Idle -> _uiState.value.copy(
                        statusText = "请连接 ESP32",
                        connectionState = state
                    )
                    is BleConnectionState.Scanning -> _uiState.value.copy(
                        statusText = "正在扫描 ${state.target}...",
                        isScanning = true,
                        connectionState = state
                    )
                    is BleConnectionState.Connecting -> _uiState.value.copy(
                        statusText = "找到设备: ${state.deviceName}，正在连接...",
                        isScanning = false,
                        connectionState = state
                    )
                    is BleConnectionState.NegotiatingMtu -> _uiState.value.copy(
                        statusText = "已连接，协商传输大小...",
                        connectionState = state
                    )
                    is BleConnectionState.Subscribing -> _uiState.value.copy(
                        statusText = "正在订阅设备通知...",
                        connectionState = state
                    )
                    is BleConnectionState.Connected -> _uiState.value.copy(
                        statusText = "设备已准备好",
                        isDeviceReady = true,
                        connectionState = state
                    )
                    is BleConnectionState.Disconnected -> _uiState.value.copy(
                        statusText = "设备已断开",
                        isDeviceReady = false,
                        connectionState = state
                    )
                    is BleConnectionState.Error -> _uiState.value.copy(
                        statusText = state.message,
                        isDeviceReady = false,
                        connectionState = state
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
     * 检查权限后由 Activity 调用，启动 BLE 扫描
     */
    fun startScanAndConnect() {
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(isScanning = true)
        bleManager.setConnectionState(BleConnectionState.Scanning(BleProvisioningManager.DEVICE_NAME))

        scanJob = viewModelScope.launch {
            bleScanner.scanForDevice(BleProvisioningManager.DEVICE_NAME)
                .collect { result ->
                    bleManager.connect(result.device)
                    return@collect // 找到第一个匹配设备即停止
                }
            _uiState.value = _uiState.value.copy(isScanning = false)
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

    /**
     * 更新状态文本（供权限拒绝等外部事件使用）
     */
    fun updateStatus(message: String) {
        _uiState.value = _uiState.value.copy(statusText = message)
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        bleManager.close()
    }
}
