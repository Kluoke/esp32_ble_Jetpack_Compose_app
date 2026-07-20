package com.kluoke.esp32ble

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// ==================== 调色板 ====================

private val DeepBlue = Color(0xFF0D1B2A)
private val DarkNavy = Color(0xFF1B2838)
private val AccentCyan = Color(0xFF00D4FF)
private val SuccessGreen = Color(0xFF00E676)
private val ErrorRed = Color(0xFFFF5252)
private val CardBg = Color(0xFF1E2D3D)
private val SubtleGray = Color(0xFF8899AA)

// ==================== 入口 ====================

@Composable
fun ProvisioningScreen(
    viewModel: ProvisioningViewModel,
    onScanClick: () -> Unit,
    onOtaClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ProvisioningContent(
        status = uiState.statusText,
        aps = uiState.wifiAps,
        ssid = uiState.ssid,
        password = uiState.password,
        isScanning = uiState.isScanning,
        isDeviceReady = uiState.isDeviceReady,
        connectionState = uiState.connectionState,
        onSsidChange = viewModel::updateSsid,
        onPasswordChange = viewModel::updatePassword,
        onScanClick = onScanClick,
        onGetWifiClick = viewModel::scanWifi,
        onProvisionClick = viewModel::provision,
        onOtaClick = onOtaClick
    )
}

// ==================== 主体 ====================

@Composable
fun ProvisioningContent(
    status: String,
    aps: List<WifiAp>,
    ssid: String,
    password: String,
    isScanning: Boolean,
    isDeviceReady: Boolean,
    connectionState: BleConnectionState,
    onSsidChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onScanClick: () -> Unit,
    onGetWifiClick: () -> Unit,
    onProvisionClick: () -> Unit,
    onOtaClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DeepBlue, DarkNavy, Color(0xFF0A1628))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard(
                status = status,
                isScanning = isScanning,
                connectionState = connectionState
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ActionButton(
                    text = if (isScanning) "扫描中..." else "连接设备",
                    icon = if (isScanning) Icons.AutoMirrored.Filled.BluetoothSearching else Icons.Default.Bluetooth,
                    onClick = onScanClick,
                    enabled = !isScanning,
                    isScanning = isScanning,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    text = "扫描 Wi-Fi",
                    icon = Icons.Default.Wifi,
                    onClick = onGetWifiClick,
                    enabled = isDeviceReady,
                    isScanning = false,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                "附近 Wi-Fi",
                style = MaterialTheme.typography.labelLarge,
                color = SubtleGray,
                fontWeight = FontWeight.Medium
            )

            WifiList(
                aps = aps,
                selectedSsid = ssid,
                onApClick = onSsidChange
            )

            StyledTextField(
                value = ssid,
                onValueChange = onSsidChange,
                label = "Wi-Fi 名称",
                icon = Icons.Default.Wifi
            )

            StyledTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = "密码",
                icon = Icons.Default.Lock,
                visualTransformation = PasswordVisualTransformation()
            )

            ProvisionButton(
                enabled = ssid.isNotEmpty() && isDeviceReady,
                onClick = onProvisionClick
            )

            // OTA 入口按钮
            OtaEntryButton(
                enabled = isDeviceReady,
                onClick = onOtaClick
            )
        }
    }
}

// ==================== OTA 入口按钮 ====================

@Composable
private fun OtaEntryButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (enabled) Color(0xFF006A8E) else Color(0xFF1A2233),
        animationSpec = tween(300),
        label = "otaEntryBg"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (!enabled) Modifier.alpha(0.5f) else Modifier),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (enabled) 2.dp else 0.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.CloudUpload,
                contentDescription = null,
                tint = if (enabled) AccentCyan else SubtleGray,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "固件升级 (OTA)",
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
        }
    }
}

// ==================== 状态卡片 ====================

@Composable
private fun StatusCard(
    status: String,
    isScanning: Boolean,
    connectionState: BleConnectionState
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val (icon, tint) = when {
        connectionState is BleConnectionState.Connected -> Icons.Default.Bluetooth to SuccessGreen
        connectionState is BleConnectionState.Error -> Icons.Default.Bluetooth to ErrorRed
        connectionState is BleConnectionState.Disconnected -> Icons.Default.Bluetooth to SubtleGray
        isScanning -> Icons.AutoMirrored.Filled.BluetoothSearching to AccentCyan
        else -> Icons.Default.Bluetooth to AccentCyan
    }

    val cardGradient = Brush.horizontalGradient(
        colors = when (connectionState) {
            is BleConnectionState.Connected -> listOf(Color(0xFF004D40), Color(0xFF00695C))
            is BleConnectionState.Error -> listOf(Color(0xFF4A0000), Color(0xFF7F0000))
            else -> listOf(Color(0xFF0D2137), Color(0xFF162A45))
        }
    )

    val title = when (connectionState) {
        is BleConnectionState.Connected -> "设备已连接"
        is BleConnectionState.Error -> "连接错误"
        is BleConnectionState.Disconnected -> "设备未连接"
        is BleConnectionState.Scanning -> "正在扫描..."
        is BleConnectionState.Connecting -> "正在连接..."
        is BleConnectionState.NegotiatingMtu -> "协商中..."
        is BleConnectionState.Subscribing -> "订阅中..."
        is BleConnectionState.Idle -> "ESP32 配网"
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .background(cardGradient, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                tint.copy(alpha = if (isScanning) pulseAlpha * 0.2f else 0.15f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = tint.copy(alpha = if (isScanning) pulseAlpha else 1f),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (isScanning) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp)),
                        color = AccentCyan,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                }
            }
        }
    }
}

