package com.kluoke.esp32ble

import android.content.Context

/**
 * 手动依赖注入容器
 *
 * Kotlin 2.4.0 刚发布，KSP 尚未发布 2.4.0 对应版本，
 * 因此采用 Google 推荐的手动 DI 模式（AppContainer），
 * 待 KSP 2.4.0 发布后可平滑迁移到 Hilt。
 *
 * @see <a href="https://developer.android.com/training/dependency-injection/manual">Manual DI Guide</a>
 */
class AppContainer(context: Context) {
    val bleScanner = BleScanner(context)
    val bleManager = BleProvisioningManager(context)
}
