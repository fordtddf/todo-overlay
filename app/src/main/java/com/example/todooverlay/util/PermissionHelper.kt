package com.example.todooverlay.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * 悬浮窗权限 + 各家 ROM 的后台保活跳转。
 *
 * 国内定制系统杀后台极其激进，只申请权限不够：还要引导用户
 * ① 关闭电池优化 ② 打开自启动 ③ 把后台策略设为“无限制”。
 * 不同厂商的设置页 Activity 名字并不稳定，所以全部用 try-catch 兜底，
 * 跳转失败就退回到系统应用详情页。
 */
object PermissionHelper {

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 通知权限。Android 13+ 是运行时权限；HyperOS 上用户还可能单独关掉本应用通知，
     * 两种都要算进来 —— 前台服务的常驻通知被禁时服务会被系统降级。
     */
    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun overlaySettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 直接弹系统的“忽略电池优化”确认框（需 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS） */
    @SuppressLint("BatteryLife")
    fun batteryOptimizationIntent(context: Context): Intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )

    fun appDetailsIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )

    /**
     * 厂商自启动管理页。返回全部候选，调用方逐个尝试。
     *
     * 小米 / HyperOS 这一页是「自启动」，但 HyperOS 上它同时还决定
     * BOOT_COMPLETED 会不会投递给应用 —— 不开的话重启后服务永远起不来。
     */
    fun autoStartIntents(): List<Intent> {
        val candidates = when {
            isMiui() -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.securitycenter" to "com.miui.appmanager.ApplicationsDetailsActivity"
            )
            isVivo() -> listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
            )
            isOppo() -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity"
            )
            isHuawei() -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
            isMeizu() -> listOf(
                "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity"
            )
            isSamsung() -> listOf(
                "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
            else -> emptyList()
        }
        return candidates.map { (pkg, cls) -> Intent().setComponent(ComponentName(pkg, cls)) }
    }

    /**
     * 小米 / HyperOS 的「省电策略」页（应用智能省电 → 无限制）。
     * 必须带上包名 extra 才会直接定位到本应用，否则只是打开一长串列表。
     * 非小米机型返回 null。
     */
    fun miuiPowerKeeperIntent(context: Context): Intent? {
        if (!isMiui()) return null
        val label = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(context.packageName, 0)).toString()
        } catch (t: Throwable) {
            context.packageName
        }
        return Intent().apply {
            setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            )
            putExtra("package_name", context.packageName)
            putExtra("package_label", label)
        }
    }

    /** 依次尝试候选 Intent，全部不可用则打开应用详情页 */
    fun openFirstAvailable(context: Context, vararg intents: Intent?) =
        openFirstAvailable(context, intents.toList())

    fun openFirstAvailable(context: Context, intents: List<Intent?>) {
        for (intent in intents) {
            if (intent == null) continue
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (t: Throwable) {
                // 该 ROM 没有这个页面，试下一个
            }
        }
        try {
            context.startActivity(appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            // 彻底失败，只能让用户自己去系统设置里找
        }
    }

    // ------------------------------------------------------------- ROM 判定

    private fun brand(): String = (Build.BRAND + " " + Build.MANUFACTURER).lowercase()

    private fun isMiui() = brand().contains("xiaomi") || brand().contains("redmi") || isProp("ro.miui.ui.version.name")
    private fun isVivo() = brand().contains("vivo") || brand().contains("iqoo")
    private fun isOppo() = brand().contains("oppo") || brand().contains("realme") || brand().contains("oneplus") || brand().contains("oplus")
    private fun isHuawei() = brand().contains("huawei") || brand().contains("honor")
    private fun isMeizu() = brand().contains("meizu")
    private fun isSamsung() = brand().contains("samsung")

    private fun isProp(key: String): Boolean = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        !(get.invoke(null, key) as? String).isNullOrEmpty()
    } catch (t: Throwable) {
        false
    }

    /** 是否需要额外导流：原生/类原生 ROM 一般不需要 */
    fun needsOemGuidance(): Boolean =
        isMiui() || isVivo() || isOppo() || isHuawei() || isMeizu()

    /** 供设置页判断是否展示小米专属的「省电策略」入口 */
    fun isMiuiDevice(): Boolean = isMiui()
}
