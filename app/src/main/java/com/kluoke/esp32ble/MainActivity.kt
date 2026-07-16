package com.kluoke.esp32ble

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme

/**
 * 主 Activity
 *
 * 职责精简为：
 * 1. 权限请求
 * 2. 创建 ViewModel 并驱动 Compose UI
 *
 * 所有业务逻辑和 UI 状态由 ProvisioningViewModel 管理，
 * UI 渲染由 ProvisioningScreen 负责。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ProvisioningViewModel by viewModels()

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startScanAndConnect()
        } else {
            viewModel.updateStatus("需要蓝牙权限")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ProvisioningScreen(
                    viewModel = viewModel,
                    onScanClick = ::checkPermissionsAndScan
                )
            }
        }
    }

    /**
     * 检查权限并启动扫描
     */
    private fun checkPermissionsAndScan() {
        viewModel.updateStatus("正在检查权限...")
        val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        permissions.launch(request)
    }
}
