Exit code: 0
Wall time: 1.8 seconds
Output:
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
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream

data class OtaUiState(
    val statusText: String = "\u8bf7\u9009\u62e9\u56fa\u4ef6\u6587\u4ef6",
    val otaState: OtaState = OtaState.Idle,
    val progress: Int = 0,
    val firmwareFileName: String? = null,
    val firmwareSize: Long = 0
)

class OtaViewModel(application: Application) : AndroidViewModel(application) {
    private val bleManager = application.appContainer().bleManager
    private val _uiState = MutableStateFlow(OtaUiState())
    val uiState: StateFlow<OtaUiState> = _uiState.asStateFlow()
    private var firmwareUri: Uri? = null
    private var firmwareStream: BufferedInputStream? = null
    private var totalBytesSent = 0L
    private var isSending = false
    private var otaJob: Job? = null

    init {
        viewModelScope.launch {
            bleManager.otaStatusFlow().collect { status ->
                when (status.state) {
                    OtaState.Receiving -> {
                        _uiState.value = _uiState.value.copy(otaState = OtaState.Transmitting, progress = status.progress, statusText = "\u6b63\u5728\u4f20\u8f93\u56fa\u4ef6... ${status.progress}%")
                        if (!isSending) startSendingChunks()
                    }
                    OtaState.Validating -> _uiState.value = _uiState.value.copy(otaState = OtaState.Validating, statusText = "\u6b63\u5728\u9a8c\u8bc1\u56fa\u4ef6...")
                    OtaState.Updating -> _uiState.value = _uiState.value.copy(otaState = OtaState.Updating, statusText = "\u6b63\u5728\u5199\u5165\u56fa\u4ef6...")
                    OtaState.Success -> { _uiState.value = _uiState.value.copy(otaState = OtaState.Success, progress = 100, statusText = "\u56fa\u4ef6\u5347\u7ea7\u6210\u529f"); cleanup() }
                    OtaState.Error -> { _uiState.value = _uiState.value.copy(otaState = OtaState.Error, statusText = "\u8bbe\u5907\u62a5\u544a OTA \u5931\u8d25"); cleanup() }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch { bleManager.connectionState.collect { if (it is BleConnectionState.Disconnected && isSending) { _uiState.value = _uiState.value.copy(otaState = OtaState.Error, statusText = "BLE \u8fde\u63a5\u65ad\u5f00\uff0c\u4f20\u8f93\u4e2d\u6b62"); cleanup() } } }
    }

    fun selectFirmware(uri: Uri) = viewModelScope.launch {
        try {
            val context = getApplication<Application>()
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val size = context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0
            firmwareUri = uri
            _uiState.value = _uiState.value.copy(firmwareFileName = uri.lastPathSegment ?: "firmware.bin", firmwareSize = size, progress = 0, otaState = OtaState.Idle, statusText = "\u5df2\u9009\u62e9\u56fa\u4ef6")
        } catch (e: Exception) { _uiState.value = _uiState.value.copy(statusText = "\u6587\u4ef6\u8bfb\u53d6\u5931\u8d25: ${e.message}") }
    }

    fun startOta() {
        val state = _uiState.value; val uri = firmwareUri ?: return
        if (state.firmwareSize <= 0 || state.otaState !is OtaState.Idle) return
        viewModelScope.launch {
            try {
                firmwareStream = (getApplication<Application>().contentResolver.openInputStream(uri) ?: error("\u65e0\u6cd5\u6253\u5f00\u56fa\u4ef6\u6587\u4ef6")).buffered()
                totalBytesSent = 0; _uiState.value = _uiState.value.copy(otaState = OtaState.Beginning, statusText = "\u6b63\u5728\u51c6\u5907 OTA...")
                bleManager.sendOtaCommand("BEGIN,${state.firmwareSize}")
            } catch (e: Exception) { _uiState.value = _uiState.value.copy(otaState = OtaState.Error, statusText = "OTA \u542f\u52a8\u5931\u8d25: ${e.message}"); cleanup() }
        }
    }

    fun abortOta() { otaJob?.cancel(); viewModelScope.launch { bleManager.sendOtaCommand("ABORT"); cleanup(); _uiState.value = _uiState.value.copy(otaState = OtaState.Idle, progress = 0, statusText = "OTA \u5df2\u4e2d\u6b62") } }
    fun rebootDevice() = viewModelScope.launch { bleManager.sendOtaCommand("REBOOT") }
    fun queryStatus() = bleManager.queryOtaStatus()

    private fun startSendingChunks() {
        if (isSending) return
        isSending = true
        otaJob = viewModelScope.launch {
            val stream = firmwareStream ?: return@launch
            val buffer = ByteArray(bleManager.effectiveMtu); val total = _uiState.value.firmwareSize
            try {
                while (true) {
                    val read = stream.read(buffer); if (read <= 0) break
                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                    withTimeout(10_000) { bleManager.sendOtaDataAwait(chunk) }
                    totalBytesSent += read
                    val progress = ((totalBytesSent * 100) / total).toInt().coerceIn(0, 100)
                    _uiState.value = _uiState.value.copy(otaState = OtaState.Transmitting, progress = progress, statusText = "\u6b63\u5728\u4f20\u8f93\u56fa\u4ef6... $progress%")
                }
                bleManager.sendOtaCommand("END"); _uiState.value = _uiState.value.copy(otaState = OtaState.Ending, statusText = "\u4f20\u8f93\u5b8c\u6210\uff0c\u7b49\u5f85\u9a8c\u8bc1...")
            } catch (_: CancellationException) { } catch (e: Exception) { _uiState.value = _uiState.value.copy(otaState = OtaState.Error, statusText = "\u4f20\u8f93\u5931\u8d25: ${e.message}"); cleanup() }
        }
    }
    private fun cleanup() { otaJob?.cancel(); otaJob = null; runCatching { firmwareStream?.close() }; firmwareStream = null; isSending = false; totalBytesSent = 0 }
    override fun onCleared() { cleanup() }
    companion object { fun formatFileSize(bytes: Long): String = when { bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0); else -> String.format("%.2f MB", bytes / 1024.0 / 1024.0) } }
}

