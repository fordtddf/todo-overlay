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
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.example.todooverlay.R
import com.example.todooverlay.data.LoopExitMode
import com.example.todooverlay.data.ScrollMode
import com.example.todooverlay.data.TimeFormat
import com.example.todooverlay.data.Todo
import com.example.todooverlay.data.Word
import kotlin.math.abs
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
    val maxHeightDp: Int,
    /** 上滑甩飞立即关闭 */
    val flingToDismiss: Boolean,
    /** 卡片底部「快速记一笔」入口 */
    val quickAddEnabled: Boolean,
    /** 顶部今日完成进度 */
    val showProgress: Boolean,
    /** 逾期任务折叠 */
    val groupOverdue: Boolean,
    /** 今日零点时间戳，用于区分今日 / 往日任务 */
    val todayStart: Long,
    val progressDone: Int,
    val progressTotal: Int,
    /** 低电量降级：禁用动画，静态展示 */
    val forceStatic: Boolean,
    /** 是否显示待办存在时长 */
    val showExistsTime: Boolean,
    /** 本次要展示的六级单词，空表示不显示 */
    val words: List<Word> = emptyList(),
    /** 单词区字号（sp） */
    val wordTextSizeSp: Int = 12
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

    /** 保存窗口参数，快速记一笔时需要临时改 flags 再 updateViewLayout */
    private var rootParams: WindowManager.LayoutParams? = null

    // 上滑关闭的手势跟踪状态（在 dispatchTouchEvent 旁路里更新，见 handleFlingGesture）
    private var velocityTracker: VelocityTracker? = null
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureDy = 0f
    private var gestureActive = false
    private var gestureScroll: MaxHeightScrollView? = null

    /** 用户已经在卡片上勾选过，之后只等淡出，不再恢复滚动和倒计时 */
    private var completionTriggered = false

    fun show(
        todos: List<Todo>,
        config: OverlayConfig,
        onToggle: (Todo, Boolean) -> Unit,
        onQuickAdd: (String) -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(todos, config, onToggle, onQuickAdd) }
            return
        }
        removeImmediately()
        completionTriggered = false

        if (!Settings.canDrawOverlays(appContext)) return

        val view = LayoutInflater.from(appContext).inflate(R.layout.overlay_card, null)
        bindContent(view, todos, config, onToggle)
        bindWords(view, config)
        applyProgress(view, config)
        applySize(view, config)
        applyOpacity(view, config)
        attachInteractionHandlers(view, config)

        view.alpha = 0f
        val params = buildParams(config)
        rootParams = params
        try {
            windowManager.addView(view, params)
        } catch (t: Throwable) {
            return
        }

        // 必须在 addView 之后：这里要拿到已生效的 LayoutParams 做焦点切换
        bindQuickAdd(view, config, onQuickAdd)

        rootView = view
        currentConfig = config
        view.animate().alpha(1f).setDuration(FADE_IN_MS).start()

        // 必须等布局完成才知道内容到底有多高、能滚多远
        view.post {
            if (rootView === view) startTimers(view, config)
        }
    }

    /**
     * 快速记一笔后立刻把新条目插进卡片，不用等下次解锁。
     * 插在列表末尾并同步更新渲染状态。
     */
    fun appendTodo(todo: Todo) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { appendTodo(todo) }
            return
        }
        val view = rootView ?: return
        val container = view.findViewById<LinearLayout>(R.id.container_items) ?: return
        val inflater = LayoutInflater.from(view.context)
        addRow(inflater, container, todo, view) { _, _ -> }
        view.post { refreshScrollRange(view) }
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

    /**
     * 全部完成时的祝贺气泡。没有列表、没有滚动，停留 2 秒自动淡出。
     */
    fun showCelebration(
        done: Int,
        total: Int,
        durationSeconds: Int,
        widthPercent: Int,
        opacityPercent: Int
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                showCelebration(done, total, durationSeconds, widthPercent, opacityPercent)
            }
            return
        }
        removeImmediately()
        completionTriggered = false

        if (!Settings.canDrawOverlays(appContext)) return

        val view = LayoutInflater.from(appContext).inflate(R.layout.overlay_celebration, null)
        view.findViewById<TextView>(R.id.tv_celebration_detail).text =
            appContext.getString(R.string.overlay_today_progress, done, total, 100)
        view.background?.mutate()?.alpha = opacityPercent.coerceIn(0, 100) * 255 / 100

        val params = WindowManager.LayoutParams(
            (screenWidth() * widthPercent / 100f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = dp(TOP_MARGIN_DP)

        view.alpha = 0f
        try {
            windowManager.addView(view, params)
        } catch (t: Throwable) {
            return
        }
        rootView = view
        view.animate().alpha(1f).setDuration(FADE_IN_MS).start()

        mainHandler.postDelayed({
            if (rootView === view) fadeOut()
        }, durationSeconds * 1000L)
    }

    // ---------------------------------------------------------------- 内容与尺寸

    private fun bindContent(
        view: View,
        todos: List<Todo>,
        config: OverlayConfig,
        onToggle: (Todo, Boolean) -> Unit
    ) {
        val inflater = LayoutInflater.from(view.context)
        val todayContainer = view.findViewById<LinearLayout>(R.id.container_items)

        // 逾期判定：单次任务创建于今天之前、且仍未完成
        fun isOverdue(todo: Todo): Boolean = !todo.isDaily && todo.createdAt < config.todayStart

        if (!config.groupOverdue) {
            todos.forEach { addRow(inflater, todayContainer, it, view, onToggle) }
        } else {
            val todayItems = todos.filterNot(::isOverdue)
            val overdueItems = todos.filter(::isOverdue)

            todayItems.forEach { addRow(inflater, todayContainer, it, view, onToggle) }

            if (overdueItems.isNotEmpty()) {
                val overdueContainer = view.findViewById<LinearLayout>(R.id.container_overdue)
                val toggle = view.findViewById<TextView>(R.id.tv_overdue_toggle)
                toggle.isVisible = true
                toggle.text = appContext.getString(R.string.overlay_overdue_toggle, overdueItems.size)

                overdueItems.forEach {
                    addRow(inflater, overdueContainer, it, view, onToggle)
                }

                toggle.setOnClickListener {
                    val expanded = overdueContainer.isVisible
                    overdueContainer.isVisible = !expanded
                    toggle.text = if (expanded) {
                        appContext.getString(R.string.overlay_overdue_toggle, overdueItems.size)
                    } else {
                        appContext.getString(R.string.overlay_overdue_collapse)
                    }
                    // 展开后内容变高，需要重新测量滚动范围
                    view.post { refreshScrollRange(view) }
                }
            }
        }

        view.findViewById<TextView>(R.id.tv_countdown).text =
            appContext.getString(R.string.overlay_countdown, config.durationSeconds)

        view.findViewById<View>(R.id.btn_close).setOnClickListener { fadeOut() }
    }

    private fun addRow(
        inflater: LayoutInflater,
        container: LinearLayout,
        todo: Todo,
        cardView: View,
        onToggle: (Todo, Boolean) -> Unit
    ) {
        val row = inflater.inflate(R.layout.overlay_item, container, false)
        val checkBox = row.findViewById<CheckBox>(R.id.cb_item)
        val label = row.findViewById<TextView>(R.id.tv_item)
        val strip = row.findViewById<View>(R.id.view_priority)
        val exists = row.findViewById<TextView>(R.id.tv_exists)

        // 优先级彩条
        strip.setBackgroundColor(priorityColor(todo.priority))

        // 有自定义序号就加在标题前
        val prefix = buildString {
            todo.customOrder?.let { append("#$it ") }
            if (todo.isDaily) append(appContext.getString(R.string.overlay_daily_tag))
        }
        label.text = prefix + todo.title

        // 存在时长
        val config = currentConfig
        if (config?.showExistsTime == true) {
            exists.isVisible = true
            exists.text = TimeFormat.existsLabel(todo.createdAt, todo.isDaily)
            if (TimeFormat.isStale(todo.createdAt)) {
                exists.setTextColor(STALE_COLOR)
            }
        } else {
            exists.isVisible = false
        }

        var checked = false
        row.setOnClickListener {
            checked = !checked
            applyRowState(checkBox, label, checked)
            onToggle(todo, checked)
            onItemToggled(cardView)
        }
        container.addView(row)
    }

    /** 0 灰 / 1 蓝 / 2 橙 / 3 红 */
    private fun priorityColor(priority: Int): Int = when (priority) {
        Todo.PRIORITY_URGENT -> 0xFFEF5350.toInt()
        Todo.PRIORITY_HIGH -> 0xFFFFB74D.toInt()
        Todo.PRIORITY_LOW -> 0xFF9E9E9E.toInt()
        else -> 0xFF64B5F6.toInt()
    }

    /** 卡片底部的 5 个六级新词 */
    private fun bindWords(view: View, config: OverlayConfig) {
        if (config.words.isEmpty()) return
        val container = view.findViewById<LinearLayout>(R.id.container_words)
        val text = view.findViewById<TextView>(R.id.tv_words)
        container.isVisible = true
        // 字号按用户设定，单位 sp，跟随系统字体缩放
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.wordTextSizeSp.toFloat())
        text.text = config.words.joinToString("\n") {
            appContext.getString(R.string.overlay_word_line, it.text, it.meaning)
        }
    }

    /** 顶部今日完成进度：3/7 · 42% */
    private fun applyProgress(view: View, config: OverlayConfig) {
        val label = view.findViewById<TextView>(R.id.tv_progress_text)
        if (!config.showProgress || config.progressTotal <= 0) {
            label.isVisible = false
            return
        }
        val percent = config.progressDone * 100 / config.progressTotal
        label.isVisible = true
        label.text = appContext.getString(
            R.string.overlay_today_progress,
            config.progressDone,
            config.progressTotal,
            percent
        )
    }

    /** 快速记一笔：点击展开输入框并请求焦点，回车保存后收起 */
    private fun bindQuickAdd(
        view: View,
        config: OverlayConfig,
        onQuickAdd: (String) -> Unit
    ) {
        if (!config.quickAddEnabled) return

        val button = view.findViewById<TextView>(R.id.btn_quick_add)
        val input = view.findViewById<EditText>(R.id.et_quick_add)
        button.isVisible = true

        val params = rootParams ?: return

        button.setOnClickListener {
            button.isVisible = false
            input.isVisible = true
            // 默认窗口是 NOT_FOCUSABLE，输入法弹不出来；临时放开焦点
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            try {
                windowManager.updateViewLayout(view, params)
            } catch (t: Throwable) {
                return@setOnClickListener
            }
            input.requestFocus()
            val imm = appContext.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }

        fun finishInput(save: Boolean) {
            val text = input.text.toString().trim()
            if (save && text.isNotEmpty()) onQuickAdd(text)
            input.setText("")
            input.isVisible = false
            button.isVisible = true
            appContext.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
            // 恢复不获取焦点的穿透状态
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            try {
                windowManager.updateViewLayout(view, params)
            } catch (t: Throwable) {
                // 窗口已被移除，忽略
            }
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                finishInput(save = true)
                true
            } else {
                false
            }
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) finishInput(save = false)
        }
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

        // 低电量降级：不创建滚动器，静态展示一小段时间就关
        if (config.forceStatic) {
            startCountdown(view, LOW_BATTERY_SHOW_MS)
            return
        }

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
    private fun attachInteractionHandlers(view: View, config: OverlayConfig) {
        // 卡片空白处也要消费掉触摸，避免穿透到下层应用
        view.isClickable = true
        // 上滑关闭和「按住暂停」共用同一条旁路通道：OnTouchListener 在列表区域收不到事件，
        // 只有 dispatchTouchEvent 能看到全部触摸（见 TouchObservingLayout 的注释）
        (view as? TouchObservingLayout)?.touchObserver = ::onTouchObserved
    }

    /**
     * 上滑关闭。只在旁路里观察，不消费事件，ScrollView 照常滚动。
     *
     * 关键的一道仲裁：列表还没滚到顶部时，上滑属于「滚列表」，不算关闭手势。
     * 否则用户正常翻列表时速度快一点，卡片就被误关了。
     * 一口气滑到顶再继续上滑，才会接管成关闭手势 —— 和 iOS 面板的行为一致。
     *
     * 接管之后同时看速度和距离：快速甩上去直接关，慢慢拖过卡片高度的 1/4 也关。
     * 拖动过程中卡片跟着手指走（阻尼位移），没达到阈值就弹回原位。
     */
    private fun handleFlingGesture(event: MotionEvent) {
        val config = currentConfig ?: return
        if (!config.flingToDismiss) return
        val view = rootView ?: return
        val slop = dp(FLING_SLOP_DP).toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartX = event.rawX
                gestureStartY = event.rawY
                gestureDy = 0f
                gestureActive = false
                gestureScroll = view.findViewById(R.id.scroll_items)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
            }

            MotionEvent.ACTION_MOVE -> {
                val tracker = velocityTracker ?: return
                tracker.addMovement(event)
                val dx = event.rawX - gestureStartX

                if (!gestureActive) {
                    // 列表还能往上滚，这次滑动归 ScrollView。每帧重置基准，
                    // 于是「滑到顶的那一刻」自然成为新的起点，之前的位移不计入
                    if ((gestureScroll?.scrollY ?: 0) > 0) {
                        gestureStartY = event.rawY
                        gestureDy = 0f
                        return
                    }
                    gestureDy = event.rawY - gestureStartY
                    if (-gestureDy > slop && -gestureDy > abs(dx) * FLING_VERTICAL_BIAS) {
                        gestureActive = true
                    } else if (abs(dx) > slop) {
                        // 横向为主，不是上滑手势，本次序列放弃
                        tracker.recycle()
                        velocityTracker = null
                        return
                    }
                } else {
                    gestureDy = event.rawY - gestureStartY
                }
                if (gestureActive) applyFollow(view)
            }

            MotionEvent.ACTION_UP -> {
                val tracker = velocityTracker
                velocityTracker = null
                if (tracker == null || !gestureActive) {
                    tracker?.recycle()
                    settleBack(view)
                    return
                }
                tracker.addMovement(event)
                tracker.computeCurrentVelocity(1000)
                val vy = tracker.yVelocity
                tracker.recycle()

                val minDistance = dp(FLING_DISTANCE_MIN_DP).toFloat()
                val farEnough = -gestureDy > maxOf(view.height * FLING_DISTANCE_RATIO, minDistance)
                if (vy < -FLING_VELOCITY_THRESHOLD || farEnough) {
                    flingUpAndDismiss()
                } else {
                    settleBack(view)
                }
                gestureActive = false
            }

            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                settleBack(view)
                gestureActive = false
            }
        }
    }

    /** 跟手位移：阻尼系数让卡片滞后于手指，最多提起 48dp */
    private fun applyFollow(view: View) {
        val maxFollow = dp(FLING_FOLLOW_MAX_DP).toFloat()
        val offset = (gestureDy * FLING_FOLLOW_DAMPING).coerceIn(-maxFollow, 0f)
        view.translationY = offset
        val progress = -offset / dp(FLING_FOLLOW_MAX_DP).toFloat()
        view.alpha = 1f - progress * FLING_FOLLOW_FADE
    }

    /** 没达到关闭阈值：弹回原位，恢复不透明 */
    private fun settleBack(view: View) {
        if (view.translationY == 0f && view.alpha == 1f) return
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(FLING_SETTLE_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** 上滑关闭：从当前跟手位置继续向上加速滑出并淡出 */
    private fun flingUpAndDismiss() {
        val view = rootView ?: return
        rootView = null
        cleanupTimers()
        view.animate().cancel()
        view.animate()
            .translationY(-view.height.toFloat() - dp(FLING_EXIT_DP))
            .alpha(0f)
            .setDuration(FLING_EXIT_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { safeRemove(view) }
            .start()
    }

    private fun onTouchObserved(event: MotionEvent) {
        if (completionTriggered) return
        handleFlingGesture(event)
        // 上滑已经关掉了卡片，别再给已移除的视图排恢复任务
        if (rootView == null) return
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
        velocityTracker?.recycle()
        velocityTracker = null
        gestureActive = false
        gestureScroll = null
        rootParams = null
    }

    /** 逾期分组展开后内容变高，重新测一次滚动范围 */
    private fun refreshScrollRange(view: View) {
        val config = currentConfig ?: return
        val scroll = view.findViewById<MaxHeightScrollView>(R.id.scroll_items)
        val distance = ((scroll.getChildAt(0)?.height ?: 0) - scroll.height).coerceAtLeast(0)
        if (distance <= 0) return
        scroller?.stop()
        scroller = null
        if (!config.autoScroll || config.forceStatic) return

        scroller = OverlayScroller(
            scrollView = scroll,
            scrollableDistance = distance,
            speedPxPerSec = dp(config.speedDpPerSec),
            loop = config.scrollMode == ScrollMode.LOOP,
            targetLoops = if (config.scrollMode == ScrollMode.LOOP &&
                config.loopExitMode == LoopExitMode.COUNT
            ) config.loopMaxCount else 0,
            onRoundComplete = { completed ->
                updateRoundLabel(view, completed, config.loopMaxCount)
            },
            onLoopsFinished = { fadeOut() }
        ).also { it.start() }
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

        /** 上滑甩飞关闭的触发速度（px/s），降到 700 让轻甩也能命中 */
        private const val FLING_VELOCITY_THRESHOLD = 700f

        /** 慢速拖动也算关闭：上滑超过卡片高度的这个比例，且不低于 60dp */
        private const val FLING_DISTANCE_RATIO = 0.25f
        private const val FLING_DISTANCE_MIN_DP = 60

        /** 判定为垂直上滑的起手位移（dp），以及垂直方向相对横向的倍数要求 */
        private const val FLING_SLOP_DP = 8
        private const val FLING_VERTICAL_BIAS = 1.2f

        /** 跟手：位移阻尼系数、最大提起高度（dp）、最多淡到多少不透明度 */
        private const val FLING_FOLLOW_DAMPING = 0.2f
        private const val FLING_FOLLOW_MAX_DP = 48
        private const val FLING_FOLLOW_FADE = 0.25f

        /** 未达阈值时回弹的时长 */
        private const val FLING_SETTLE_MS = 200L

        private const val FLING_EXIT_MS = 200L
        private const val FLING_EXIT_DP = 40

        /** 低电量模式下静态展示的时长 */
        private const val LOW_BATTERY_SHOW_MS = 3000L

        /** 超过 7 天的时长标签用淡橙色弱警示 */
        private const val STALE_COLOR = 0xFFFFB74D.toInt()
    }
}
