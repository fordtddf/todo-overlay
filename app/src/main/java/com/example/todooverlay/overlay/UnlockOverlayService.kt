package com.example.todooverlay.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.todooverlay.R
import com.example.todooverlay.TodoApp
import com.example.todooverlay.data.Today
import com.example.todooverlay.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 常驻前台服务，唯一职责是让 ACTION_USER_PRESENT 的动态注册长期有效。
 *
 * Android 8.0+ 起 ACTION_USER_PRESENT 不能静态注册，而普通后台进程的
 * 动态 Receiver 会在进程被回收时一起消失；国内定制系统（HyperOS / OriginOS /
 * ColorOS）对后台更激进，所以必须用前台服务撑住进程。
 */
class UnlockOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlayManager: OverlayManager
    private lateinit var receiver: UnlockReceiver
    private var registered = false

    /** 卡片显示期间的来电监听任务，重复触发时先取消旧的 */
    private var callWatchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        overlayManager = OverlayManager(applicationContext)
        registerUnlockReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 预览走和真实解锁完全相同的链路，方便在设置页验证权限是否配好
        if (intent?.action == ACTION_PREVIEW) {
            onScreenUnlocked()
        }
        // 被系统杀掉后尽量重新创建（真正保活仍依赖厂商白名单）
        return START_STICKY
    }

    override fun onDestroy() {
        if (registered) {
            try {
                unregisterReceiver(receiver)
            } catch (t: Throwable) {
                Log.w(TAG, "unregisterReceiver failed", t)
            }
            registered = false
        }
        overlayManager.destroy()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 解锁处理

    private fun registerUnlockReceiver() {
        receiver = UnlockReceiver { onScreenUnlocked() }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 监听系统广播必须用 EXPORTED。ACTION_USER_PRESENT 是受保护广播，
            // 只有系统能发出、第三方应用伪造不了，不存在被恶意触发的风险；
            // 反过来用 NOT_EXPORTED 会有收不到广播的风险。
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        registered = true
    }

    private fun onScreenUnlocked() {
        val app = application as TodoApp
        scope.launch {
            // 一次读全设置，避免解锁瞬间连读两次 DataStore
            val settings = app.settings.snapshot()
            if (!settings.enabled) return@launch

            // 正在通话/来电时不打扰。AudioManager.getMode() 不需要任何权限，
            // 比申请 READ_PHONE_STATE 轻得多
            if (isInCall()) return@launch

            val dao = app.database.todoDao()
            val today = Today.key()
            val pending = dao.pendingForOverlay(today, OverlayManager.MAX_ITEMS)

            // 没有未完成事项就不打扰
            if (pending.isEmpty()) return@launch

            val total = dao.pendingCount(today)
            overlayManager.show(pending, total, settings.durationSeconds) { todo, done ->
                // 卡片上的勾选立刻落库：每日任务记日期，单次任务改完成位
                scope.launch {
                    if (todo.isDaily) {
                        dao.setLastCompletedDate(todo.id, if (done) today else null)
                    } else {
                        dao.setCompleted(todo.id, done)
                    }
                }
            }
            watchForIncomingCall(settings.durationSeconds)
        }
    }

    /**
     * 卡片显示期间如果来了电话，立刻收回，别挡住来电界面。
     * 只在卡片存活的这几秒内轮询，结束后自动停。
     */
    private fun watchForIncomingCall(durationSeconds: Int) {
        callWatchJob?.cancel()
        callWatchJob = scope.launch {
            repeat(durationSeconds * 2) {
                delay(500)
                if (isInCall()) {
                    overlayManager.dismiss()
                    return@launch
                }
            }
        }
    }

    /** 通话中、来电中都不弹卡片 */
    private fun isInCall(): Boolean {
        val audio = getSystemService(AudioManager::class.java) ?: return false
        return audio.mode == AudioManager.MODE_IN_CALL ||
            audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audio.mode == AudioManager.MODE_RINGTONE
    }

    // ------------------------------------------------------------ 前台通知

    private fun startAsForeground() {
        createChannel()
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notify_title))
            .setContentText(getString(R.string.notify_text))
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()

        // Android 14+ 对 FGS 类型、权限的校验会在 startForeground 里抛异常；
        // HyperOS 上还可能因为通知权限被拒直接拒绝。不接住的话整个进程崩溃，
        // 连带解锁监听一起没了 —— 宁可降级成普通服务，也不能崩。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed, service keeps running without it", t)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_unlock_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.channel_unlock_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "UnlockOverlayService"
        private const val CHANNEL_ID = "unlock_overlay"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PREVIEW = "com.example.todooverlay.action.PREVIEW"
    }
}
