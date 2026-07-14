package com.kluoke.esp32ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity(), BleProvisioningListener {
    private lateinit var ble: BleProvisioningManager

    // 状态提升：将 UI 状态保持在 Activity 中
    private var status by mutableStateOf("请连接 ESP32")
    private var aps by mutableStateOf(listOf<WifiAp>())
    private var ssid by mutableStateOf("")
    private var password by mutableStateOf("")

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) {
            scanDevice()
        } else {
            status = "需要蓝牙权限"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleProvisioningManager(this, this)
        
        setContent {
            MaterialTheme {
                ProvisioningScreen(
                    status = status,
                    aps = aps,
                    ssid = ssid,
                    password = password,
                    onSsidChange = { ssid = it },
                    onPasswordChange = { password = it },
                    onScanClick = { checkPermissionsAndScan() },
                    onGetWifiClick = { ble.scanWifi() },
                    onProvisionClick = { ble.provision(ssid, password) }
                )
            }
        }
    }

    private fun checkPermissionsAndScan() {
        status = "正在检查权限..."
        val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        permissions.launch(request)
    }

    @SuppressLint("MissingPermission")
    private fun scanDevice() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            status = "设备不支持蓝牙"
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            status = "蓝牙未开启，请在设置中开启蓝牙"
            return
        }

        status = "正在扫描设备..."
        android.util.Log.d("BLE", "开始扫描，目标名称: ${BleProvisioningManager.DEVICE_NAME}")
        
        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val deviceName = result.device.name ?: "Unknown"
                android.util.Log.d("BLE", "发现设备: $deviceName (${result.device.address})")
                
                if (deviceName == BleProvisioningManager.DEVICE_NAME || deviceName.contains("ESP32", ignoreCase = true)) {
                    android.util.Log.i("BLE", "匹配成功，正在连接: $deviceName")
                    status = "找到设备: $deviceName，正在连接..."
                    scanner.stopScan(this)
                    ble.connect(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                android.util.Log.e("BLE", "扫描失败: $errorCode")
                status = "扫描失败: 错误码 $errorCode"
            }
        })
    }

    // --- Listener 实现 ---

    override fun onStatusChanged(status: String) {
        runOnUiThread { this.status = status }
    }

    override fun onWifiApReceived(accessPoint: WifiAp) {
        runOnUiThread {
            aps = (aps.filter { it.ssid != accessPoint.ssid } + accessPoint).sortedByDescending { it.rssi }
        }
    }

    override fun onReady() {
        onStatusChanged("设备已准备好")
    }

    override fun onError(message: String) {
        onStatusChanged(message)
    }

    override fun onDestroy() {
        ble.close()
        super.onDestroy()
    }
}

/**
 * 将 UI 抽离成独立的 Composable 函数。
 * 这样做的好处是：
 * 1. 逻辑与 UI 分离，代码更清晰。
 * 2. 方便进行 Compose Preview 预览。
 */
@Composable
fun ProvisioningScreen(
    status: String,
    aps: List<WifiAp>,
    ssid: String,
    password: String,
    onSsidChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onScanClick: () -> Unit,
    onGetWifiClick: () -> Unit,
    onProvisionClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 状态显示
            Text(
                text = status,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            // 操作按钮
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScanClick, modifier = Modifier.weight(1f)) {
                    Text("连接设备")
                }
                Button(onClick = onGetWifiClick, modifier = Modifier.weight(1f)) {
                    Text("扫描 Wi-Fi")
                }
            }

            // Wi-Fi 列表
            Text("附近 Wi-Fi:", style = MaterialTheme.typography.labelLarge)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(aps, key = { it.ssid }) { a ->
                    ListItem(
                        headlineContent = { Text(a.ssid) },
                        supportingContent = { Text("${a.rssi} dBm") },
                        modifier = Modifier.clickable { onSsidChange(a.ssid) }
                    )
                    HorizontalDivider()
                }
            }

            // 输入区域
            OutlinedTextField(
                value = ssid,
                onValueChange = onSsidChange,
                label = { Text("Wi-Fi 名称") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("密码") },
                modifier = Modifier.fillMaxWidth()
            )

            // 配网按钮
            Button(
                onClick = onProvisionClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = ssid.isNotEmpty()
            ) {
                Text("发送配置并配网")
            }
        }
    }
}

/**
 * 预览函数，让你可以直接在 Android Studio 中看到 UI 效果。
 */
@Preview(showBackground = true, name = "配网界面预览")
@Composable
fun ProvisioningPreview() {
    MaterialTheme {
        ProvisioningScreen(
            status = "已连接到 ESP32",
            aps = listOf(
                WifiAp("Kluoke_5G", -45, 3),
                WifiAp("Guest_WiFi", -70, 2),
                WifiAp("Office_Network", -60, 3)
            ),
            ssid = "Kluoke_5G",
            password = "password123",
            onSsidChange = {},
            onPasswordChange = {},
            onScanClick = {},
            onGetWifiClick = {},
            onProvisionClick = {}
        )
    }
}
