package com.example.todooverlay.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.todooverlay.R
import com.example.todooverlay.TodoApp
import com.example.todooverlay.data.ScrollMode
import com.example.todooverlay.data.SettingsStore
import com.example.todooverlay.data.Todo
import com.example.todooverlay.data.Today
import com.example.todooverlay.overlay.UnlockOverlayService
import com.example.todooverlay.overlay.preview
import com.example.todooverlay.overlay.start
import com.example.todooverlay.overlay.stop
import com.example.todooverlay.util.PermissionHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    /** 每次回到前台 +1，驱动设置页重新探测权限（用户刚从系统设置页回来） */
    private var permissionEpoch by mutableStateOf(0)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            TodoOverlayTheme {
                val vm: TodoViewModel = viewModel()
                val todos by vm.todos.collectAsStateWithLifecycle()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    if (showSettings) {
                        SettingsScreen(
                            epoch = permissionEpoch,
                            vm = vm,
                            onBack = { showSettings = false }
                        )
                    } else {
                        TodoScreen(
                            todos = todos,
                            onAdd = vm::add,
                            onEdit = vm::update,
                            onToggle = vm::toggle,
                            onDelete = vm::delete,
                            onOpenSettings = { showSettings = true }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionEpoch++

        // 服务被系统或用户清掉后，回到前台补拉一次（尊重设置里的开关）
        val app = application as TodoApp
        lifecycleScope.launch {
            val enabled = app.settings.overlayEnabled.first()
            if (enabled && PermissionHelper.canDrawOverlays(this@MainActivity)) {
                UnlockOverlayService.start(this@MainActivity)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoScreen(
    todos: List<Todo>,
    onAdd: (String, Boolean) -> Unit,
    onEdit: (Todo, String, Boolean) -> Unit,
    onToggle: (Todo) -> Unit,
    onDelete: (Todo) -> Unit,
    onOpenSettings: () -> Unit
) {
    val today = Today.key()
    val pending = todos.count { !it.isDoneOn(today) }
    var editor by remember { mutableStateOf<TodoEditorState?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("待办", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (pending == 0) "全部完成" else "还有 $pending 项未完成",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editor = TodoEditorState(null) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("添加") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (todos.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "还没有待办，点右下角添加",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(todos, key = { it.id }) { todo ->
                TodoRow(
                    todo = todo,
                    done = todo.isDoneOn(today),
                    onToggle = { onToggle(todo) },
                    onEdit = { editor = TodoEditorState(todo) },
                    onDelete = { onDelete(todo) }
                )
            }
        }
    }

    editor?.let { state ->
        TodoEditorDialog(
            state = state,
            onDismiss = { editor = null },
            onConfirm = { title, daily ->
                val target = state.todo
                if (target == null) onAdd(title, daily) else onEdit(target, title, daily)
                editor = null
            }
        )
    }
}

/** 弹窗状态：todo 为 null 表示新增 */
private data class TodoEditorState(val todo: Todo?)

@Composable
private fun TodoEditorDialog(
    state: TodoEditorState,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit
) {
    var title by remember(state) { mutableStateOf(state.todo?.title.orEmpty()) }
    var daily by remember(state) { mutableStateOf(state.todo?.isDaily ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (state.todo == null) R.string.todo_add_title else R.string.todo_edit_title
                )
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    placeholder = { Text("要做什么？") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.todo_daily_switch),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            stringResource(R.string.todo_daily_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = daily, onCheckedChange = { daily = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, daily) },
                enabled = title.isNotBlank()
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun TodoRow(
    todo: Todo,
    done: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
                val checkColor by animateColorAsState(
                    if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "checkColor"
                )
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (done) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            else androidx.compose.ui.graphics.Color.Transparent
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "已完成",
                            tint = checkColor,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onEdit)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = todo.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null
                )
                if (todo.isDaily) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Repeat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.todo_daily_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    epoch: Int,
    vm: TodoViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val overlayEnabled by vm.overlayEnabled.collectAsStateWithLifecycle()
    val duration by vm.durationSeconds.collectAsStateWithLifecycle()

    val autoScroll by vm.autoScrollEnabled.collectAsStateWithLifecycle()
    val mode by vm.scrollMode.collectAsStateWithLifecycle()
    val speed by vm.scrollSpeed.collectAsStateWithLifecycle()
    val widthPercent by vm.cardWidthPercent.collectAsStateWithLifecycle()
    val heightDp by vm.cardMaxHeightDp.collectAsStateWithLifecycle()

    // DataStore 首次发射前给默认值，避免 Composable 提前 return 造成布局抖动
    val enabled = overlayEnabled ?: true
    val seconds = duration ?: SettingsStore.DEFAULT_SECONDS
    val autoScrollOn = autoScroll ?: SettingsStore.DEFAULT_AUTO_SCROLL
    val scrollMode = mode ?: SettingsStore.DEFAULT_SCROLL_MODE
    val speedValue = speed ?: SettingsStore.DEFAULT_SPEED
    val cardWidth = widthPercent ?: SettingsStore.DEFAULT_WIDTH_PERCENT
    val cardHeight = heightDp ?: SettingsStore.DEFAULT_HEIGHT_DP

    // epoch 变化（从系统设置返回）时重新探测权限
    val canOverlay = remember(epoch) { PermissionHelper.canDrawOverlays(context) }
    val batteryOk = remember(epoch) { PermissionHelper.isIgnoringBatteryOptimizations(context) }
    val canNotify = remember(epoch) { PermissionHelper.canPostNotifications(context) }

    // 开关与权限、服务状态保持同步：开启但没权限时只提示，不强行启动
    LaunchedEffect(enabled, canOverlay) {
        if (enabled && canOverlay) {
            UnlockOverlayService.start(context)
        } else {
            UnlockOverlayService.stop(context)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 设置项变多了，小屏上要能滚动
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            context.getString(R.string.settings_overlay_switch),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            context.getString(R.string.settings_overlay_switch_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { vm.setOverlayEnabled(it) }
                    )
                }

                if (!canOverlay) {
                    Text(
                        "⚠ 尚未授予悬浮窗权限，弹窗不会生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = androidx.compose.ui.graphics.Color(0xFFFFB74D)
                    )
                }

                // 拖动时只改本地值，松手才写 DataStore，否则一次拖动会写几十次
                var sliderValue by remember(seconds) { mutableFloatStateOf(seconds.toFloat()) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        context.getString(R.string.settings_duration),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = " ${sliderValue.roundToInt()} 秒",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { vm.setDurationSeconds(sliderValue.roundToInt()) },
                    valueRange = SettingsStore.MIN_SECONDS.toFloat()..SettingsStore.MAX_SECONDS.toFloat(),
                    steps = SettingsStore.MAX_SECONDS - SettingsStore.MIN_SECONDS - 1
                )
                Text(
                    "解锁后显示 ${SettingsStore.MIN_SECONDS}~${SettingsStore.MAX_SECONDS} 秒，超时自动淡出",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 不必反复锁屏解锁就能验证链路是否打通
                TextButton(
                    onClick = { UnlockOverlayService.preview(context) },
                    enabled = canOverlay && enabled,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("立即预览卡片")
                }
                if (!canOverlay) {
                    Text(
                        "先授予悬浮窗权限才能预览",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (!enabled) {
                    Text(
                        "打开上面的开关后才能预览",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------------- 自动滚动 ----------------
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("解锁弹窗自动滚动", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "内容超出一屏时自动匀速滚动",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoScrollOn,
                        onCheckedChange = { vm.setAutoScrollEnabled(it) }
                    )
                }

                if (autoScrollOn) {
                    Text(
                        "滚动模式",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip("单遍", scrollMode == ScrollMode.SINGLE) {
                            vm.setScrollMode(ScrollMode.SINGLE)
                        }
                        ChoiceChip("循环", scrollMode == ScrollMode.LOOP) {
                            vm.setScrollMode(ScrollMode.LOOP)
                        }
                    }
                    Text(
                        if (scrollMode == ScrollMode.SINGLE) {
                            "滚到底部停顿 2 秒后自动关闭"
                        } else {
                            "到底部停顿后回滚到顶部继续，最长 18 秒兜底关闭"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        "滚动速度",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip("慢速 25", speedValue == SettingsStore.SPEED_SLOW) {
                            vm.setScrollSpeed(SettingsStore.SPEED_SLOW)
                        }
                        ChoiceChip("适中 40", speedValue == SettingsStore.SPEED_NORMAL) {
                            vm.setScrollSpeed(SettingsStore.SPEED_NORMAL)
                        }
                        ChoiceChip("快速 60", speedValue == SettingsStore.SPEED_FAST) {
                            vm.setScrollSpeed(SettingsStore.SPEED_FAST)
                        }
                    }
                    Text(
                        "单位 dp/秒。速度只影响自动滚动，单遍模式的总时长会自动跟着重算",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------------- 卡片尺寸 ----------------
            SettingCard {
                Text("悬浮卡片尺寸", style = MaterialTheme.typography.titleMedium)
                Text(
                    "改动即时保存，下次弹窗生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    ChoiceChip(
                        "紧凑",
                        cardWidth == SettingsStore.PRESET_S_WIDTH &&
                            cardHeight == SettingsStore.PRESET_S_HEIGHT
                    ) {
                        vm.applyCardPreset(
                            SettingsStore.PRESET_S_WIDTH,
                            SettingsStore.PRESET_S_HEIGHT
                        )
                    }
                    ChoiceChip(
                        "默认",
                        cardWidth == SettingsStore.PRESET_M_WIDTH &&
                            cardHeight == SettingsStore.PRESET_M_HEIGHT
                    ) {
                        vm.applyCardPreset(
                            SettingsStore.PRESET_M_WIDTH,
                            SettingsStore.PRESET_M_HEIGHT
                        )
                    }
                    ChoiceChip(
                        "宽大",
                        cardWidth == SettingsStore.PRESET_L_WIDTH &&
                            cardHeight == SettingsStore.PRESET_L_HEIGHT
                    ) {
                        vm.applyCardPreset(
                            SettingsStore.PRESET_L_WIDTH,
                            SettingsStore.PRESET_L_HEIGHT
                        )
                    }
                }

                var widthSlider by remember(cardWidth) {
                    mutableFloatStateOf(cardWidth.toFloat())
                }
                Text(
                    "宽度 ${widthSlider.roundToInt()}% 屏宽",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Slider(
                    value = widthSlider,
                    onValueChange = { widthSlider = it },
                    onValueChangeFinished = { vm.setCardWidthPercent(widthSlider.roundToInt()) },
                    valueRange = SettingsStore.MIN_WIDTH_PERCENT.toFloat()..
                        SettingsStore.MAX_WIDTH_PERCENT.toFloat(),
                    steps = SettingsStore.MAX_WIDTH_PERCENT - SettingsStore.MIN_WIDTH_PERCENT - 1
                )

                var heightSlider by remember(cardHeight) {
                    mutableFloatStateOf(cardHeight.toFloat())
                }
                Text(
                    "最大高度 ${heightSlider.roundToInt()} dp",
                    style = MaterialTheme.typography.titleSmall
                )
                Slider(
                    value = heightSlider,
                    onValueChange = { heightSlider = it },
                    // 10dp 一档：180、190 … 420 共 25 个位置
                    onValueChangeFinished = {
                        vm.setCardMaxHeightDp(
                            (heightSlider / 10).roundToInt() * 10
                        )
                    },
                    valueRange = SettingsStore.MIN_HEIGHT_DP.toFloat()..
                        SettingsStore.MAX_HEIGHT_DP.toFloat(),
                    steps = (SettingsStore.MAX_HEIGHT_DP - SettingsStore.MIN_HEIGHT_DP) / 10 - 1
                )
                Text(
                    "任务少时卡片按内容自适应，超过这个高度才出现滚动",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { vm.resetCardSize() },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("恢复默认尺寸")
                }
            }

            SettingCard {
                PermissionRow(
                    title = context.getString(R.string.settings_permission_overlay),
                    desc = context.getString(R.string.settings_permission_overlay_desc),
                    granted = canOverlay,
                    onOpen = {
                        PermissionHelper.openFirstAvailable(
                            context,
                            PermissionHelper.overlaySettingsIntent(context)
                        )
                    }
                )
                PermissionRow(
                    title = "通知权限",
                    desc = if (canNotify) "已允许" else "前台服务的常驻通知需要它，被禁时服务会被系统降级",
                    granted = canNotify,
                    onOpen = {
                        // 固定跳系统通知设置页：既覆盖运行时权限被拒，
                        // 也覆盖 HyperOS 上单独关掉应用通知的情况，
                        // 还不会跟权限对话框互相顶掉
                        // Settings 与 Icons.Default.Settings 同名，这里用全限定名
                        PermissionHelper.openFirstAvailable(
                            context,
                            listOf(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                ).apply {
                                    putExtra(
                                        android.provider.Settings.EXTRA_APP_PACKAGE,
                                        context.packageName
                                    )
                                },
                                PermissionHelper.appDetailsIntent(context)
                            )
                        )
                    }
                )
                PermissionRow(
                    title = context.getString(R.string.settings_battery),
                    desc = if (batteryOk) "已忽略电池优化" else context.getString(R.string.settings_battery_desc),
                    granted = batteryOk,
                    onOpen = {
                        // 先申请忽略电池优化；系统不给弹就直接跳厂商自启动页
                        PermissionHelper.openFirstAvailable(
                            context,
                            listOf(
                                PermissionHelper.batteryOptimizationIntent(context),
                                PermissionHelper.miuiPowerKeeperIntent(context),
                                PermissionHelper.appDetailsIntent(context)
                            )
                        )
                    }
                )
                if (PermissionHelper.needsOemGuidance()) {
                    Text(
                        "本机为定制系统：请再到系统设置里把本应用的「自启动」打开、" +
                            "「电池 → 后台运行」设为无限制，否则息屏一段时间后解锁监听会被杀掉。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            PermissionHelper.openFirstAvailable(
                                context,
                                PermissionHelper.autoStartIntents().plus(
                                    PermissionHelper.appDetailsIntent(context)
                                )
                            )
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("打开自启动设置")
                    }
                    if (PermissionHelper.isMiuiDevice()) {
                        // HyperOS 的省电策略和自启动是两个不同的页面，单独给一个入口
                        TextButton(
                            onClick = {
                                PermissionHelper.openFirstAvailable(
                                    context,
                                    listOfNotNull(
                                        PermissionHelper.miuiPowerKeeperIntent(context),
                                        PermissionHelper.appDetailsIntent(context)
                                    )
                                )
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("打开省电策略（设为「无限制」）")
                        }
                    }
                }
            }

            TextButton(onClick = onBack) { Text("返回待办") }

            // 装上之后一眼就能确认是哪个版本，避免"改了却没装上去"这种排查黑洞
            Text(
                "版本 ${appVersionName(context)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun appVersionName(context: Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName} (${info.longVersionCode})"
} catch (t: Throwable) {
    "未知"
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    desc: String,
    granted: Boolean,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onOpen) {
            Text(if (granted) "已授权" else "去授权")
        }
    }
}
