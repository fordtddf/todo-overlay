package com.example.todooverlay.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Paint
import android.graphics.PixelFormat
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
import androidx.core.view.isVisible
import com.example.todooverlay.R
import com.example.todooverlay.data.Todo
import kotlin.math.ceil

/**
 * 解锁悬浮卡片。
 *
 * 窗口只在自己的卡片矩形内接收触摸（FLAG_NOT_TOUCH_MODAL），
 * 卡片以外的区域完全穿透给下层桌面/应用，绝不挡图标。
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
     * 展示待完成事项。[todos] 已按「每日优先 + 创建时间」排好序并截断到 [MAX_ITEMS]。
     * [onToggle] 在用户点选某一条时回调，第二个参数是勾选后的状态。
     */
    fun show(
        todos: List<Todo>,
        totalCount: Int,
        durationSeconds: Int,
        onToggle: (Todo, Boolean) -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(todos, totalCount, durationSeconds, onToggle) }
            return
        }
        // 上一次还没淡出完就又被解锁了：先干净移除，避免叠加
        removeImmediately()
        completionTriggered = false

        if (!Settings.canDrawOverlays(appContext)) return

        val view = LayoutInflater.from(appContext).inflate(R.layout.overlay_card, null)
        bindContent(view, todos, totalCount, durationSeconds, onToggle)
        attachTouchHandlers(view)

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
        totalCount: Int,
        durationSeconds: Int,
        onToggle: (Todo, Boolean) -> Unit
    ) {
        val shown = todos.take(MAX_ITEMS)
        val container = view.findViewById<LinearLayout>(R.id.container_items)
        val inflater = LayoutInflater.from(view.context)

        shown.forEach { todo ->
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

        val subtitle = view.findViewById<TextView>(R.id.tv_subtitle)
        val rest = totalCount - shown.size
        if (rest > 0) {
            subtitle.isVisible = true
            subtitle.text = appContext.getString(R.string.overlay_more, rest)
        }

        view.findViewById<TextView>(R.id.tv_countdown).text =
            appContext.getString(R.string.overlay_countdown, durationSeconds)
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

    private fun attachTouchHandlers(view: View) {
        // 卡片内部的空白区域也要消费掉触摸，避免穿透到下层应用
        view.isClickable = true
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                // 手指按住卡片：暂停倒计时，让用户从容点选
                MotionEvent.ACTION_DOWN -> if (!completionTriggered) countdown?.pause()
                // 抬手：恢复倒计时（已经勾选过的话就让它走延时关闭）
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (!completionTriggered) countdown?.resume()
                // 点到卡片外面：直接收起，这一下仍会正常传给下层应用
                MotionEvent.ACTION_OUTSIDE -> fadeOut()
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

    private fun startCountdown(view: View, durationSeconds: Int) {
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
            // FLAG_NOT_TOUCH_MODAL 保证窗口矩形之外的触摸照常交给下层应用，
            // FLAG_WATCH_OUTSIDE_TOUCH 让我们能感知「点到外面了」并顺手收起。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = dp(TOP_MARGIN_DP)
        return params
    }

    private fun screenWidth(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels
        }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()

    companion object {
        /** 卡片最多展示几条，多了就变成列表，违背“瞥一眼就走” */
        const val MAX_ITEMS = 5

        private const val FADE_IN_MS = 180L
        private const val FADE_OUT_MS = 260L
        private const val SIDE_MARGIN_DP = 12
        private const val TOP_MARGIN_DP = 52

        /** 勾选后停留多久再淡出，留出看清反馈的时间 */
        private const val CLOSE_DELAY_MS = 1800L
    }
}