// ==================== 操作按钮 ====================

@Composable
private fun ActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    isScanning: Boolean,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (enabled) Color(0xFF1A3A5C) else Color(0xFF1A2233),
        animationSpec = tween(300),
        label = "actionBtnBg"
    )

    Card(
        modifier = modifier
            .height(52.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (!enabled) Modifier.alpha(0.5f) else Modifier),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (enabled) 2.dp else 0.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = AccentCyan
                )
            } else {
                Icon(
                    icon, contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
        }
    }
}

// ==================== Wi-Fi 列表 ====================

@Composable
private fun ColumnScope.WifiList(
    aps: List<WifiAp>,
    selectedSsid: String,
    onApClick: (String) -> Unit
) {
    AnimatedVisibility(
        visible = aps.isNotEmpty(),
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            items(aps, key = { it.ssid }) { ap ->
                WifiCard(
                    ap = ap,
                    isSelected = ap.ssid == selectedSsid,
                    onClick = { onApClick(ap.ssid) }
                )
            }
        }
    }
}

// ==================== Wi-Fi 卡片 ====================

@Composable
private fun WifiCard(
    ap: WifiAp,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) Color(0xFF1A3A5C) else CardBg,
        animationSpec = tween(200),
        label = "wifiCardBg"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SignalBars(rssi = ap.rssi, modifier = Modifier.size(24.dp))

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    ap.ssid,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${ap.rssi} dBm",
                    color = SubtleGray,
                    fontSize = 12.sp
                )
            }

            if (ap.authMode > 0) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "已加密",
                    tint = SubtleGray,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(AccentCyan, CircleShape)
                )
            }
        }
    }
}

// ==================== 信号强度柱状图 ====================

@Composable
private fun SignalBars(rssi: Int, modifier: Modifier = Modifier) {
    val activeBars = when {
        rssi >= -50 -> 4
        rssi >= -60 -> 3
        rssi >= -70 -> 2
        else -> 1
    }
    val barColor = when {
        rssi >= -50 -> SuccessGreen
        rssi >= -60 -> AccentCyan
        rssi >= -70 -> Color(0xFFFFA726)
        else -> ErrorRed
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        (1..4).forEach { i ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((i * 5 + 3).dp)
                    .background(
                        color = if (i <= activeBars) barColor else Color(0xFF2A3A4A),
                        shape = RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

// ==================== 输入框 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StyledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = SubtleGray) },
        leadingIcon = {
            Icon(
                icon, contentDescription = null,
                tint = SubtleGray,
                modifier = Modifier.size(20.dp)
            )
        },
        singleLine = true,
        visualTransformation = visualTransformation,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = AccentCyan,
            unfocusedBorderColor = Color(0xFF2A3A4A),
            focusedLabelColor = AccentCyan,
            cursorColor = AccentCyan
        )
    )
}

// ==================== 配网按钮 ====================

@Composable
private fun ProvisionButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF006A8E),
            disabledContainerColor = Color(0xFF1A2233),
            contentColor = Color.White
        )
    ) {
        Text(
            "配网",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

// ==================== 预览 ====================

@Preview(showBackground = true, backgroundColor = 0xFF0D1B2A, device = "id:pixel_5")
@Composable
private fun ProvisioningScreenConnectedPreview() {
    ProvisioningContent(
        status = "设备已连接，等待配网",
        aps = listOf(
            WifiAp("HomeWiFi_5G", -45, 3),
            WifiAp("Office_Guest", -62, 0),
            WifiAp("Neighbor_AP", -78, 3)
        ),
        ssid = "HomeWiFi_5G",
        password = "mySecretPass",
        isScanning = false,
        isDeviceReady = true,
        connectionState = BleConnectionState.Connected,
        onSsidChange = {},
        onPasswordChange = {},
        onScanClick = {},
        onGetWifiClick = {},
        onProvisionClick = {},
        onOtaClick = {}
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1B2A, device = "id:pixel_5")
@Composable
private fun ProvisioningScreenScanningPreview() {
    ProvisioningContent(
        status = "正在扫描 BLE 设备...",
        aps = emptyList(),
        ssid = "",
        password = "",
        isScanning = true,
        isDeviceReady = false,
        connectionState = BleConnectionState.Scanning,
        onSsidChange = {},
        onPasswordChange = {},
        onScanClick = {},
        onGetWifiClick = {},
        onProvisionClick = {},
        onOtaClick = {}
    )
}
