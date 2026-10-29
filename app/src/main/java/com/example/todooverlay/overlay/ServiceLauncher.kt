package com.example.todooverlay.overlay

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 统一的启动/停止入口。
 * Android 12+ 从后台启动前台服务会抛 ForegroundServiceStartNotAllowedException，
 * 所有调用点都必须容忍这个失败（用户下次打开 App 时会再拉起）。
 */
fun UnlockOverlayService.Companion.start(context: Context, action: String? = null) {
    val intent = Intent(context, UnlockOverlayService::class.java)
    if (action != null) intent.action = action
    try {
        context.startForegroundService(intent)
    } catch (t: Throwable) {
        Log.w("UnlockOverlayService", "startForegroundService rejected", t)
    }
}

fun UnlockOverlayService.Companion.stop(context: Context) {
    try {
        context.stopService(Intent(context, UnlockOverlayService::class.java))
    } catch (t: Throwable) {
        Log.w("UnlockOverlayService", "stopService failed", t)
    }
}

/** 立即弹一次卡片，用于验证权限与服务是否正常 */
fun UnlockOverlayService.Companion.preview(context: Context) =
    start(context, UnlockOverlayService.ACTION_PREVIEW)
