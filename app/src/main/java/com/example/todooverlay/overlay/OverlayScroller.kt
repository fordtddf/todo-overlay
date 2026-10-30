package com.example.todooverlay.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import kotlin.math.abs

/**
 * 卡片内部的匀速自动滚动。
 *
 * 性能要点（高刷屏不卡顿的关键）：
 * - 可滚动距离在布局完成后测一次传进来，滚动过程中不再调 getHeight()；
 * - 每帧只做 `scrollTo()` + 唤醒滚动条，不做 requestLayout / 测量；
 * - `ValueAnimator.pause()/resume()` 保留动画内部进度，手指松开后从同一像素接着走；
 * - 全部跑在主线程 Looper 上，不跨线程切协程，避免微卡顿。
 *
 * 什么时候关闭卡片不归这里管，只通过 [onLoopsFinished] 通知外部。
 */
class OverlayScroller(
    private val scrollView: MaxHeightScrollView,
    /** 布局完成后测得的内容高度减去可视高度的差值 */
    private val scrollableDistance: Int,
    private val speedPxPerSec: Int,
    private val loop: Boolean,
    /** 目标轮数；<= 0 表示不限轮数，由外部计时器负责关闭 */
    private val targetLoops: Int,
    private val onRoundComplete: (Int) -> Unit,
    private val onLoopsFinished: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var animator: ValueAnimator? = null
    private var running = false
    private var completedLoops = 0

    fun start() {
        if (running) return
        running = true
        startTopPause()
    }

    /** 手指按下：停掉暂停阶段的计时器，冻结动画（保留进度，不销毁） */
    fun pause() {
        handler.removeCallbacksAndMessages(null)
        animator?.pause()
    }

    /** 手指抬起：优先从被冻结的那一帧继续，做到像素级接续 */
    fun resume() {
        if (!running) return
        val current = animator
        if (current != null && current.isPaused) {
            current.resume()
            return
        }
        // 被暂停时正处在顶部/底部停顿阶段，没有动画可恢复：按位置重新接上
        val y = scrollView.scrollY
        when {
            y <= 0 -> startTopPause()
            y >= scrollableDistance -> onReachedBottom()
            else -> scrollToBottom()
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        animator?.let {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        animator = null
    }

    // ---------------------------------------------------------------- 阶段

    private fun startTopPause() {
        if (!running) return
        handler.postDelayed({ scrollToBottom() }, TOP_PAUSE_MS)
    }

    private fun scrollToBottom() {
        if (!running) return
        val from = scrollView.scrollY
        if (scrollableDistance <= from) {
            onReachedBottom()
            return
        }
        val travel = travelDuration(from, scrollableDistance)
        animateTo(
            target = scrollableDistance,
            durationMs = travel,
            interpolator = softLandingInterpolator(scrollableDistance - from)
        ) { onReachedBottom() }
    }

    private fun scrollToTop() {
        if (!running) return
        if (scrollView.scrollY <= 0) {
            startTopPause()
            return
        }
        // 回顶不能瞬间跳过去，用 400ms 的缓入缓出平滑滚回
        animateTo(
            target = 0,
            durationMs = RETURN_MS,
            interpolator = AccelerateDecelerateInterpolator()
        ) { startTopPause() }
    }

    private fun onReachedBottom() {
        if (!running) return
        if (!loop) return // 单遍模式：停在底部，由外部倒计时关闭

        handler.postDelayed({
            if (!running) return@postDelayed
            completedLoops++
            onRoundComplete(completedLoops)

            if (targetLoops > 0 && completedLoops >= targetLoops) {
                // 达到目标轮数：停在底部，不要再回滚
                running = false
                onLoopsFinished()
            } else {
                scrollToTop()
            }
        }, BOTTOM_PAUSE_MS)
    }

    // ---------------------------------------------------------------- 动画

    private fun animateTo(
        target: Int,
        durationMs: Long,
        interpolator: Interpolator,
        onEnd: () -> Unit
    ) {
        animator?.let {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }

        val from = scrollView.scrollY.toFloat()
        animator = ValueAnimator.ofFloat(from, target.toFloat()).apply {
            duration = durationMs
            this.interpolator = interpolator
            addUpdateListener { anim ->
                // 每帧只做位移和滚动条唤醒。任何测量、布局、inflate 都会导致掉帧。
                scrollView.scrollTo(0, (anim.animatedValue as Float).toInt())
                scrollView.keepScrollBarAwake()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    if (running) onEnd()
                }
            })
            start()
        }
    }

    private fun travelDuration(from: Int, to: Int): Long =
        (abs(to - from) * 1000L / speedPxPerSec.coerceAtLeast(1)).coerceAtLeast(MIN_TRAVEL_MS)

    /**
     * 前段严格匀速，最后 [SOFT_LANDING_PX] 像素做二次减速，
     * 避免滚到底部时像撞墙一样急停。
     */
    private fun softLandingInterpolator(travelPx: Int): Interpolator {
        if (travelPx <= SOFT_LANDING_PX) return LinearInterpolator()
        val easeFraction = (SOFT_LANDING_PX.toFloat() / travelPx).coerceIn(0.02f, 0.25f)
        return SoftLandingInterpolator(easeFraction)
    }

    private class SoftLandingInterpolator(private val easeFraction: Float) : Interpolator {
        override fun getInterpolation(t: Float): Float {
            val linearEnd = 1f - easeFraction
            if (t <= linearEnd) return t
            val p = (t - linearEnd) / easeFraction
            val eased = 1f - (1f - p) * (1f - p)
            return linearEnd + eased * easeFraction
        }
    }

    companion object {
        const val TOP_PAUSE_MS = 1500L
        const val BOTTOM_PAUSE_MS = 2000L

        /** 回滚到顶部的固定时长 */
        const val RETURN_MS = 400L

        private const val MIN_TRAVEL_MS = 200L
        private const val SOFT_LANDING_PX = 25

        fun travelDurationMs(distancePx: Int, speedPxPerSec: Int): Long =
            distancePx.coerceAtLeast(0).toLong() * 1000L / speedPxPerSec.coerceAtLeast(1)

        /** 单遍模式总时长 = 顶部停顿 + 滚动耗时 + 底部停顿 */
        fun singlePassTotalMs(distancePx: Int, speedPxPerSec: Int): Long =
            TOP_PAUSE_MS + travelDurationMs(distancePx, speedPxPerSec) + BOTTOM_PAUSE_MS
    }
}
