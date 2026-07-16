package com.kluoke.esp32ble

import android.app.Application
import android.content.Context

/**
 * 自定义 Application
 * 在 onCreate 中初始化依赖注入容器
 */
class AppApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * 便捷扩展属性，从 Context 获取 AppContainer
 */
fun Context.appContainer(): AppContainer {
    return (applicationContext as AppApplication).container
}
