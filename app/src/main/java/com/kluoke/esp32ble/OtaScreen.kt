package com.kluoke.esp32ble

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// 复用 ProvisioningScreen 的调色板
private val DeepBlue = Color(0xFF0D1B2A)
private val DarkNavy = Color(0xFF1B2838)
private val AccentCyan = Color(0xFF00D4FF)
private val SuccessGreen = Color(0xFF00E676)
private val ErrorRed = Color(0xFFFF5252)
private val CardBg = Color(0xFF1E2D3D)
private val SubtleGray = Color(0xFF8899AA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtaScreen(
    viewModel: OtaViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.selectFirmware(it) }
    }

    OtaScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onSelectFile = {
            filePickerLauncher.launch(arrayOf("*/*"))
        },
        onStart = viewModel::startOta,
        onAbort = viewModel::abortOta,
        onReboot = viewModel::rebootDevice
    )
}

// ==================== 纯 UI 主体 ====================

@Composable
fun OtaScreenContent(
    uiState: OtaUiState,
    onBackClick: () -> Unit,
    onSelectFile: () -> Unit,
    onStart: () -> Unit,
    onAbort: () -> Unit,
    onReboot: () -> Unit
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
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部导航栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                Text(
                    "固件升级 (OTA)",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(24.dp))

            // OTA 状态卡片
            OtaStatusCard(
                otaState = uiState.otaState,
                statusText = uiState.statusText
            )

            Spacer(Modifier.weight(1f))

            // 圆形进度指示器
            OtaProgressIndicator(
                progress = uiState.progress,
                modifier = Modifier.size(200.dp),
                activeColor = when (uiState.otaState) {
                    is OtaState.Error -> ErrorRed
                    is OtaState.Success -> SuccessGreen
                    else -> AccentCyan
                }
            )

            Spacer(Modifier.height(12.dp))

            // 进度百分比
            Text(
                text = "${uiState.progress}%",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(24.dp))

            // 固件文件信息卡片
            uiState.firmwareFileName?.let { fileName ->
                FileInfoCard(
                    fileName = fileName,
                    fileSize = uiState.firmwareSize
                )
                Spacer(Modifier.height(24.dp))
            }

            Spacer(Modifier.weight(1f))

            // 操作按钮
            OtaActionButtons(
                otaState = uiState.otaState,
                hasFile = uiState.firmwareFileName != null,
                onSelectFile = onSelectFile,
                onStart = onStart,
                onAbort = onAbort,
                onReboot = onReboot
            )
        }
    }
}

// ==================== OTA 状态卡片 ====================

