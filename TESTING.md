# 验证记录

## 1.2.0 网络校准与帧刷新

- `scripts/build-local.sh` 与 `./gradlew assembleDebug` 均构建成功；本地调试 APK 的 v2/v3 签名验证通过。
- Android 11 模拟器：两路 NTP 校准成功，设置页显示手机时钟约快 456 ms、测量延迟 27 ms、2 个来源；目标 `14:00:00.000` 的系统闹钟登记在设备时钟 `14:00:00.457`，换算方向一致。
- Android 11 模拟器：校准时钟首页与跨应用悬浮窗均正常显示，无启动或帧回调崩溃；断开 Wi-Fi 和移动数据并清空模拟器测试数据后，首页明确显示“网络校准不可用 · 使用手机时间”。
- Android 11 模拟器：恢复网络后，无需点击校准按钮，首页自动回到“两路网络校准 · 较稳定”状态。
- Android 11 模拟器：取得两路校准结果后结束应用进程并断网，重新打开仍从本地恢复有效校准基准，状态显示“网络校准 · 较稳定 · 3分钟前 · 更新失败”；不会立即退回手机时间。
- Android 16 小米手机：通过 USB 安装最终 `1.2.0`（versionCode 5），设备内 `base.apk` 与交付包 SHA-256 一致；首页毫秒时钟、倒计时与“网络校准 · 较稳定”状态显示正常，系统闹钟把目标 `14:00:00.000` 换算登记为设备时间 `14:00:00.573`，精确闹钟权限有效。
- 设备屏幕实际发光延迟、120Hz 刷新效果及新版本真机到点声震延迟仍待测；NTP 状态是估算结果，不代表毫秒级绝对准确度。

## 1.1.0 原生界面

- Kotlin Android View 已替换首页、目标时间弹层、悬浮位置、外观与设置页面；APK 资源中只有 `clock-grid.png`，无 HTML、JS、字体文件或 WebView 调用。
- `scripts/build-local.sh` 构建并通过 APK v2/v3 签名校验；`./gradlew assembleDebug` 标准构建成功。
- Android 11 模拟器：原生首页当前时间和倒计时持续更新，主要操作按钮在 360 × 640 dp 首屏可见；设置、外观、悬浮位置页和目标时间弹层已截图检查。
- Android 11 模拟器：位置页能启动实际悬浮服务；防误触使悬浮窗口使用 `NOT_FOCUSABLE NOT_TOUCHABLE` 和 `alpha=0.79`。
- Android 11 模拟器：通过原生目标时间弹层选取“约 5 分钟后”后，系统闹钟登记为对应的 `Alarm clock` 目标时刻。
- Android 16 小米手机：通过系统文件管理器更新到 `1.1.0`（versionCode 4）；原生首页正常启动并截图检查，当前时间、目标时刻、倒计时和底部导航均可见，无启动崩溃。
- Android 16 小米手机：`SCHEDULE_EXACT_ALARM` 仍为 `allow`；系统闹钟队列已登记当天 `13:00:00.000` 的 `RTC_WAKEUP`/`Alarm clock`，`exactAllowReason=permission`。

## 1.0.2 原生提醒

- `scripts/build-local.sh`：构建成功；APK 签名 v2/v3 校验通过。
- APK 元信息：`com.zhundian.clock`，版本 `1.0.2`，最低 API 26，目标 API 36，包含精确闹钟与开机重排权限。
- Android 11 模拟器：主界面、目标时间弹层、“约 5 分钟后”快捷目标和倒计时已检查。
- Android 11 模拟器：前台服务启动、切到桌面后仍显示悬浮时钟、拖动位置已检查。
- Android 11 模拟器：防误触模式的窗口标志为 `NOT_FOCUSABLE NOT_TOUCHABLE`，系统窗口 alpha 为 `0.79`。
- Android 11 模拟器：关闭按钮可停止前台服务，已修复配置刷新将服务重新启动的问题。
- Android 11 模拟器：位置页显示原生悬浮窗；防误触开启时测试按钮能收到穿透点击；关闭后可直接拖动；桌面和返回位置页保留拖动位置，时钟页隐藏悬浮窗。
- Android 11 模拟器：目标设为 `2026-10-21 11:44:00.000`，关闭悬浮窗并切到桌面；系统 `dumpsys alarm` 显示 `RTC_WAKEUP` 的 `Alarm clock` 登记在该时刻，到点后记录了 1 次 `FIRE_REMINDER` 唤醒。`dumpsys vibrator` 在 `11:44:00` 记录 `Usage=ALARM` 的双段震动。
- Android 11 模拟器：设置页精确提醒状态显示“已就绪”，测试提醒按钮触发了系统震动；提示音开关可用。模拟器以 `-no-audio` 运行，未做可听性验证。
- Android 11 模拟器：未来目标 `11:53:00.000` 在设备重启前后均出现在系统 `Alarm clock` 队列，验证开机广播恢复调度。
- Android 16 小米手机已通过文件管理器更新到 `1.0.2`；系统 `SCHEDULE_EXACT_ALARM` 状态为 `allow`，目标 `12:00:00.000` 已在系统闹钟队列，`exactAllowReason=permission`。用户在设置页打开提示音并点击“测试提醒”，确认声音和震动均正常。
- Android Studio/Gradle 构建、发布签名、商店审核和多机型测试尚未完成。
