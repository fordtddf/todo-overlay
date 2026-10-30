package com.example.todooverlay.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.animation.LinearInterpolator
import android.widget.ScrollView
import kotlin.math.abs

/**
 * 卡片内部的匀速自动滚动。
 *
 * 只负责「滚」这一件事：顶部停顿 → 匀速下滚 → 底部停顿 →（循环模式下）平滑回滚。
 * 什么时候关闭卡片由 [OverlayManager] 的倒计时决定，这里不碰。
 *
 * 用 ValueAnimator + LinearInterpolator 驱动 scrollTo，速度恒定、无跳帧。
 */
class OverlayScroller(
    private val scrollView: ScrollView,
    private val speedPxPerSec: Int,
    private val loop: Boolean
) {

    private val handler = Handler(Looper.getMainLooper())
    private var animator: ValueAnimator? = null
    private var running = false

    /** 内容超出可视区域的距离；<= 0 表示根本不需要滚动 */
    val scrollableDistance: Int
        get() {
            val content = scrollView.getChildAt(0)?.height ?: 0
            return (content - scrollView.height).coerceAtLeast(0)
        }

    fun start() {
        if (running) return
        running = true
        startTopPause()
    }

    /** 手指按下：停掉动画和待执行的停顿，但保留 running，方便之后接着滚 */
    fun pause() {
        handler.removeCallbacksAndMessages(null)
        val current = animator ?: return
        animator = null
        // 先摘监听再 cancel，否则 cancel 会回调 onAnimationEnd 把状态机推错
        current.removeAllListeners()
        current.removeAllUpdateListeners()
        current.cancel()
    }

    /** 手指抬起后继续：按当前滚动位置决定从哪一阶段接上 */
    fun resume() {
        if (!running) return
        val max = scrollableDistance
        val y = scrollView.scrollY
        when {
            y <= 0 -> startTopPause()
            y >= max -> onReachedBottom()
            else -> scrollTo(max)
        }
    }

    fun stop() {
        running = false
        pause()
    }

    // ---------------------------------------------------------------- 阶段

    private fun startTopPause() {
        if (!running) return
        handler.postDelayed({ scrollTo(scrollableDistance) }, TOP_PAUSE_MS)
    }

    private fun scrollTo(target: Int) {
        if (!running) return
        val from = scrollView.scrollY
        if (from == target) {
            onReached(target)
            return
        }
        animate(from, target)
    }

    private fun animate(from: Int, to: Int) {
        animator?.let {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        val distance = abs(to - from).coerceAtLeast(1)
        val durationMs = (distance * 1000L / speedPxPerSec.coerceAtLeast(1))
            .coerceAtLeast(MIN_ANIM_MS)

        animator = ValueAnimator.ofInt(from, to).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { scrollView.scrollTo(0, it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (running) onReached(to)
                }
            })
            start()
        }
    }

    private fun onReached(target: Int) {
        if (!running) return
        if (target <= 0) {
            // 回到顶部，循环继续
            startTopPause()
        } else {
            onReachedBottom()
        }
    }

    private fun onReachedBottom() {
        if (!running) return
        if (!loop) {
            // 单遍模式：停在底部，等外部倒计时把卡片关掉
            return
        }
        handler.postDelayed({ scrollTo(0) }, BOTTOM_PAUSE_MS)
    }

    companion object {
        const val TOP_PAUSE_MS = 1500L
        const val BOTTOM_PAUSE_MS = 2000L

        /** 循环模式的最长保护时间，到点无论滚到哪都关闭 */
        const val LOOP_MAX_MS = 18_000L

        private const val MIN_ANIM_MS = 200L

        fun travelDurationMs(distancePx: Int, speedPxPerSec: Int): Long =
            distancePx.coerceAtLeast(0).toLong() * 1000L / speedPxPerSec.coerceAtLeast(1)

        /** 单遍模式总时长 = 顶部停顿 + 滚动耗时 + 底部停顿 */
        fun singlePassTotalMs(distancePx: Int, speedPxPerSec: Int): Long =
            TOP_PAUSE_MS + travelDurationMs(distancePx, speedPxPerSec) + BOTTOM_PAUSE_MS
    }
}
