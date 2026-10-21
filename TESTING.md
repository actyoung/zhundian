# 验证记录

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
