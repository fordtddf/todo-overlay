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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.todooverlay.R
import com.example.todooverlay.TodoApp
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
                val todos by vm.todayTodos.collectAsStateWithLifecycle()

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
    onAdd: (String) -> Unit,
    onToggle: (Todo) -> Unit,
    onDelete: (Todo) -> Unit,
    onOpenSettings: () -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    val pending = todos.count { !it.done }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("今日待办", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = Today.key() + " · 还有 " + pending + " 项",
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
        bottomBar = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    // edge-to-edge 下 Scaffold 不会自动给 bottomBar 加 inset：
                    // 键盘弹起时用 ime，收起时用导航栏，取两者较大的那个
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                placeholder = { Text("添加一件事…") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    onAdd(input)
                    input = ""
                }),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            onAdd(input)
                            input = ""
                        },
                        enabled = input.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "添加")
                    }
                }
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
                            "今天还没有待办，添加一条吧",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(todos, key = { it.id }) { todo ->
                TodoRow(todo = todo, onToggle = { onToggle(todo) }, onDelete = { onDelete(todo) })
            }
        }
    }
}

@Composable
private fun TodoRow(todo: Todo, onToggle: () -> Unit, onDelete: () -> Unit) {
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
                val doneColor by animateColorAsState(
                    if (todo.done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "checkColor"
                )
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (todo.done) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            else androidx.compose.ui.graphics.Color.Transparent
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (todo.done) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "已完成",
                            tint = doneColor,
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

            Text(
                text = todo.title,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (todo.done) TextDecoration.LineThrough else null
            )

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

    // DataStore 首次发射前给默认值，避免 Composable 提前 return 造成布局抖动
    val enabled = overlayEnabled ?: true
    val seconds = duration ?: SettingsStore.DEFAULT_SECONDS

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
