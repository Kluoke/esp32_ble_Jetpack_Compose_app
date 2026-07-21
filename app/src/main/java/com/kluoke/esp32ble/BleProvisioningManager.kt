Exit code: 0
Wall time: 1.6 seconds
Output:
package com.kluoke.esp32ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class WifiAp(val ssid: String, val rssi: Int, val authMode: Int)
sealed interface BleConnectionState { data object Idle: BleConnectionState; data class Scanning(val target:String): BleConnectionState; data class Connecting(val deviceName:String): BleConnectionState; data class NegotiatingMtu(val deviceName:String): BleConnectionState; data class Subscribing(val deviceName:String): BleConnectionState; data class Connected(val deviceName:String): BleConnectionState; data object Disconnected: BleConnectionState; data class Error(val message:String): BleConnectionState }
sealed interface OtaState { data object Idle: OtaState; data object Beginning: OtaState; data object Receiving: OtaState; data object Transmitting: OtaState; data object Validating: OtaState; data object Updating: OtaState; data object Ending: OtaState; data object Success: OtaState; data object Error: OtaState }
data class OtaStatus(val state: OtaState, val progress: Int = 0)
data class CommandResponse(val sequence: Int, val command: Byte, val error: Int? = null)

/** GATT transport. All business payloads use [BleProtocol]. */
class BleProvisioningManager(context: Context) {
    companion object {
        const val DEVICE_NAME = "ESP32_BLE_DEVICE"; const val DEFAULT_MTU = 23; const val ATT_HEADER_SIZE = 3
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
    private val _responses = MutableSharedFlow<CommandResponse>(extraBufferCapacity = 32)
    val commandResponses: SharedFlow<CommandResponse> = _responses.asSharedFlow()
    private val decoder = BleProtocol.Decoder(); private val otaWriteMutex = Mutex()
    private var otaWriteCompletion: CompletableDeferred<Unit>? = null
    private var gatt: BluetoothGatt? = null; private var servicesDiscovered = false; private var effectiveMtuRaw = DEFAULT_MTU; private var sequence = 1
    val effectiveMtu: Int get() = effectiveMtuRaw - ATT_HEADER_SIZE
    fun setConnectionState(state: BleConnectionState) { _connectionState.value = state }
    fun wifiApFlow(): Flow<WifiAp> = _wifiAps.asSharedFlow(); fun statusFlow(): Flow<String> = _statuses.asSharedFlow(); fun otaStatusFlow(): Flow<OtaStatus> = _otaStatuses.asSharedFlow()

    @SuppressLint("MissingPermission") fun connect(device: BluetoothDevice) { close(); _connectionState.value = BleConnectionState.Connecting(device.name ?: device.address); @Suppress("DEPRECATION") run { gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE) } }
    fun scanWifi() = sendCommand(BleProtocol.Command.WIFI_SCAN)
    fun provision(ssid: String, password: String) { if (ssid.isBlank() || password.isBlank()) return updateError("Wi-Fi \u540d\u79f0\u548c\u5bc6\u7801\u4e0d\u80fd\u4e3a\u7a7a"); try { sendCommand(BleProtocol.Command.SET_WIFI, BleProtocol.setWifiPayload(ssid, password)) } catch (e: IllegalArgumentException) { updateError(e.message ?: "Wi-Fi \u53c2\u6570\u65e0\u6548") } }
    fun sendOtaCommand(command: String) { when (command.substringBefore(',')) { "BEGIN" -> { val size = command.substringAfter(',', "").toLongOrNull() ?: return updateError("OTA \u56fa\u4ef6\u5927\u5c0f\u65e0\u6548"); if (size !in 1..UInt.MAX_VALUE.toLong()) return updateError("OTA \u56fa\u4ef6\u8fc7\u5927"); sendCommand(BleProtocol.Command.OTA_BEGIN, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size.toUInt().toInt()).array()) }; "END" -> sendCommand(BleProtocol.Command.OTA_END); "ABORT" -> sendCommand(BleProtocol.Command.OTA_ABORT); "REBOOT" -> sendCommand(BleProtocol.Command.REBOOT); "QUERY" -> sendCommand(BleProtocol.Command.OTA_QUERY); else -> updateError("\u672a\u77e5 OTA \u547d\u4ee4") } }
    suspend fun sendOtaDataAwait(data: ByteArray) = otaWriteMutex.withLock { val characteristic = characteristic(OTA_DATA_UUID) ?: throw IllegalStateException("OTA \u6570\u636e\u901a\u9053\u4e0d\u53ef\u7528"); val completion = CompletableDeferred<Unit>(); otaWriteCompletion = completion; if (!write(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) { otaWriteCompletion = null; throw IllegalStateException("OTA \u5199\u5165\u672a\u542f\u52a8") }; completion.await() }
    fun queryOtaStatus() = sendCommand(BleProtocol.Command.OTA_QUERY)
    @SuppressLint("MissingPermission") fun close() { gatt?.disconnect(); gatt?.close(); gatt = null; servicesDiscovered = false; effectiveMtuRaw = DEFAULT_MTU; otaWriteCompletion?.cancel(); otaWriteCompletion = null; _connectionState.value = BleConnectionState.Disconnected }
    @SuppressLint("MissingPermission") private fun sendCommand(command: Byte, payload: ByteArray = ByteArray(0)) { val chr = characteristic(COMMAND_UUID) ?: return updateError("\u8bbe\u5907\u5c1a\u672a\u51c6\u5907\u597d"); val requestSequence = sequence; sequence = if (sequence >= 0xFFFF) 1 else sequence + 1; if (!write(chr, BleProtocol.encode(command, requestSequence, payload), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) updateError("\u547d\u4ee4\u5199\u5165\u672a\u542f\u52a8") }
    private fun characteristic(uuid: UUID) = if (servicesDiscovered) gatt?.getService(SERVICE_UUID)?.getCharacteristic(uuid) else null
    @SuppressLint("MissingPermission") private fun write(chr: BluetoothGattCharacteristic, value: ByteArray, type: Int): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) gatt?.writeCharacteristic(chr, value, type) == BluetoothGatt.GATT_SUCCESS else { @Suppress("DEPRECATION") run { chr.writeType = type; chr.value = value; gatt?.writeCharacteristic(chr) == true } }
    @SuppressLint("MissingPermission") private fun enableEvents() { val chr = characteristic(EVENT_UUID) ?: return updateError("\u7f3a\u5c11\u4e8b\u4ef6\u901a\u77e5\u901a\u9053"); val current = gatt ?: return; if (!current.setCharacteristicNotification(chr, true)) return updateError("\u65e0\u6cd5\u542f\u7528\u901a\u77e5"); val d = chr.getDescriptor(CCCD_UUID) ?: return updateError("\u7f3a\u5c11 CCCD"); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) current.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) else @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; current.writeDescriptor(d) } }
    private fun updateError(message: String) { _connectionState.value = BleConnectionState.Error(message) }
    private val callback = object: BluetoothGattCallback() {
        @SuppressLint("MissingPermission") override fun onConnectionStateChange(g:BluetoothGatt,status:Int,newState:Int) { if(status != BluetoothGatt.GATT_SUCCESS) return updateError("\u8fde\u63a5\u5931\u8d25: $status"); if(newState == BluetoothGatt.STATE_CONNECTED) { _connectionState.value=BleConnectionState.NegotiatingMtu(g.device.name ?: g.device.address); if(!g.requestMtu(128)) g.discoverServices() } else if(newState == BluetoothGatt.STATE_DISCONNECTED) { servicesDiscovered=false; _connectionState.value=BleConnectionState.Disconnected } }
        @SuppressLint("MissingPermission") override fun onMtuChanged(g:BluetoothGatt,mtu:Int,status:Int) { if(status==BluetoothGatt.GATT_SUCCESS) effectiveMtuRaw=mtu; g.discoverServices() }
        override fun onServicesDiscovered(g:BluetoothGatt,status:Int) { if(status!=BluetoothGatt.GATT_SUCCESS || g.getService(SERVICE_UUID)==null) return updateError("\u672a\u53d1\u73b0 BLE Protocol v1 \u670d\u52a1"); servicesDiscovered=true; _connectionState.value=BleConnectionState.Subscribing(g.device.name ?: g.device.address); enableEvents() }
        override fun onDescriptorWrite(g:BluetoothGatt,d:BluetoothGattDescriptor,status:Int) { if(status!=BluetoothGatt.GATT_SUCCESS) return updateError("\u8ba2\u9605\u4e8b\u4ef6\u5931\u8d25: $status"); _connectionState.value=BleConnectionState.Connected(g.device.name ?: g.device.address) }
        override fun onCharacteristicWrite(g:BluetoothGatt,chr:BluetoothGattCharacteristic,status:Int) { if(chr.uuid==OTA_DATA_UUID) { val completion=otaWriteCompletion; otaWriteCompletion=null; if(status==BluetoothGatt.GATT_SUCCESS) completion?.complete(Unit) else completion?.completeExceptionally(IllegalStateException("OTA \u5199\u5165\u5931\u8d25: $status")) } else if(status!=BluetoothGatt.GATT_SUCCESS) updateError("\u5199\u5165\u5931\u8d25: $status") }
        @Deprecated("Deprecated in Java") override fun onCharacteristicChanged(g:BluetoothGatt,chr:BluetoothGattCharacteristic) { @Suppress("DEPRECATION") onEvent(chr.uuid, chr.value ?: ByteArray(0)) }
        override fun onCharacteristicChanged(g:BluetoothGatt,chr:BluetoothGattCharacteristic,value:ByteArray) = onEvent(chr.uuid,value)
    }
    private fun onEvent(uuid:UUID,bytes:ByteArray) { if(uuid!=EVENT_UUID)return; decoder.append(bytes).forEach { p -> when(p.command) { BleProtocol.Command.ACK -> _responses.tryEmit(CommandResponse(p.sequence,p.payload.firstOrNull() ?: 0)); BleProtocol.Command.ERROR -> { val cmd=p.payload.getOrNull(0) ?: 0; val code=p.payload.getOrNull(1)?.toInt()?.and(0xff) ?: -1; _responses.tryEmit(CommandResponse(p.sequence,cmd,code)); updateError("\u8bbe\u5907\u62d2\u7edd\u547d\u4ee4: $code") }; BleProtocol.Command.WIFI_STATUS -> _statuses.tryEmit(displayStatus(p.payload.decodeToString())); BleProtocol.Command.WIFI_SCAN_RESULT -> parseAp(p.payload)?.let(_wifiAps::tryEmit); BleProtocol.Command.OTA_STATUS -> if(p.payload.size>=2) _otaStatuses.tryEmit(OtaStatus(mapOtaState(p.payload[0]),p.payload[1].toInt().and(0xff).coerceIn(0,100))) } } }
    private fun parseAp(p:ByteArray):WifiAp? { val n=p.firstOrNull()?.toInt()?.and(0xff) ?: return null; if(p.size!=n+3)return null; return WifiAp(p.copyOfRange(1,n+1).decodeToString(),p[n+1].toInt(),p[n+2].toInt().and(0xff)) }
    private fun mapOtaState(value:Byte):OtaState = when(value.toInt().and(0xff)) { 0,1 -> OtaState.Idle; 2 -> OtaState.Receiving; 3 -> OtaState.Validating; 4 -> OtaState.Updating; 5 -> OtaState.Success; 6 -> OtaState.Error; else -> OtaState.Idle }
    private fun displayStatus(s:String)=when(s) { "scanning"->"\u8bbe\u5907\u6b63\u5728\u626b\u63cf Wi-Fi"; "scan_done"->"\u626b\u63cf\u5b8c\u6210"; "scan_busy"->"\u8bbe\u5907\u6b63\u5728\u626b\u63cf\uff0c\u8bf7\u7a0d\u5019"; "scan_failed"->"Wi-Fi \u626b\u63cf\u5931\u8d25"; "connecting"->"\u8bbe\u5907\u6b63\u5728\u8fde\u63a5 Wi-Fi"; else->s }
}

