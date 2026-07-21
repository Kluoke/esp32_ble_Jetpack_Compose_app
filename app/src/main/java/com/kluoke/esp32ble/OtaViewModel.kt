Exit code: 0
Wall time: 1.3 seconds
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream

/**
 * OTA 鍗囩骇 UI 鐘舵€?
 */
data class OtaUiState(
    val statusText: String = "璇烽€夋嫨鍥轰欢鏂囦欢",
    val otaState: OtaState = OtaState.Idle,
    val progress: Int = 0,
    val firmwareFileName: String? = null,
    val firmwareSize: Long = 0
)

/**
 * OTA 鍗囩骇 ViewModel
 *
 * 璐熻矗锛?
 * - 鍥轰欢鏂囦欢閫夋嫨涓庤鍙?
 * - OTA 鐘舵€佹満椹卞姩锛圔EGIN 鈫?浼犺緭 鈫?END 鈫?閲嶅惎锛?
 * - MTU 鎰熺煡鐨勫垎鍧椾紶杈撲笌娴佹帶
 * - 杩涘害杩借釜锛堣澶囩杩涘害浼樺厛锛屾湰鍦板洖閫€锛?
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
        // 鏀堕泦 OTA 鐘舵€侀€氱煡锛堟潵鑷?ESP32 0xFF0B 鐗瑰緛锛?
        viewModelScope.launch {
            bleManager.otaStatusFlow().collect { status ->
                when (status.state) {
                    OtaState.Receiving -> {
                        // 璁惧鎶ュ憡姝ｅ湪鎺ユ敹锛岀敤璁惧绔繘搴︽洿鏂?UI
                        _uiState.value = _uiState.value.copy(
                            otaState = OtaState.Transmitting,
                            progress = status.progress,
                            statusText = "姝ｅ湪浼犺緭鍥轰欢... ${status.progress}%"
                        )
                        // 棣栨鏀跺埌 receiving 鐘舵€佹椂鍚姩鍒嗗潡浼犺緭
                        if (!isSending) {
                            startSendingChunks()
                        }
                    }
                    OtaState.Validating -> {
                        _uiState.value = _uiState.value.copy(
                            otaState = OtaState.Validating,
                            statusText = "姝ｅ湪楠岃瘉鍥轰欢..."
                        )
                    }
                    OtaState.Updating -> {
                        _uiState.value = _uiState.value.copy(
                            otaState = OtaState.Updating,
                            statusText = "姝ｅ湪鍐欏叆鍚姩鍒嗗尯..."
                        )
                    }
                    OtaState.Success -> {
                        _uiState.value = _uiState.value.copy(
                            otaState = OtaState.Success,
                            progress = 100,
                            statusText = "鍥轰欢鍗囩骇鎴愬姛锛岃閲嶅惎璁惧"
                        )
                        cleanup()
                    }
                    OtaState.Error -> {
                        if (isSending) {
                            _uiState.value = _uiState.value.copy(
                                otaState = OtaState.Error,
                                statusText = "璁惧鎶ュ憡鍗囩骇澶辫触"
                            )
                            cleanup()
                        }
                    }
                    OtaState.Idle -> {
                        if (_uiState.value.otaState != OtaState.Idle) {
                            _uiState.value = _uiState.value.copy(
                                otaState = OtaState.Idle,
                                statusText = "OTA 宸查噸缃紝璇烽噸鏂板紑濮?
                            )
                        }
                    }
                    else -> { /* Beginning, Transmitting, Ending 鐢辨湰鍦伴┍鍔?*/ }
                }
            }
        }

        // 鐩戝惉 BLE 鏂繛
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                if (state is BleConnectionState.Disconnected && isSending) {
                    _uiState.value = _uiState.value.copy(
                        otaState = OtaState.Error,
                        statusText = "BLE 杩炴帴鏂紑锛屼紶杈撲腑姝?
                    )
                    cleanup()
                }
            }
        }
    }

    // ==================== 鐢ㄦ埛鎿嶄綔 ====================

    /**
     * 閫夋嫨鍥轰欢鏂囦欢
     */
    fun selectFirmware(uri: Uri) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                // 鎸佷箙鍖?URI 璇诲彇鏉冮檺
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val size = context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
                val fileName = uri.lastPathSegment ?: "firmware.bin"

                firmwareUri = uri
                _uiState.value = _uiState.value.copy(
                    firmwareFileName = fileName,
                    firmwareSize = size,
                    statusText = "宸查€夋嫨: $fileName (${formatFileSize(size)})",
                    otaState = OtaState.Idle,
                    progress = 0
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    statusText = "鏂囦欢璇诲彇澶辫触: ${e.message}"
                )
            }
        }
    }

    /**
     * 寮€濮?OTA 鍗囩骇
     *
     * 娴佺▼锛氭墦寮€鏂囦欢 鈫?鍙戦€?BEGIN,{size} 鈫?绛夊緟璁惧 Ready 鈫?鍒嗗潡浼犺緭 鈫?鍙戦€?END
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
                    statusText = "姝ｅ湪鍑嗗 OTA..."
                )

                // 鎵撳紑鏂囦欢娴佷緵鍒嗗潡璇诲彇
                val inputStream = getApplication<Application>()
                    .contentResolver.openInputStream(uri) ?: run {
                    _uiState.value = _uiState.value.copy(
                        otaState = OtaState.Error,
                        statusText = "鏃犳硶鎵撳紑鍥轰欢鏂囦欢"
                    )
                    return@launch
                }
                firmwareStream = inputStream.buffered()
                totalBytesSent = 0L

                // 鍙戦€?BEGIN 鍛戒护閫氱煡璁惧鍑嗗鎺ユ敹
                bleManager.sendOtaCommand("BEGIN,${state.firmwareSize}")

                // 绛夊緟璁惧閫氳繃 0xFF0B 閫氱煡鍥炲 receiving 鐘舵€佸悗鍚姩浼犺緭
                // 棣栨鏀跺埌 receiving 鏃剁敱 otaStatusFlow 鐨勬敹闆嗗櫒瑙﹀彂 startSendingChunks()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    otaState = OtaState.Error,
                    statusText = "OTA 鍚姩澶辫触: ${e.message}"
                )
                cleanup()
            }
        }
    }

    /**
     * 涓 OTA 鍗囩骇
     */
    fun abortOta() {
        otaJob?.cancel()
        viewModelScope.launch {
            bleManager.sendOtaCommand("ABORT")
            cleanup()
            _uiState.value = _uiState.value.copy(
                otaState = OtaState.Idle,
                progress = 0,
                statusText = "OTA 宸蹭腑姝?
            )
        }
    }

    /**
     * 閲嶅惎璁惧
     */
    fun rebootDevice() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                statusText = "姝ｅ湪閲嶅惎璁惧..."
            )
            bleManager.sendOtaCommand("REBOOT")
        }
    }

    /**
     * 鏌ヨ璁惧 OTA 鐘舵€?
     */
    fun queryStatus() {
        bleManager.queryOtaStatus()
    }

    // ==================== 鍐呴儴瀹炵幇 ====================

    /**
     * 鍚姩鍒嗗潡浼犺緭鍗忕▼
     *
     * 鐢?otaStatusFlow 棣栨鏀跺埌 Receiving 鐘舵€佹椂瑙﹀彂銆?
     * 閫氳繃 [BleProvisioningManager.writeCompletionFlow] 瀹炵幇娴佹帶锛?
     * 姣忓啓鍏ヤ竴鍧楀悗绛夊緟 onCharacteristicWrite 鍥炶皟锛屽啀鍐欏叆涓嬩竴鍧楋紝
     * 閬垮厤 BLE 鏍堢紦鍐插尯婧㈠嚭銆?
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

                    // 鏈湴杩涘害璁＄畻锛堣澶囩杩涘害閫氳繃 otaStatusFlow 瑕嗙洊锛?
                    val localProgress = ((totalBytesSent * 100) / totalSize).toInt().coerceIn(0, 100)
                    if (_uiState.value.otaState == OtaState.Transmitting) {
                        _uiState.value = _uiState.value.copy(
                            progress = localProgress,
                            statusText = "姝ｅ湪浼犺緭鍥轰欢... $localProgress%"
                        )
                    }

                    // 绛夊緟鏈鍐欏叆瀹屾垚鍥炶皟锛屽疄鐜版祦鎺?
                    withTimeout(10_000) { bleManager.writeCompletionFlow.first() }
                }

                // 鎵€鏈夋暟鎹彂閫佸畬姣曪紝鍙戦€?END 鍛戒护
                bleManager.sendOtaCommand("END")
                _uiState.value = _uiState.value.copy(
                    otaState = OtaState.Ending,
                    statusText = "鏁版嵁浼犺緭瀹屾垚锛岀瓑寰呰澶囬獙璇?.."
                )
            } catch (e: CancellationException) {
                // 鍗忕▼琚彇娑堬紙鐢ㄦ埛涓锛夛紝涓嶅鐞?
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    otaState = OtaState.Error,
                    statusText = "浼犺緭澶辫触: ${e.message}"
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

