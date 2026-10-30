package com.example.todooverlay.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.todooverlay.R
import com.example.todooverlay.TodoApp
import com.example.todooverlay.data.SettingsStore
import com.example.todooverlay.data.Today
import com.example.todooverlay.data.Todo
import com.example.todooverlay.data.TodoOrdering
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

            // 横屏（游戏、视频）静默
            if (settings.landscapeMute && isLandscape()) return@launch

            // 低电量：完全静默，连庆祝卡片也不弹
            val lowBattery = settings.lowBatteryMode && isBatteryLow()

            val dao = app.database.todoDao()
            val today = Today.key()
            val todayStart = Today.startMillis()

            // 与主界面共用同一个比较器，避免两处排序规则跑偏
            val pending = TodoOrdering.sorted(
                dao.pendingForOverlay(today), today, settings.sortMode
            )

            val done = dao.todayDone(today, todayStart)
            val total = dao.todayTotal(todayStart)

            // 全部完成：按设置弹祝贺卡片或静默
            if (pending.isEmpty()) {
                if (settings.celebrateEnabled && !lowBattery) {
                    showCelebration(settings, done, total)
                }
                return@launch
            }

            // 高频解锁冷却：上次弹出后短时间内不再打扰
            if (settings.cooldownEnabled) {
                val last = app.settings.lastShownAt()
                val cooldownMs = settings.cooldownMinutes * 60_000L
                if (System.currentTimeMillis() - last < cooldownMs) return@launch
            }

            overlayManager.show(
                todos = pending,
                config = OverlayConfig(
                    durationSeconds = settings.durationSeconds,
                    autoScroll = settings.autoScroll && !lowBattery,
                    scrollMode = settings.scrollMode,
                    loopExitMode = settings.loopExitMode,
                    loopMaxTimeSeconds = settings.loopMaxTimeSeconds,
                    loopMaxCount = settings.loopMaxCount,
                    speedDpPerSec = settings.scrollSpeedDpPerSec,
                    opacityPercent = settings.cardOpacityPercent,
                    widthPercent = settings.cardWidthPercent,
                    maxHeightDp = settings.cardMaxHeightDp,
                    flingToDismiss = settings.flingToDismiss,
                    quickAddEnabled = settings.quickAddEnabled,
                    showProgress = settings.showProgress,
                    groupOverdue = settings.groupOverdue,
                    todayStart = todayStart,
                    progressDone = done,
                    progressTotal = total,
                    // 低电量时强制静态短展示
                    forceStatic = lowBattery,
                    showExistsTime = settings.showExistsTime,
                    words = if (settings.vocabEnabled && !lowBattery) {
                        app.settings.pickWords()
                    } else {
                        emptyList()
                    }
                ),
                onToggle = { todo, isDone ->
                    // 卡片上的勾选立刻落库：每日任务记日期，单次任务改完成位
                    scope.launch {
                        if (todo.isDaily) {
                            dao.setLastCompletedDate(todo.id, if (isDone) today else null)
                        } else {
                            dao.setCompleted(todo.id, isDone)
                        }
                    }
                },
                onQuickAdd = { title ->
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val todo = Todo(title = title, createdAt = now, orderIndex = now)
                        val id = dao.insert(todo)
                        // 立刻插进卡片，不用等下次解锁
                        overlayManager.appendTodo(todo.copy(id = id))
                    }
                }
            )

            app.settings.setLastShownAt(System.currentTimeMillis())
            watchForIncomingCall()
        }
    }

    private fun showCelebration(settings: com.example.todooverlay.data.SettingsStore.Snapshot,
                                done: Int, total: Int) {
        overlayManager.showCelebration(
            done = done,
            total = total,
            durationSeconds = CELEBRATION_SECONDS,
            widthPercent = settings.cardWidthPercent,
            opacityPercent = settings.cardOpacityPercent
        )
    }

    /** 横屏判断：用 Configuration 而不是 Display.getRotation，不依赖传感器 */
    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** 低电量判断。BatteryManager 不需要任何权限 */
    private fun isBatteryLow(): Boolean {
        val bm = getSystemService(BatteryManager::class.java) ?: return false
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level in 0..SettingsStore.LOW_BATTERY_THRESHOLD
    }

    /**
     * 卡片显示期间如果来了电话，立刻收回，别挡住来电界面。
     * 只在卡片存活的这几秒内轮询，结束后自动停。
     */
    private fun watchForIncomingCall() {
        callWatchJob?.cancel()
        callWatchJob = scope.launch {
            // 卡片最长可能显示 18 秒（循环模式），多留一点余量
            repeat(CALL_WATCH_TICKS) {
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

        /** 来电轮询次数：500ms 一次，共 65 秒，覆盖循环模式的最长展示时间（60 秒） */
        private const val CALL_WATCH_TICKS = 130

        /** 祝贺气泡停留秒数 */
        private const val CELEBRATION_SECONDS = 2

        const val ACTION_PREVIEW = "com.example.todooverlay.action.PREVIEW"
    }
}
