# 今日待办 · 解锁悬浮卡片

解锁屏幕后自动弹出今日未完成事项的轻量卡片，显示指定秒数后自动淡出。

**纯展示，不拦截任何触摸。** 卡片显示期间，滑动、点击、接电话全部正常。

## 为什么做这个

每天早上解锁手机，先看今天要做什么，比打开 App 翻列表快得多。
市场上没有合适的工具：要么是重型待办应用（打开才看得到），
要么是日历小组件（锁屏解锁后不出现）。

这个 App 只做一件事：**解锁时把今天没做完的事摆在你面前 5 秒**。

## 功能

- 解锁屏幕自动弹出未完成事项（最多 5 条）
- **卡片上直接勾选完成**，文字立刻划线置灰，实时写回数据库
- 手指按住卡片时暂停倒计时，勾选后停留 1.8 秒再淡出
- 顶部倒计时进度条 + 剩余秒数
- 显示时长 3~15 秒可调，默认 5 秒
- 卡片只在自己矩形内接收点击，**卡片以外的区域完全穿透**，绝不挡桌面图标
- 通话中不打扰，显示期间来电立即收回
- 开机自启，重启后自动恢复监听

## 两类任务

| | 每日提醒 | 单次任务 |
|---|---|---|
| 跨日行为 | 第二天自动回到未完成 | 保持未完成，一直提醒 |
| 完成判定 | `last_completed_date == 今天` | `is_completed == true` |
| 适用 | 吃药、背单词、喝水 | 交房租、修自行车 |

跨日**不使用任何定时任务**，而是在每次读取时拿当前日期现算。
好处是即使手机整夜关机、App 被系统冻结，第二天打开也一定是对的。

## 截图

待补充。

## 安装

**[下载最新版 APK](https://github.com/fordtddf/todo-overlay/releases/latest)**，传到手机点击安装。

> 首次安装需允许「未知来源应用」。这是所有侧载 APK 的必经步骤。
>
> 已装旧版本可直接覆盖安装，待办数据会保留（含数据库迁移）。

## 使用前必须配置

装好后打开 App，设置页有四个权限入口，**缺一不可**：

| 权限 | 作用 | 不配的后果 |
|---|---|---|
| 悬浮窗 | 绘制卡片 | 卡片完全不显示 |
| 通知 | 前台服务常驻通知 | 服务被系统降级，后台留不住 |
| 电池优化 | 后台保活 | 息屏一段时间后被清理 |
| 自启动 | 开机恢复监听 | 重启后永久失效 |

配置完点「**立即预览卡片**」验证：能弹出就说明链路通了。

之后再锁屏解锁一次确认广播也正常。预览走的是和真实解锁完全相同的代码路径，
所以**预览正常但解锁不出，问题一定在保活配置**。

### 国产 ROM 特别说明

小米 / HyperOS、vivo / OriginOS、OPPO / ColorOS 等系统杀后台很激进，
设置页会自动识别厂商并给出对应跳转入口。

小米用户注意：HyperOS 用的是自家的 `powerkeeper`，AOSP 的「忽略电池优化」
对话框在小米上经常被拦截、点了没反应，所以设置页直接跳小米自己的省电策略页。
另外 HyperOS 的「自启动」还决定 `BOOT_COMPLETED` 会不会投递给你，不开的话重启后服务永远起不来。

## 自行构建

需要 JDK 17 与 Android SDK（platform 34、build-tools 34.0.0）。

```bash
gradle assembleRelease
```

> 仓库未包含 `gradle/wrapper/gradle-wrapper.jar`（二进制文件）。
> 如需 `./gradlew`，先在本机执行一次 `gradle wrapper --gradle-version 8.7`，
> 或用 Android Studio 打开项目让它自动补齐。

`release` 构建需要签名密钥。**仓库里不含密钥**，需自行生成：

```bash
keytool -genkeypair -v -keystore keystore.jks -alias todooverlay \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <你的密码> -keypass <你的密码> \
  -dname "CN=YourName, OU=Personal, O=Personal, L=City, ST=Province, C=CN"
```

然后在项目根目录创建 `keystore.properties`（已在 `.gitignore` 中）：

```properties
storeFile=keystore.jks
storePassword=<你的密码>
keyAlias=todooverlay
keyPassword=<你的密码>
```

也可以用环境变量 `TODO_KEYSTORE_PATH` / `TODO_KEYSTORE_PASSWORD` /
`TODO_KEY_ALIAS` / `TODO_KEY_PASSWORD`，适合 CI。

没有密钥时 `assembleRelease` 会产出一个未签名的 APK，无法安装。

## 技术要点

Kotlin + Jetpack Compose + Room + DataStore，minSdk 26 / targetSdk 34。

几个踩过的坑，改代码时容易再踩一遍：

### 1. `ACTION_USER_PRESENT` 不能静态注册

Android 8.0 起后台广播受限。方案是常驻前台服务，在服务里动态注册。
服务用 `foregroundServiceType="specialUse"`（Android 14 强制要求声明类型）。

Android 13+ 动态注册**必须**用 `RECEIVER_EXPORTED`。
`ACTION_USER_PRESENT` 是受保护广播，第三方伪造不了，不存在被恶意触发的风险；
反过来用 `NOT_EXPORTED` 有收不到广播的风险。

### 2. 背景模糊会拦截触摸（本项目最大的坑）

卡片现在需要在自身上勾选，所以窗口是可点击的：

```
FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH
```

`NOT_TOUCH_MODAL` 保证窗口矩形之外的触摸照常交给下层桌面，
`WATCH_OUTSIDE_TOUCH` 让我们能感知「点到外面了」并顺手收起卡片。

**绝对不要加 `FLAG_BLUR_BEHIND`。** 之前做毛玻璃时踩过：
Android 12+ 会为模糊在窗口下铺一层 `Dim Layer`，这层遮罩的不透明度
超过 `BlockUntrustedTouches` 的阈值，结果是**整块屏幕的触摸都被丢弃**，
看起来就像卡片把屏幕锁死了。日志特征：

```
InputDispatcher: Untrusted touch due to occlusion by /1000/Dim Layer
InputDispatcher: Dropping untrusted touch event due to /1000
```

排查时注意拦截者名字：是自己的包名才是窗口 alpha 问题，是 `Dim Layer`
就是模糊或系统遮罩。降低卡片 alpha 是没用的 —— 拦住你的根本不是卡片。

### 3. 快速连续解锁会叠卡片

`ValueAnimator.cancel()` 会回调 `onAnimationEnd`，若在里面调用淡出会造成重入，
同一视图被淡出两次。必须先摘引用和监听，再 cancel。

### 4. 视图生命周期独立于 Activity

只用 `applicationContext` 拿 `WindowManager`，所有 `addView` / `removeView`
都包 try-catch —— 息屏、切换用户、进程重建时视图可能已被系统回收。

## 待办数据来源

**不读取小米笔记。** 小米笔记的待办存在应用私有目录，第三方应用无权访问，
也没有公开 API，ContentProvider 带签名级权限保护。除非手机 root，否则读不到。

待办在本应用内独立录入。如果你需要从其他地方导入，欢迎提 issue。

## 贡献

欢迎 issue 和 PR。改代码前建议先读上面「技术要点」—— 第 2 条尤其容易踩。

## License

[MIT](LICENSE)
