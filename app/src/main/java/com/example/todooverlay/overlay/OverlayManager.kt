package com.example.todooverlay.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.todooverlay.R
import com.example.todooverlay.data.LoopExitMode
import com.example.todooverlay.data.ScrollMode
import com.example.todooverlay.data.Todo
import kotlin.math.ceil
import kotlin.math.min

/** 一次展示所需的全部参数，由 Service 从 SettingsStore 映射过来 */
data class OverlayConfig(
    val durationSeconds: Int,
    val autoScroll: Boolean,
    val scrollMode: ScrollMode,
    val loopExitMode: LoopExitMode,
    val loopMaxTimeSeconds: Int,
    val loopMaxCount: Int,
    val speedDpPerSec: Int,
    val opacityPercent: Int,
    val widthPercent: Int,
    val maxHeightDp: Int
)

/**
 * 解锁悬浮卡片。
 *
 * 窗口只在自己的卡片矩形内接收触摸（FLAG_NOT_TOUCH_MODAL），
 * 卡片以外的区域完全穿透给下层桌面/应用。
 *
 * 卡片只会因为两件事消失：倒计时走完，或用户点了右上角关闭按钮。
 * 点卡片外面**不会**关闭它。
 *
 * 注意：不要加 FLAG_BLUR_BEHIND。Android 12+ 上系统会为模糊在窗口下铺一层
 * Dim Layer，那层遮罩的不透明度超过 BlockUntrustedTouches 的阈值，
 * 结果是整块屏幕的触摸都被丢弃。
 */
class OverlayManager(context: Context) {

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    private var countdown: ValueAnimator? = null
    private var scroller: OverlayScroller? = null
    private var currentConfig: OverlayConfig? = null

    private var closeRunnable: Runnable? = null
    private var resumeRunnable: Runnable? = null

    /** 用户已经在卡片上勾选过，之后只等淡出，不再恢复滚动和倒计时 */
    private var completionTriggered = false

