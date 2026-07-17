package com.kluoke.esp32ble.fcm

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM 消息推送服务
 *
 * 接收来自服务端的消息通知。
 *
 * 注意：onNewToken() 已被 Firebase 废弃（FCM 正迁移到 Installation IDs），
 * 如需获取 Token，请使用 FirebaseInstallations.getInstance().getToken()。
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "From: ${remoteMessage.from}")

        // 检查消息是否包含数据
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "Message data payload: ${remoteMessage.data}")
        }

        // 检查消息是否包含通知
        remoteMessage.notification?.let {
            Log.d(TAG, "Message Notification Body: ${it.body}")
            // TODO: 构建并显示通知栏通知 (NotificationCompat)
        }
    }

    companion object {
        private const val TAG = "MyFirebaseMsgService"
    }
}
