package com.kluoke.esp32ble

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * 主 Activity
 *
 * 职责精简为：
 * 1. 启用 Edge-to-Edge 显示
 * 2. 权限请求
 * 3. Compose Navigation 路由（配网 / OTA）
 * 4. 创建 ViewModel 并驱动 Compose UI
 */
class MainActivity : ComponentActivity() {

    private val provisioningViewModel: ProvisioningViewModel by viewModels()
    private val otaViewModel: OtaViewModel by viewModels()

    // 专门用于扫描相关权限的启动器（成功后会触发扫描）
    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            provisioningViewModel.startScanAndConnect()
        } else {
            provisioningViewModel.updateStatus("部分权限未被允许，无法扫描设备")
        }
    }

    // 专门用于通知权限的启动器（不触发业务逻辑）
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 仅请求，不触发自动扫描
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 使用 enableEdgeToEdge 替代已废弃的 window.statusBarColor/navigationBarColor
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(
                scrim = Color(0xFF0D1B2A).toArgb(),
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(
                scrim = Color(0xFF0D1B2A).toArgb(),
            )
        )

        // 启动时静默请求通知权限 (Android 13+)
        requestNotificationPermission()

        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                AppNavHost(
                    navController = navController,
                    provisioningViewModel = provisioningViewModel,
                    otaViewModel = otaViewModel,
                    onScanClick = ::checkPermissionsAndScan
                )
            }
        }
    }

    /**
     * 检查权限并启动扫描
     */
    private fun checkPermissionsAndScan() {
        provisioningViewModel.updateStatus("正在检查权限...")
        val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        blePermissionLauncher.launch(request)
    }

    /**
     * 请求通知权限 (Android 13+)
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
}

/**
 * 导航图：配网页面 ↔ OTA 页面
 */
@Composable
private fun AppNavHost(
    navController: NavHostController,
    provisioningViewModel: ProvisioningViewModel,
    otaViewModel: OtaViewModel,
    onScanClick: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = "provisioning"
    ) {
        composable("provisioning") {
            ProvisioningScreen(
                viewModel = provisioningViewModel,
                onScanClick = onScanClick,
                onOtaClick = { navController.navigate("ota") }
            )
        }
        composable("ota") {
            OtaScreen(
                viewModel = otaViewModel,
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}