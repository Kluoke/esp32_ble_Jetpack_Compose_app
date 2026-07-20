package com.kluoke.esp32ble

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream

/**
 * OTA 升级 UI 状态
 */
data class OtaUiState(
    val statusText: String = "请选择固件文件",
    val otaState: OtaState = OtaState.Idle,
    val progress: Int = 0,
    val firmwareFileName: String? = null,
    val firmwareSize: Long = 0
)

/**
 * OTA 升级 ViewModel
 *
 * 负责：
 * - 固件文件选择与读取
 * - OTA 状态机驱动（BEGIN → 传输 → END → 重启）
 * - MTU 感知的分块传输与流控
 * - 进度追踪（设备端进度优先，本地回退）
 */
class OtaViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = application.appContainer().bleManager

    private val _uiState = MutableStateFlow(OtaUiState())
    val uiState: StateFlow<OtaUiState> = _uiState.asStateFlow()

    private var firmwareUri: Uri? = null
    private var firmwareStream: BufferedInputStream? = null
    private var totalBytesSent: Long = 0L
    private var isSending = false
    private var otaJob: Job? = null

    init {
        // 收集 OTA 状态通知（来自 ESP32 0xFF0B 特征）
        viewModelScope.launch {
            bleManager.otaStatusFlow().collect { status ->
                when (status.state) {
                    OtaState.Receiving -> {
                        // 设备报告正在接收，用设备端进度更新 UI
                        _uiState.value = _uiState.value.copy(
                            otaState = OtaState.Transmitting,
                            progress = status.progress,
                            statusText = "正在传输固件... ${status.progress}%"
                        )
                        // 首次收到 receiving 状态时启动分块传输
                        if (!isSending) {
                            startSendingChunks()
                        }
                    }
                    OtaState.Validating -> {
                        _uiState.value = _uiState.value.copy(
                            otaState = OtaState.Validating,
                            statusText = "正在验证固件..."
                        )
                    }
                    OtaState.Updating -> {
                        _uiState.value = _uiState.value.copy(
                            otaState = OtaState.Updating,
                            statusText = "正在写入启动分区..."
                        )
                    }
                    OtaState.Success -> {
                        _uiState.value = _uiState.value.copy(
                            otaState = OtaState.Success,
                            progress = 100,
                            statusText = "固件升级成功，请重启设备"
                        )
                        cleanup()
                    }
                    OtaState.Error -> {
                        if (isSending) {
                            _uiState.value = _uiState.value.copy(
                                otaState = OtaState.Error,
                                statusText = "设备报告升级失败"
                            )
                            cleanup()
                        }
                    }
                    OtaState.Idle -> {
                        if (_uiState.value.otaState != OtaState.Idle) {
                            _uiState.value = _uiState.value.copy(
                                otaState = OtaState.Idle,
                                statusText = "OTA 已重置，请重新开始"
                            )
                        }
                    }
                    else -> { /* Beginning, Transmitting, Ending 由本地驱动 */ }
                }
            }
        }

        // 监听 BLE 断连
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                if (state is BleConnectionState.Disconnected && isSending) {
                    _uiState.value = _uiState.value.copy(
                        otaState = OtaState.Error,
                        statusText = "BLE 连接断开，传输中止"
                    )
                    cleanup()
                }
            }
        }
    }

    // ==================== 用户操作 ====================

    /**
     * 选择固件文件
     */
    fun selectFirmware(uri: Uri) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                // 持久化 URI 读取权限
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val size = context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
                val fileName = uri.lastPathSegment ?: "firmware.bin"

                firmwareUri = uri
                _uiState.value = _uiState.value.copy(
                    firmwareFileName = fileName,
                    firmwareSize = size,
                    statusText = "已选择: $fileName (${formatFileSize(size)})",
                    otaState = OtaState.Idle,
                    progress = 0
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    statusText = "文件读取失败: ${e.message}"
                )
            }
        }
    }

    /**
     * 开始 OTA 升级
     *
     * 流程：打开文件 → 发送 BEGIN,{size} → 等待设备 Ready → 分块传输 → 发送 END
     */
    fun startOta() {
        val state = _uiState.value
        val uri = firmwareUri ?: return
        if (state.firmwareSize <= 0) return
        if (state.otaState !is OtaState.Idle) return

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    otaState = OtaState.Beginning,
                    statusText = "正在准备 OTA..."
                )

                // 打开文件流供分块读取
                val inputStream = getApplication<Application>()
                    .contentResolver.openInputStream(uri) ?: run {
                    _uiState.value = _uiState.value.copy(
                        otaState = OtaState.Error,
                        statusText = "无法打开固件文件"
                    )
                    return@launch
                }
                firmwareStream = inputStream.buffered()
                totalBytesSent = 0L

                // 发送 BEGIN 命令通知设备准备接收
                bleManager.sendOtaCommand("BEGIN,${state.firmwareSize}")

                // 等待设备通过 0xFF0B 通知回复 receiving 状态后启动传输
                // 首次收到 receiving 时由 otaStatusFlow 的收集器触发 startSendingChunks()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    otaState = OtaState.Error,
                    statusText = "OTA 启动失败: ${e.message}"
                )
                cleanup()
            }
        }
    }

    /**
     * 中止 OTA 升级
     */
    fun abortOta() {
        otaJob?.cancel()
        viewModelScope.launch {
            bleManager.sendOtaCommand("ABORT")
            cleanup()
            _uiState.value = _uiState.value.copy(
                otaState = OtaState.Idle,
                progress = 0,
                statusText = "OTA 已中止"
            )
        }
    }

    /**
     * 重启设备
     */
    fun rebootDevice() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                statusText = "正在重启设备..."
            )
            bleManager.sendOtaCommand("REBOOT")
        }
    }

    /**
     * 查询设备 OTA 状态
     */
    fun queryStatus() {
        bleManager.queryOtaStatus()
    }

    // ==================== 内部实现 ====================

    /**
     * 启动分块传输协程
     *
     * 由 otaStatusFlow 首次收到 Receiving 状态时触发。
     * 通过 [BleProvisioningManager.writeCompletionFlow] 实现流控：
     * 每写入一块后等待 onCharacteristicWrite 回调，再写入下一块，
     * 避免 BLE 栈缓冲区溢出。
     */
    fun startSendingChunks() {
        if (isSending) return
        isSending = true

        otaJob = viewModelScope.launch {
            val stream = firmwareStream ?: run {
                isSending = false
                return@launch
            }
            val chunkSize = bleManager.effectiveMtu
            val buffer = ByteArray(chunkSize)
            val totalSize = _uiState.value.firmwareSize

            try {
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break

                    val chunk = if (read < chunkSize) buffer.copyOf(read) else buffer
                    bleManager.sendOtaData(chunk)
                    totalBytesSent += read

                    // 本地进度计算（设备端进度通过 otaStatusFlow 覆盖）
                    val localProgress = ((totalBytesSent * 100) / totalSize).toInt().coerceIn(0, 100)
                    if (_uiState.value.otaState == OtaState.Transmitting) {
                        _uiState.value = _uiState.value.copy(
                            progress = localProgress,
                            statusText = "正在传输固件... $localProgress%"
                        )
                    }

                    // 等待本次写入完成回调，实现流控
                    bleManager.writeCompletionFlow().collect { }
                }

                // 所有数据发送完毕，发送 END 命令
                bleManager.sendOtaCommand("END")
                _uiState.value = _uiState.value.copy(
                    otaState = OtaState.Ending,
                    statusText = "数据传输完成，等待设备验证..."
                )
            } catch (e: CancellationException) {
                // 协程被取消（用户中止），不处理
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    otaState = OtaState.Error,
                    statusText = "传输失败: ${e.message}"
                )
                cleanup()
            }
        }
    }

    private fun cleanup() {
        otaJob?.cancel()
        otaJob = null
        try {
            firmwareStream?.close()
        } catch (_: Exception) { }
        firmwareStream = null
        isSending = false
        totalBytesSent = 0L
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }

    companion object {
        fun formatFileSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }
}