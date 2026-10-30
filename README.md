# 今日待办 · 解锁悬浮卡片

解锁屏幕后自动弹出今日未完成事项的轻量卡片，显示指定秒数后自动淡出。

**纯展示，不拦截任何触摸。** 卡片显示期间，滑动、点击、接电话全部正常。

## 为什么做这个

每天早上解锁手机，先看今天要做什么，比打开 App 翻列表快得多。
市场上没有合适的工具：要么是重型待办应用（打开才看得到），
要么是日历小组件（锁屏解锁后不出现）。

这个 App 只做一件事：**解锁时把今天没做完的事摆在你面前 5 秒**。

## 功能

- 解锁屏幕自动弹出全部未完成事项，按「未完成 > 单次 > 每日 > 权重」智能排序
- **卡片上直接勾选完成**，文字立刻划线置灰，实时写回数据库
- 条目超出一屏时自动匀速滚动，支持单遍 / 循环两种模式和三档速度
- 手指按住或滑动卡片时暂停滚动与倒计时，抬手 2 秒后恢复
- 顶部倒计时进度条 + 剩余秒数，右上角有关闭按钮
- 卡片宽度、最大高度、背景不透明度可调（预设档位 + 精细滑块）
- 卡片只在自己矩形内接收点击，**卡片以外的区域完全穿透**，绝不挡桌面图标
- 点卡片外面**不会**关闭卡片，只有倒计时结束或点关闭按钮才收起
- 通话中不打扰，显示期间来电立即收回
- 开机自启，重启后自动恢复监听

## 排序规则

主界面列表和解锁卡片共用同一个比较器（`data/TodoOrdering.kt`），保证两处顺序一致：

1. **完成状态** —— 未完成在前，已完成沉底
2. **任务类型** —— 单次任务（时效紧迫）排在每日提醒之前
3. **权重** —— `order_index` 升序，默认等于创建时间

「是否完成」对每日任务依赖当天日期，所以比较器必须带 `todayStr`，
不能写成无参的静态比较器。

## 自动滚动

内容超出一屏时才滚动，否则卡片按内容自适应。

| 模式 | 行为 |
|---|---|
| 单遍 | 顶部停 1.5s → 匀速滚到底 → 底部停 2s → 淡出 |
| 循环 | 到底部停 2s → 平滑回滚到顶部继续，最长 18s 兜底关闭 |

单遍模式的总时长是**算出来的**，不是用户设的固定值：

```
总时长 = 顶部停顿 1.5s + (可滚动距离 / 速度) + 底部停顿 2s
```

这样列表一定滚得完才关闭。调整卡片高度会改变可滚动距离，时长随之重算。
不需要滚动时，才回退到设置里的固定显示时长（3~15 秒）。

速度范围 20~150 dp/s（5 dp 一档），另提供四档快捷预设：
慢速 30 / 标准 50 / 快速 80 / 极速 120，默认 40。

滚动条：ScrollView 在代码调用 `scrollTo` 时默认不绘制滚动条，
所以这里显式启用 `scrollbars=vertical` + `fadeScrollbars=false`，
并在滚动动画的每一帧调用 `awakenScrollBars()`，右侧 3dp 半透明圆角细条全程可见。

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
FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
```

`NOT_TOUCH_MODAL` 保证窗口矩形之外的触摸照常交给下层桌面。

**不要加 `FLAG_WATCH_OUTSIDE_TOUCH`。** 加过一版，结果用户点桌面时卡片被当成
「点了外面」直接关掉，体验很糟。卡片的消失只应该由倒计时结束或用户点关闭按钮触发，
外部手势不该被当成退出信号。

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

### 4. 「按住暂停」不能用 OnTouchListener

事件会先交给子 View（列表项、复选框）。子 View 消费掉之后，
父容器的 `OnTouchListener` 根本收不到 `ACTION_DOWN`，
于是「按住暂停自动滚动」在列表区域完全失效 —— 而且不会报错，只是不生效。

正确做法是在根布局的 `dispatchTouchEvent` 里旁路观察
（见 `overlay/TouchObservingLayout.kt`），它在事件分发给子 View 之前调用，
任何触摸都逃不掉，同时不影响子 View 正常响应。

### 5. 视图生命周期独立于 Activity

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