    fun show(
        todos: List<Todo>,
        config: OverlayConfig,
        onToggle: (Todo, Boolean) -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(todos, config, onToggle) }
            return
        }
        removeImmediately()
        completionTriggered = false

        if (!Settings.canDrawOverlays(appContext)) return

        val view = LayoutInflater.from(appContext).inflate(R.layout.overlay_card, null)
        bindContent(view, todos, config, onToggle)
        applySize(view, config)
        applyOpacity(view, config)
        attachInteractionHandlers(view)

        view.alpha = 0f
        try {
            windowManager.addView(view, buildParams(config))
        } catch (t: Throwable) {
            return
        }

        rootView = view
        currentConfig = config
        view.animate().alpha(1f).setDuration(FADE_IN_MS).start()

        // 必须等布局完成才知道内容到底有多高、能滚多远
        view.post {
            if (rootView === view) startTimers(view, config)
        }
    }

    /** 立即淡出。供服务在来电等情况下提前收回卡片。 */
    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismiss() }
            return
        }
        fadeOut()
    }

    fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { destroy() }
            return
        }
        removeImmediately()
    }

    // ---------------------------------------------------------------- 内容与尺寸

    private fun bindContent(
        view: View,
        todos: List<Todo>,
        config: OverlayConfig,
        onToggle: (Todo, Boolean) -> Unit
    ) {
        val container = view.findViewById<LinearLayout>(R.id.container_items)
        val inflater = LayoutInflater.from(view.context)

        todos.forEach { todo ->
            val row = inflater.inflate(R.layout.overlay_item, container, false)
            val checkBox = row.findViewById<CheckBox>(R.id.cb_item)
            val label = row.findViewById<TextView>(R.id.tv_item)

            label.text = if (todo.isDaily) {
                appContext.getString(R.string.overlay_daily_prefix, todo.title)
            } else {
                todo.title
            }

            var checked = false
            row.setOnClickListener {
                checked = !checked
                applyRowState(checkBox, label, checked)
                onToggle(todo, checked)
                onItemToggled(view)
            }
            container.addView(row)
        }

        view.findViewById<TextView>(R.id.tv_countdown).text =
            appContext.getString(R.string.overlay_countdown, config.durationSeconds)

        view.findViewById<View>(R.id.btn_close).setOnClickListener { fadeOut() }
    }

    /** 卡片宽度按百分比、列表高度按用户设定，但都不超过半屏 */
    private fun applySize(view: View, config: OverlayConfig) {
        val scroll = view.findViewById<MaxHeightScrollView>(R.id.scroll_items)
        scroll.maxHeight = min(dp(config.maxHeightDp), (screenHeight() * 0.5f).toInt())

        // 开启硬件图层，交给 GPU 合成
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        scroll.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    /**
     * 只调背景 drawable 的 alpha，不动根布局的 view alpha。
     *
     * 用 view.alpha 会把子视图（文字、复选框）一起变淡，50% 时几乎看不清；
     * 改背景 drawable 能保证文字始终是高对比度的纯白。
     */
    private fun applyOpacity(view: View, config: OverlayConfig) {
        val percent = config.opacityPercent.coerceIn(0, 100)
        // mutate() 避免改到共享的 ConstantState，影响后续其它卡片实例
        view.background?.mutate()?.alpha = percent * 255 / 100
    }

    private fun applyRowState(checkBox: CheckBox, label: TextView, checked: Boolean) {
        checkBox.isChecked = checked
        label.paintFlags = if (checked) {
            label.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            label.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        label.alpha = if (checked) 0.45f else 1f
    }

    // ---------------------------------------------------------------- 计时

    /**
     * 布局完成后才能拿到真实的可滚动距离，所以计时和自动滚动都放在这里启动。
     *
     * 单遍模式的总时长按「顶部停顿 + 滚动耗时 + 底部停顿」算，
     * 保证列表一定滚得完；不需要滚动时退回用户设置的固定时长。
     */
    private fun startTimers(view: View, config: OverlayConfig) {
        val scroll = view.findViewById<MaxHeightScrollView>(R.id.scroll_items)
        // 距离只在这里测一次，之后整个滚动过程都复用这个值
        val distance = ((scroll.getChildAt(0)?.height ?: 0) - scroll.height).coerceAtLeast(0)
        val speedPx = dp(config.speedDpPerSec)
        val needScroll = config.autoScroll && distance > 0

        val looping = config.scrollMode == ScrollMode.LOOP
        val byCount = looping && config.loopExitMode == LoopExitMode.COUNT

        if (needScroll) {
            scroller = OverlayScroller(
                scrollView = scroll,
                scrollableDistance = distance,
                speedPxPerSec = speedPx,
                loop = looping,
                // 按轮数退出时传目标轮数；按时间退出传 0 表示不限轮
                targetLoops = if (byCount) config.loopMaxCount else 0,
                onRoundComplete = { completed ->
                    updateRoundLabel(view, completed, config.loopMaxCount)
                },
                onLoopsFinished = { fadeOut() }
            ).also { it.start() }
        }

        when {
            !needScroll -> startCountdown(view, config.durationSeconds * 1000L)

            config.scrollMode == ScrollMode.SINGLE ->
                startCountdown(view, OverlayScroller.singlePassTotalMs(distance, speedPx))

            config.loopExitMode == LoopExitMode.TIME ->
                startCountdown(view, config.loopMaxTimeSeconds * 1000L)

            else -> {
                // 按轮数退出：没有倒计时，顶部文本改成轮数进度
                view.findViewById<TextView>(R.id.tv_countdown).text =
                    appContext.getString(R.string.overlay_round_progress, 1, config.loopMaxCount)
                view.findViewById<ProgressBar>(R.id.progress).progress = 0
            }
        }
    }

    /** 按轮数退出时的顶部进度：已完成的轮数 / 目标轮数 */
    private fun updateRoundLabel(view: View, completedLoops: Int, target: Int) {
        val total = target.coerceAtLeast(1)
        val display = (completedLoops + 1).coerceAtMost(total)
        view.findViewById<TextView>(R.id.tv_countdown).text =
            appContext.getString(R.string.overlay_round_progress, display, total)
        view.findViewById<ProgressBar>(R.id.progress).progress =
            (completedLoops * 1000 / total).coerceIn(0, 1000)
    }

    private fun startCountdown(view: View, totalMs: Long) {
        cancelCountdown()

        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val label = view.findViewById<TextView>(R.id.tv_countdown)
        val duration = totalMs.coerceAtLeast(1_000L)

        countdown = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            var lastShown = -1
            addUpdateListener { animator ->
                val elapsed = animator.animatedValue as Float
                progress.progress = ((1f - elapsed) * progress.max).toInt()
                val remaining = ceil((1f - elapsed) * duration / 1000.0).toInt().coerceAtLeast(0)
                if (remaining != lastShown) {
                    lastShown = remaining
                    label.text = appContext.getString(R.string.overlay_countdown, remaining)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!completionTriggered) fadeOut()
                }
            })
            start()
        }
    }

    private fun cancelCountdown() {
        val animator = countdown ?: return
        countdown = null
        // 先摘监听再 cancel：否则 cancel 会回调 onAnimationEnd → fadeOut，造成重入
        animator.removeAllListeners()
        animator.removeAllUpdateListeners()
        animator.cancel()
    }

    // ---------------------------------------------------------------- 手势

    /**
     * 手指按住（卡片任意位置，含列表内滑动）就暂停自动滚动和倒计时，
     * 此时列表可以自由手动拖拽；抬手 2 秒后恢复。
     *
     * 不处理 ACTION_OUTSIDE —— 点卡片外面必须让卡片保持不动。
     */
    private fun attachInteractionHandlers(view: View) {
        // 卡片空白处也要消费掉触摸，避免穿透到下层应用
        view.isClickable = true
        (view as? TouchObservingLayout)?.touchObserver = ::onTouchObserved
    }

    private fun onTouchObserved(event: MotionEvent) {
        if (completionTriggered) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelResume()
                scroller?.pause()
                countdown?.pause()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleResume()
        }
    }

    private fun scheduleResume() {
        cancelResume()
        val runnable = Runnable { resumeInteraction() }
        resumeRunnable = runnable
        mainHandler.postDelayed(runnable, RESUME_DELAY_MS)
    }

    private fun cancelResume() {
        resumeRunnable?.let { mainHandler.removeCallbacks(it) }
        resumeRunnable = null
    }

    private fun resumeInteraction() {
        val view = rootView ?: return
        val config = currentConfig ?: return
        scroller?.resume()

        val needRecalc = scroller != null && config.scrollMode == ScrollMode.SINGLE
        if (!needRecalc) {
            // 循环模式或不需要滚动：接着走原来那根倒计时就行
            countdown?.resume()
            return
        }

        // 单遍模式按剩余距离重算，避免用户滑到一半松手后卡片立刻关闭
        val scroll = view.findViewById<MaxHeightScrollView>(R.id.scroll_items)
        val distance = ((scroll.getChildAt(0)?.height ?: 0) - scroll.height).coerceAtLeast(0)
        val remainingPx = (distance - scroll.scrollY).coerceAtLeast(0)
        val remainingMs = OverlayScroller.travelDurationMs(remainingPx, dp(config.speedDpPerSec)) +
            OverlayScroller.BOTTOM_PAUSE_MS
        startCountdown(view, remainingMs)
    }

    // ---------------------------------------------------------------- 勾选

    private fun onItemToggled(view: View) {
        if (!completionTriggered) {
            completionTriggered = true
            scroller?.stop()
            cancelCountdown()
            cancelResume()
            view.findViewById<TextView>(R.id.tv_countdown).text =
                appContext.getString(R.string.overlay_completed)
        }
        // 连续勾选时重新计时，不会被中途关掉
        scheduleClose()
    }

    private fun scheduleClose() {
        cancelScheduledClose()
        val runnable = Runnable { fadeOut() }
        closeRunnable = runnable
        mainHandler.postDelayed(runnable, CLOSE_DELAY_MS)
    }

    private fun cancelScheduledClose() {
        closeRunnable?.let { mainHandler.removeCallbacks(it) }
        closeRunnable = null
    }

    // ---------------------------------------------------------------- 视图移除

    private fun fadeOut() {
        val view = rootView ?: return
        rootView = null
        cleanupTimers()
        view.animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .withEndAction { safeRemove(view) }
            .start()
    }

    private fun removeImmediately() {
        val view = rootView ?: return
        rootView = null
        cleanupTimers()
        view.animate().cancel()
        safeRemove(view)
    }

    private fun cleanupTimers() {
        cancelCountdown()
        cancelScheduledClose()
        cancelResume()
        scroller?.stop()
        scroller = null
    }

    private fun safeRemove(view: View) {
        try {
            windowManager.removeViewImmediate(view)
        } catch (t: Throwable) {
            // 视图已被系统回收（息屏、切换用户、进程重建）——忽略即可
        }
    }

    // ---------------------------------------------------------------- 窗口参数

    private fun buildParams(config: OverlayConfig): WindowManager.LayoutParams {
        val width = (screenWidth() * config.widthPercent / 100f).toInt()
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不加 FLAG_NOT_TOUCHABLE：卡片要能接收点击。
            // FLAG_NOT_TOUCH_MODAL 保证窗口矩形之外的触摸照常交给下层应用。
            // 不要加 FLAG_WATCH_OUTSIDE_TOUCH —— 那会把「点了外面」当成关闭信号。
            // HARDWARE_ACCELERATED 让自动滚动走 GPU 合成，高刷屏上更跟手。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = dp(TOP_MARGIN_DP)
        return params
    }

    private fun screenWidth(): Int = windowMetrics().width()

    private fun screenHeight(): Int = windowMetrics().height()

    private fun windowMetrics(): android.graphics.Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()

    companion object {
        private const val FADE_IN_MS = 180L
        private const val FADE_OUT_MS = 260L
        private const val TOP_MARGIN_DP = 52

        /** 勾选后停留多久再淡出 */
        private const val CLOSE_DELAY_MS = 1500L

        /** 手指抬起后多久恢复自动滚动 */
        private const val RESUME_DELAY_MS = 2000L
    }
}
