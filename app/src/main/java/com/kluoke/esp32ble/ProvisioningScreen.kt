package com.kluoke.esp32ble

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 配网主界面
 *
 * 纯 UI 组件，通过 ViewModel 提供的 StateFlow 驱动渲染。
 * 使用 collectAsStateWithLifecycle 确保生命周期安全。
 */
@Composable
fun ProvisioningScreen(
    viewModel: ProvisioningViewModel,
    onScanClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ProvisioningContent(
        status = uiState.statusText,
        aps = uiState.wifiAps,
        ssid = uiState.ssid,
        password = uiState.password,
        isScanning = uiState.isScanning,
        isDeviceReady = uiState.isDeviceReady,
        onSsidChange = viewModel::updateSsid,
        onPasswordChange = viewModel::updatePassword,
        onScanClick = onScanClick,
        onGetWifiClick = viewModel::scanWifi,
        onProvisionClick = viewModel::provision
    )
}

/**
 * 无 ViewModel 依赖的纯 UI 组件，方便测试和预览
 */
@Composable
fun ProvisioningContent(
    status: String,
    aps: List<WifiAp>,
    ssid: String,
    password: String,
    isScanning: Boolean,
    isDeviceReady: Boolean,
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
                Button(
                    onClick = onScanClick,
                    modifier = Modifier.weight(1f),
                    enabled = !isScanning
                ) {
                    Text(if (isScanning) "扫描中..." else "连接设备")
                }
                Button(
                    onClick = onGetWifiClick,
                    modifier = Modifier.weight(1f),
                    enabled = isDeviceReady
                ) {
                    Text("扫描 Wi-Fi")
                }
            }

            // Wi-Fi 列表
            Text("附近 Wi-Fi:", style = MaterialTheme.typography.labelLarge)
            WifiList(
                aps = aps,
                onApClick = onSsidChange
            )

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
                enabled = ssid.isNotEmpty() && isDeviceReady
            ) {
                Text("发送配置并配网")
            }
        }
    }
}

/**
 * Wi-Fi 列表组件
 */
@Composable
private fun WifiList(
    aps: List<WifiAp>,
    onApClick: (String) -> Unit
) {
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
                modifier = Modifier.clickable { onApClick(a.ssid) }
            )
            HorizontalDivider()
        }
    }
}

@Preview(showBackground = true, name = "配网界面预览")
@Composable
fun ProvisioningPreview() {
    MaterialTheme {
        ProvisioningContent(
            status = "已连接到 ESP32",
            aps = listOf(
                WifiAp("Kluoke_5G", -45, 3),
                WifiAp("Guest_WiFi", -70, 2),
                WifiAp("Office_Network", -60, 3)
            ),
            ssid = "Kluoke_5G",
            password = "password123",
            isScanning = false,
            isDeviceReady = true,
            onSsidChange = {},
            onPasswordChange = {},
            onScanClick = {},
            onGetWifiClick = {},
            onProvisionClick = {}
        )
    }
}
