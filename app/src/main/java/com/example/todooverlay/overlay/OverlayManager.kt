package com.example.todooverlay.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.example.todooverlay.R
import kotlin.math.ceil

/**
 * 解锁悬浮卡片。
 *
 * 生命周期独立于 Activity，因此只用 ApplicationContext 拿 WindowManager，
 * 所有 add/remove 都走 try-catch —— 快速反复解锁时最容易在 removeView 上崩。
 * 视图不接收触摸（FLAG_NOT_TOUCHABLE），纯展示，绝不挡住首页图标。
 */
class OverlayManager(context: Context) {

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    private var countdown: ValueAnimator? = null

    /**
     * 展示未完成事项。
     * [titles] 已截断到 [MAX_ITEMS] 条；[totalCount] 是当天未完成的总数，用于提示还有多少没显示。
     */
    fun show(titles: List<String>, totalCount: Int, durationSeconds: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(titles, totalCount, durationSeconds) }
            return
        }
        // 上一次还没淡出完就又被解锁了：先干净移除，避免叠加
        removeImmediately()

        if (!Settings.canDrawOverlays(appContext)) return

        val view = LayoutInflater.from(appContext).inflate(R.layout.overlay_card, null)
        bindContent(view, titles, totalCount, durationSeconds)

        val params = buildParams()
        view.alpha = 0f

        try {
            windowManager.addView(view, params)
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

    // ---------------------------------------------------------------- 内部实现

    private fun bindContent(
        view: View,
        titles: List<String>,
        totalCount: Int,
        durationSeconds: Int
    ) {
        val shown = titles.take(MAX_ITEMS)
        val container = view.findViewById<LinearLayout>(R.id.container_items)
        val inflater = LayoutInflater.from(view.context)

        shown.forEach { title ->
            val row = inflater.inflate(R.layout.overlay_item, container, false)
            row.findViewById<TextView>(R.id.tv_item).text = title
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

    private fun startCountdown(view: View, durationSeconds: Int) {
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val label = view.findViewById<TextView>(R.id.tv_countdown)

        countdown = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationSeconds * 1000L
            interpolator = LinearInterpolator()
            var lastShown = -1
            addUpdateListener { animator ->
                // elapsed 0 → 1，剩余时间与进度条都按 (1 - elapsed) 递减
                val elapsed = animator.animatedValue as Float
                progress.progress = ((1f - elapsed) * progress.max).toInt()
                // 只在整秒变化时改文本，避免每帧都走一次资源查找
                val remaining = ceil((1f - elapsed) * durationSeconds).toInt().coerceAtLeast(0)
                if (remaining != lastShown) {
                    lastShown = remaining
                    label.text = appContext.getString(R.string.overlay_countdown, remaining)
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = fadeOut()
            })
            start()
        }
    }

    private fun fadeOut() {
        val view = rootView ?: return
        rootView = null
        cancelCountdown()
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
        view.animate().cancel()
        safeRemove(view)
    }

    private fun cancelCountdown() {
        val animator = countdown ?: return
        countdown = null
        // 先摘监听再 cancel：否则 cancel 会回调 onAnimationEnd → fadeOut，造成重入
        animator.removeAllListeners()
        animator.removeAllUpdateListeners()
        animator.cancel()
    }

    /**
     * 诊断用：把窗口的实际不透明度打进 logcat。
     *
     * Android 12+ 的 BlockUntrustedTouches 依据「窗口不透明度」判定，
     * 它算的是 window alpha × 根视图 alpha × 背景 drawable alpha。
     * 这三者只要有一个没压住，触摸就会被丢弃，而且从界面上完全看不出来。
     * 排查时执行：
     *   adb logcat -s OverlayManager
     */
    private fun logObscuringOpacity(view: View, params: WindowManager.LayoutParams) {
        view.post {
            val windowAlpha = params.alpha
            val viewAlpha = view.alpha
            // Drawable.getAlpha() 返回 0~255 的 Int，需换算成 0~1
            val bgAlpha = (view.background?.alpha ?: 255) / 255f
            val combined = windowAlpha * viewAlpha * bgAlpha
            Log.i(
                TAG,
                "obscuring opacity: window=$windowAlpha view=$viewAlpha bg=$bgAlpha " +
                    "combined=$combined threshold=0.8 safe=${combined <= 0.8f}"
            )
        }
    }

    private fun safeRemove(view: View) {        try {
            windowManager.removeViewImmediate(view)
        } catch (t: Throwable) {
            // 视图已被系统回收（息屏、切换用户、进程重建）——忽略即可
        }
    }

    private fun buildParams(): WindowManager.LayoutParams {
        // 卡片是「可点击」的，不再依赖触摸穿透：
        // FLAG_NOT_TOUCHABLE + 半透明 在 Android 12+ 上会触发 BlockUntrustedTouches，
        // 表现为整块区域点不动、滑不动，且降低不透明度并不能稳定绕过。
        // 改为可点击窗口后，卡片区域内的触摸由卡片消费（点一下即关闭），
        // 区域外因为有 FLAG_NOT_TOUCH_MODAL，触摸照常交给下层应用。
        val params = WindowManager.LayoutParams(
            screenWidth() - dp(SIDE_MARGIN_DP) * 2,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = dp(TOP_MARGIN_DP)

        // 关键：不要用 FLAG_BLUR_BEHIND / setBlurBehindRadius。
        // 实测在 HyperOS 上，背景模糊会让系统在该窗口下铺一层 Dim Layer，
        // 这层遮罩的不透明度超过 BlockUntrustedTouches 的阈值，
        // 导致卡片显示期间所有触摸被 InputDispatcher 丢弃
        // （日志：Untrusted touch due to occlusion by Dim Layer）。
        // 半透明深色背景本身已经够好看，模糊效果不值得这个代价。
        params.alpha = MAX_WINDOW_ALPHA
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

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()

    companion object {
        /** 悬浮卡片最多展示几条，多了就变成列表，违背“瞥一眼就走” */
        const val MAX_ITEMS = 5

        private const val FADE_IN_MS = 180L
        private const val FADE_OUT_MS = 260L
        private const val SIDE_MARGIN_DP = 12
        private const val TOP_MARGIN_DP = 52

        /** 窗口不透明度上限，必须低于 0.8，见 buildParams 注释 */
        private const val MAX_WINDOW_ALPHA = 0.78f

        private const val TAG = "OverlayManager"
    }
}
