package com.example.todooverlay.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 解锁监听。ACTION_USER_PRESENT 从 Android 8.0 起已在清单里隐式注册失效，
 * 所以这里不做静态注册，只由 [UnlockOverlayService] 动态注册后回调。
 */
class UnlockReceiver(
    private val onUnlocked: () -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_USER_PRESENT) {
            onUnlocked()
        }
    }
}
