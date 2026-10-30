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
import com.example.todooverlay.data.Todo
import kotlin.math.ceil
import kotlin.math.min

/**
 * 解锁悬浮卡片。
 *
 * 窗口只在自己的卡片矩形内接收触摸（FLAG_NOT_TOUCH_MODAL），
 * 卡片以外的区域完全穿透给下层桌面/应用。
 *
 * 卡片**不会**因为用户点了外面而关闭 —— 只有两种情况会消失：
 * ① 倒计时自然走完；② 用户点了卡片右上角的关闭按钮。
 *
 * 注意：不要加 FLAG_BLUR_BEHIND。Android 12+ 上系统会为模糊在窗口下铺一层
 * Dim Layer，那层遮罩的不透明度超过 BlockUntrustedTouches 的阈值，
 * 结果是整块屏幕的触摸都被丢弃（日志：Untrusted touch due to occlusion by Dim Layer）。
 */
class OverlayManager(context: Context) {

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    private var countdown: ValueAnimator? = null
    private var closeRunnable: Runnable? = null

    /** 用户已经在卡片上勾选过，之后只等淡出，不再恢复自动倒计时 */
    private var completionTriggered = false

    /**
     * 展示全部待完成事项。[todos] 已按「每日优先 + 创建时间」排好序。
     * [onToggle] 在用户点选某一条时回调，第二个参数是勾选后的状态。
     */
    fun show(
        todos: List<Todo>,
        durationSeconds: Int,
        onToggle: (Todo, Boolean) -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(todos, durationSeconds, onToggle) }
            return
        }
        // 上一次还没淡出完就又被解锁了：先干净移除，避免叠加
        removeImmediately()
        completionTriggered = false

        if (!Settings.canDrawOverlays(appContext)) return

        val view = LayoutInflater.from(appContext).inflate(R.layout.overlay_card, null)
        bindContent(view, todos, durationSeconds, onToggle)
        attachInteractionHandlers(view)

        view.alpha = 0f
        try {
            windowManager.addView(view, buildParams())
        } catch (t: Throwable) {
            // 权限被中途撤销 / BadToken / 窗口已失效，静默放弃本次展示
            return
        }

        rootView = view
        view.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        startCountdown(view, durationSeconds)
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

    // ---------------------------------------------------------------- 内容绑定

    private fun bindContent(
        view: View,
        todos: List<Todo>,
        durationSeconds: Int,
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

            // 每行自己记住勾选状态，点击整行都能切换（复选框本身不接收点击）
            var checked = false
            row.setOnClickListener {
                checked = !checked
                applyRowState(checkBox, label, checked)
                onToggle(todo, checked)
                onItemToggled(view)
            }
            container.addView(row)
        }

        // 列表高度封顶：条目少时卡片自适应，条目多时在卡片内部滚动，
        // 卡片整体尺寸保持和原来 5 条时一致
        val scroll = view.findViewById<MaxHeightScrollView>(R.id.scroll_items)
        scroll.maxHeight = min(dp(LIST_MAX_HEIGHT_DP), (screenHeight() * 0.5f).toInt())
        attachInteractionHandlers(scroll)

        view.findViewById<TextView>(R.id.tv_countdown).text =
            appContext.getString(R.string.overlay_countdown, durationSeconds)

        view.findViewById<View>(R.id.btn_close).setOnClickListener { fadeOut() }
    }

    /** 勾选后的即时视觉反馈：删除线 + 置灰，不等数据库回来 */
    private fun applyRowState(checkBox: CheckBox, label: TextView, checked: Boolean) {
        checkBox.isChecked = checked
        label.paintFlags = if (checked) {
            label.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            label.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        label.alpha = if (checked) 0.45f else 1f
    }

    // ---------------------------------------------------------------- 交互

    /**
     * 手指按住卡片（含列表区域）就暂停倒计时，抬手后重新给一段宽限时间。
     *
     * 注意这里**不处理** ACTION_OUTSIDE：点到卡片外面时卡片必须保持不动，
     * 那一下点击由系统正常传给下层应用。
     */
    private fun attachInteractionHandlers(target: View) {
        target.isClickable = true
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    if (!completionTriggered) countdown?.pause()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (!completionTriggered) restartCountdownWithGrace()
            }
            false
        }
    }

    private fun onItemToggled(view: View) {
        if (!completionTriggered) {
            completionTriggered = true
            cancelCountdown()
            view.findViewById<TextView>(R.id.tv_countdown).text =
                appContext.getString(R.string.overlay_completed)
        }
        // 每次勾选都重新计时，连续勾选时不会被中途关掉
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

    // ---------------------------------------------------------------- 倒计时

    /** 抬手后重新计时，避免用户刚滑完列表卡片就消失 */
    private fun restartCountdownWithGrace() {
        val view = rootView ?: return
        startCountdown(view, GRACE_SECONDS)
    }

    private fun startCountdown(view: View, durationSeconds: Int) {
        cancelCountdown()

        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val label = view.findViewById<TextView>(R.id.tv_countdown)

        countdown = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationSeconds * 1000L
            interpolator = LinearInterpolator()
            var lastShown = -1
            addUpdateListener { animator ->
                val elapsed = animator.animatedValue as Float
                progress.progress = ((1f - elapsed) * progress.max).toInt()
                val remaining = ceil((1f - elapsed) * durationSeconds).toInt().coerceAtLeast(0)
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

    // ---------------------------------------------------------------- 视图移除

    private fun fadeOut() {
        val view = rootView ?: return
        rootView = null
        cancelCountdown()
        cancelScheduledClose()
        view.animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .withEndAction { safeRemove(view) }
            .start()
    }

    private fun removeImmediately() {
        val view = rootView ?: return
        rootView = null
        cancelCountdown()
        cancelScheduledClose()
        view.animate().cancel()
        safeRemove(view)
    }

    private fun safeRemove(view: View) {
        try {
            windowManager.removeViewImmediate(view)
        } catch (t: Throwable) {
            // 视图已被系统回收（息屏、切换用户、进程重建）——忽略即可
        }
    }

    // ---------------------------------------------------------------- 窗口参数

    private fun buildParams(): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            screenWidth() - dp(SIDE_MARGIN_DP) * 2,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不加 FLAG_NOT_TOUCHABLE：卡片要能接收点击。
            // FLAG_NOT_TOUCH_MODAL 保证窗口矩形之外的触摸照常交给下层应用。
            // 不要加 FLAG_WATCH_OUTSIDE_TOUCH —— 那会让我们把「点了外面」当成关闭信号。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = dp(TOP_MARGIN_DP)
        return params
    }

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels
        }

    private fun screenHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.heightPixels
        }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()

    companion object {
        private const val FADE_IN_MS = 180L
        private const val FADE_OUT_MS = 260L
        private const val SIDE_MARGIN_DP = 12
        private const val TOP_MARGIN_DP = 52

        /** 列表区域的最大高度，约等于原来 5 条时的高度，卡片整体尺寸不变 */
        private const val LIST_MAX_HEIGHT_DP = 150

        /** 勾选后停留多久再淡出，留出看清反馈的时间 */
        private const val CLOSE_DELAY_MS = 1800L

        /** 手指离开卡片后重新计时的秒数 */
        private const val GRACE_SECONDS = 4
    }
}
