package com.example.todooverlay.overlay

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * ScrollView 本身不支持 maxHeight。
 *
 * 这里把高度测量规格改成 AT_MOST(maxHeight)：
 * 内容少时按 wrap_content 收缩，内容多时封顶并可以上下滚动。
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /** 最大高度（像素）。<= 0 表示不限制 */
    var maxHeight: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeight > 0) {
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, spec)
    }

    /**
     * 会话式保持滚动条可见。
     *
     * View.awakenScrollBars() 是 protected，外部调不到，
     * 这里开一个公开入口供自动滚动时逐帧调用 —— 代码调用 scrollTo 时
     * 系统默认不会绘制滚动条，必须主动唤醒。
     */
    fun keepScrollBarAwake() {
        awakenScrollBars()
        // 即使系统把滚动条标记为淡出，也强制重绘一次
        invalidate()
    }
}
