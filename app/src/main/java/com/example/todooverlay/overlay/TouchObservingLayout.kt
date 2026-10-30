package com.example.todooverlay.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * 只做一件事：在 dispatchTouchEvent 里把每个触摸事件上报出去。
 *
 * 为什么不能用 OnTouchListener：事件会先交给子 View（列表项、复选框），
 * 子 View 消费掉之后父容器的 OnTouchListener 根本收不到 ACTION_DOWN，
 * 于是「按住暂停滚动」在列表区域完全失效。
 * dispatchTouchEvent 在事件分发给子 View 之前调用，任何触摸都能被看到。
 */
class TouchObservingLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 事件观察者；不消费事件，只做旁路通知 */
    var touchObserver: ((MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchObserver?.invoke(ev)
        return super.dispatchTouchEvent(ev)
    }
}