@Composable
private fun OtaStatusCard(
    otaState: OtaState,
    statusText: String
) {
    val cardGradient = Brush.horizontalGradient(
        colors = when (otaState) {
            is OtaState.Success -> listOf(Color(0xFF004D40), Color(0xFF00695C))
            is OtaState.Error -> listOf(Color(0xFF4A0000), Color(0xFF7F0000))
            is OtaState.Transmitting, is OtaState.Validating, is OtaState.Updating ->
                listOf(Color(0xFF0D2137), Color(0xFF1A3A5C))
            else -> listOf(Color(0xFF0D2137), Color(0xFF162A45))
        }
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardGradient, RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            when (otaState) {
                                is OtaState.Success -> SuccessGreen
                                is OtaState.Error -> ErrorRed
                                is OtaState.Transmitting -> AccentCyan
                                else -> SubtleGray
                            }.copy(alpha = 0.15f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = when (otaState) {
                            is OtaState.Success -> Icons.Default.Refresh
                            is OtaState.Error -> Icons.Default.Stop
                            is OtaState.Transmitting, is OtaState.Validating, is OtaState.Updating ->
                                Icons.Default.CloudUpload
                            else -> Icons.Default.Description
                        },
                        contentDescription = null,
                        tint = when (otaState) {
                            is OtaState.Success -> SuccessGreen
                            is OtaState.Error -> ErrorRed
                            is OtaState.Transmitting -> AccentCyan
                            else -> SubtleGray
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (otaState) {
                            is OtaState.Idle -> "就绪"
                            is OtaState.Beginning -> "准备中..."
                            is OtaState.Receiving -> "接收中"
                            is OtaState.Transmitting -> "传输中"
                            is OtaState.Validating -> "验证中"
                            is OtaState.Updating -> "写入中"
                            is OtaState.Ending -> "完成传输"
                            is OtaState.Success -> "升级成功"
                            is OtaState.Error -> "升级失败"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ==================== 圆形进度指示器 ====================

@Composable
private fun OtaProgressIndicator(
    progress: Int,
    modifier: Modifier = Modifier,
    activeColor: Color = AccentCyan
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress / 100f,
        animationSpec = tween(300),
        label = "otaProgress"
    )

    val trackColor = Color(0xFF2A3A4A)

    Canvas(modifier = modifier) {
        val strokeWidth = 10.dp.toPx()
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

        // 底层轨道
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // 进度弧
        if (animatedProgress > 0f) {
            drawArc(
                color = activeColor,
                startAngle = -90f,
                sweepAngle = animatedProgress * 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
    }
}

// ==================== 固件文件信息 ====================

@Composable
private fun FileInfoCard(
    fileName: String,
    fileSize: Long
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    fileName,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    OtaViewModel.formatFileSize(fileSize),
                    color = SubtleGray,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ==================== 操作按钮 ====================

@Composable
private fun OtaActionButtons(
    otaState: OtaState,
    hasFile: Boolean,
    onSelectFile: () -> Unit,
    onStart: () -> Unit,
    onAbort: () -> Unit,
    onReboot: () -> Unit
) {
    val isIdle = otaState is OtaState.Idle
    val isSuccess = otaState is OtaState.Success
    val isBusy = otaState is OtaState.Transmitting ||
            otaState is OtaState.Beginning ||
            otaState is OtaState.Validating ||
            otaState is OtaState.Updating ||
            otaState is OtaState.Ending

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // 选择文件 / 重试按钮
        if (isIdle || otaState is OtaState.Error) {
            OtaButton(
                text = "选择固件",
                icon = Icons.Default.Description,
                onClick = onSelectFile,
                modifier = Modifier.weight(1f)
            )
        }

        // 开始升级
        if (isIdle && hasFile) {
            OtaButton(
                text = "开始升级",
                icon = Icons.Default.CloudUpload,
                onClick = onStart,
                isPrimary = true,
                modifier = Modifier.weight(1f)
            )
        }

        // 中止按钮
        if (isBusy) {
            OtaButton(
                text = "中止",
                icon = Icons.Default.Stop,
                onClick = onAbort,
                isDanger = true,
                modifier = Modifier.weight(1f)
            )
        }

        // 重启按钮
        if (isSuccess) {
            OtaButton(
                text = "重启设备",
                icon = Icons.Default.PowerSettingsNew,
                onClick = onReboot,
                isPrimary = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun OtaButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    isDanger: Boolean = false
) {
    val bgColor by animateColorAsState(
        targetValue = when {
            isPrimary -> Color(0xFF006A8E)
            isDanger -> Color(0xFF5A1A1A)
            else -> Color(0xFF1A3A5C)
        },
        animationSpec = tween(300),
        label = "otaBtnBg"
    )

    Card(
        modifier = modifier
            .height(52.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                icon, contentDescription = null,
                tint = if (isDanger) ErrorRed else if (isPrimary) AccentCyan else Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
        }
    }
}

// ==================== 预览 ====================

@Preview(showBackground = true, backgroundColor = 0xFF0D1B2A, device = "id:pixel_5")
@Composable
private fun OtaScreenTransmittingPreview() {
    OtaScreenContent(
        uiState = OtaUiState(
            statusText = "正在传输固件... 45%",
            otaState = OtaState.Transmitting,
            progress = 45,
            firmwareFileName = "firmware_v2.1.0.bin",
            firmwareSize = 1024 * 1024
        ),
        onBackClick = {},
        onSelectFile = {},
        onStart = {},
        onAbort = {},
        onReboot = {}
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1B2A, device = "id:pixel_5")
@Composable
private fun OtaScreenSuccessPreview() {
    OtaScreenContent(
        uiState = OtaUiState(
            statusText = "升级成功，请重启设备",
            otaState = OtaState.Success,
            progress = 100,
            firmwareFileName = "firmware_v2.1.0.bin",
            firmwareSize = 1024 * 1024
        ),
        onBackClick = {},
        onSelectFile = {},
        onStart = {},
        onAbort = {},
        onReboot = {}
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0D1B2A, device = "id:pixel_5")
@Composable
private fun OtaScreenIdlePreview() {
    OtaScreenContent(
        uiState = OtaUiState(
            statusText = "请选择固件文件",
            otaState = OtaState.Idle,
            progress = 0,
            firmwareFileName = null,
            firmwareSize = 0
        ),
        onBackClick = {},
        onSelectFile = {},
        onStart = {},
        onAbort = {},
        onReboot = {}
    )
}
