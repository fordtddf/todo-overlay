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
}
