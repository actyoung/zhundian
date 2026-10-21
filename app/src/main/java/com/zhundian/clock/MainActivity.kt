package com.zhundian.clock

import android.app.Activity
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private enum class Screen { CLOCK, POSITION, APPEARANCE, SETTINGS }

    private val background = Color.rgb(11, 16, 14)
    private val surface = Color.rgb(18, 29, 20)
    private val lineColor = Color.rgb(75, 89, 76)
    private val white = Color.rgb(240, 244, 237)
    private val muted = Color.rgb(162, 177, 162)
    private val amber = Color.rgb(242, 196, 65)
    private val green = Color.rgb(145, 255, 103)
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    private val targetFormat = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val dateFormat = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
    private val date = Date()
    private val prefs by lazy { getSharedPreferences("clock", MODE_PRIVATE) }
    private lateinit var root: FrameLayout
    private var screen = Screen.CLOCK
    private var pendingOverlayStart = false
    private var targetMillis = 0L
    private var size = 1
    private var opacity = 65
    private var noBg = false
    private var safeMode = false
    private var vibrate = true
    private var sound = false
    private var clickCount = 0
    private var currentScroll: ScrollView? = null
    private var renderedScreen = Screen.CLOCK
    private val scrollPositions = mutableMapOf<Screen, Int>()
    private var clockDigits: SevenSegmentView? = null
    private var countdownDigits: SevenSegmentView? = null
    private var previewDigits: SevenSegmentView? = null
    private var countdownCaption: TextView? = null
    private var sourceLabel: TextView? = null
    private var calibrationDetails: TextView? = null
    private var lastReachedTarget = 0L
    private var lastStatusSecond = -1L
    private val ticker = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            updateClock()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TimeAuthority.initialize(this)
        window.statusBarColor = background
        window.navigationBarColor = Color.rgb(10, 14, 12)
        window.decorView.systemUiVisibility = 0
        loadConfig()
        root = FrameLayout(this)
        root.setBackgroundColor(background)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(0, top, 0, bottom)
            insets
        }
        setContentView(root)
        OverlayService.setOverlayPageVisible(false)
        render()
    }

    override fun onResume() {
        super.onResume()
        OverlayService.setAppVisible(true)
        if (pendingOverlayStart && Settings.canDrawOverlays(this)) {
            pendingOverlayStart = false
            launchOverlay()
        }
        ReminderScheduler.schedule(this)
        TimeAuthority.syncIfDue(applicationContext, false)
        render()
        Choreographer.getInstance().removeFrameCallback(ticker)
        Choreographer.getInstance().postFrameCallback(ticker)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(ticker)
        OverlayService.setAppVisible(false)
        super.onPause()
    }

    private fun loadConfig() {
        targetMillis = prefs.getLong("targetMillis", 0)
        if (targetMillis < TimeAuthority.nowMillis() - 60_000L) {
            targetMillis = nextHour()
            prefs.edit().putLong("targetMillis", targetMillis).apply()
        }
        size = prefs.getInt("size", 1).coerceIn(0, 2)
        opacity = prefs.getInt("opacity", 65).coerceIn(0, 100)
        noBg = prefs.getBoolean("noBg", false)
        safeMode = prefs.getBoolean("safeMode", false)
        vibrate = prefs.getBoolean("vibrate", true)
        sound = prefs.getBoolean("sound", false)
    }

    private fun nextHour(): Long = Calendar.getInstance().apply {
        timeInMillis = TimeAuthority.nowMillis()
        add(Calendar.HOUR_OF_DAY, 1)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun render() {
        currentScroll?.let { scrollPositions[renderedScreen] = it.scrollY }
        clockDigits = null
        countdownDigits = null
        previewDigits = null
        countdownCaption = null
        sourceLabel = null
        calibrationDetails = null
        lastStatusSecond = -1
        root.removeAllViews()
        root.background = gridBackground()

        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        currentScroll = scroll
        renderedScreen = screen
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(23), dp(12), dp(23), dp(22))
        }
        scroll.addView(content)
        when (screen) {
            Screen.CLOCK -> renderClock(content)
            Screen.POSITION -> renderPosition(content)
            Screen.APPEARANCE -> renderAppearance(content)
            Screen.SETTINGS -> renderSettings(content)
        }
        shell.addView(bottomNav())
        OverlayService.setOverlayPageVisible(screen == Screen.POSITION)
        updateClock()
        scroll.post { scroll.scrollTo(0, scrollPositions[screen] ?: 0) }
    }

    private fun gridBackground(): BitmapDrawable {
        val bitmap = assets.open("clock-grid.png").use { BitmapFactory.decodeStream(it) }
        return BitmapDrawable(resources, bitmap).apply {
            setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        }
    }

    private fun renderClock(parent: LinearLayout) {
        header(parent, "准点", dateFormat.format(Date(TimeAuthority.nowMillis())), android.R.drawable.ic_menu_preferences) {
            navigate(Screen.SETTINGS)
        }
        space(parent, 9)
        section(parent, "当前时间")
        space(parent, 6)
        val available = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 46f
        clockDigits = SevenSegmentView(this).apply { setDisplayScale(min(1.9f, available / 166f)) }
        parent.addView(clockDigits)
        space(parent, 3)
        sourceLabel = text("●  ${TimeAuthority.status()}", 12f, muted).apply {
            setOnClickListener { TimeAuthority.syncIfDue(applicationContext, true) }
        }
        parent.addView(sourceLabel)
        space(parent, 10)
        divider(parent)
        space(parent, 12)

        val title = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        parent.addView(title, LinearLayout.LayoutParams(-1, dp(35)))
        title.addView(sectionLabel("目标时刻"), LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(text("下一个整点", 13f, amber, true).apply {
            setPadding(dp(7), dp(8), 0, dp(8))
            setOnClickListener { setTarget(nextHour(), "已设为下一个整点") }
        })
        val targetRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(4))
            setOnClickListener { showTargetDialog() }
        }
        parent.addView(targetRow)
        targetRow.addView(text(targetFormat.format(Date(targetMillis)), 39f, white, true))
        targetRow.addView(text(targetDay(), 13f, muted).apply { setPadding(dp(11), 0, 0, 0) })
        targetRow.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        targetRow.addView(text("›", 33f, amber))
        divider(parent)
        space(parent, 8)
        section(parent, "距离目标")
        space(parent, 7)
        countdownDigits = SevenSegmentView(this).apply { setDisplayScale(min(1.55f, available / 166f)) }
        parent.addView(countdownDigits)
        countdownCaption = text("", 12f, muted).also { parent.addView(it) }
        space(parent, 8)
        divider(parent)

        if (!ReminderScheduler.canScheduleExact(this) && (vibrate || sound) && targetMillis > TimeAuthority.nowMillis()) {
            space(parent, 14)
            val notice = action("允许精确提醒，到点才能在后台提醒", true) { requestExactAlarm() }
            notice.background = shape(Color.rgb(50, 39, 26), Color.rgb(165, 109, 55))
            notice.setTextColor(Color.rgb(246, 214, 142))
            parent.addView(notice)
        }
        space(parent, 12)
        parent.addView(action(if (OverlayService.isRunning()) "查看悬浮时钟" else "开启悬浮时钟") {
            if (OverlayService.isRunning()) navigate(Screen.POSITION) else requestOverlayStart()
        })
        space(parent, 12)
        val status = when {
            !vibrate && !sound -> "到点提醒未开启"
            ReminderScheduler.isArmed(this) -> "到点提醒已就绪"
            targetMillis <= TimeAuthority.nowMillis() -> "目标时间已到"
            else -> "到点提醒待授权"
        }
        parent.addView(text(status, 12f, muted).apply { gravity = Gravity.CENTER })
    }

    private fun renderPosition(parent: LinearLayout) {
        header(parent, "悬浮位置", if (safeMode && OverlayService.isRunning()) "关闭防误触后可拖动时钟" else "拖动时钟，调整它在屏幕上的位置",
            android.R.drawable.ic_menu_manage) { navigate(Screen.APPEARANCE) }
        space(parent, 10)
        val running = OverlayService.isRunning()
        val area = FrameLayout(this)
        val areaHeight = min((resources.displayMetrics.heightPixels / resources.displayMetrics.density * .43f).toInt(), 400)
        parent.addView(area, LinearLayout.LayoutParams(-1, dp(max(220, areaHeight))))
        val test = action(if (clickCount == 0) "测试点击" else "已点击 $clickCount 次", false) {
            clickCount++
            (it as Button).text = "已点击 $clickCount 次"
        }
        test.isEnabled = running
        if (!running) test.alpha = .5f
        area.addView(test, FrameLayout.LayoutParams(dp(150), dp(46), Gravity.CENTER))
        parent.addView(text(if (running) "●  ${if (safeMode) "运行中 · 已锁定并穿透触摸" else "运行中 · 可拖动调整位置"}" else "●  未开启", 12f,
            if (running) green else amber))
        space(parent, 13)
        parent.addView(action(if (safeMode) "防误触已开启" else "开启防误触", running) {
            safeMode = !safeMode
            saveVisualConfig()
            render()
        }.apply { isEnabled = running; if (!running) alpha = .5f })
        space(parent, 12)
        parent.addView(action(if (running) "关闭悬浮时钟" else "开启悬浮时钟", false) {
            if (running) {
                stopService(Intent(this, OverlayService::class.java))
                handler.postDelayed({ render() }, 180)
            } else requestOverlayStart()
        })
    }

    private fun renderAppearance(parent: LinearLayout) {
        header(parent, "外观", "悬浮时钟的显示样式")
        space(parent, 20)
        val previewBand = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(45, 61, 48))
        }
        parent.addView(previewBand, LinearLayout.LayoutParams(-1, dp(160)))
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(8))
        }
        previewBand.addView(preview, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        preview.addView(text("准点 · 校准时间", 10f, muted, true))
        previewDigits = SevenSegmentView(this).apply {
            setSize(size)
            setDisplayScale(1.08f)
        }
        preview.addView(previewDigits)
        updatePreviewBackground(preview)
        space(parent, 22)

        section(parent, "尺寸")
        val sizeLine = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        parent.addView(sizeLine)
        val sizeValue = text(arrayOf("小", "标准", "大")[size], 13f, white)
        val sizeBar = slider(2, size) {
            size = it
            sizeValue.text = arrayOf("小", "标准", "大")[size]
            previewDigits?.setSize(size)
            saveVisualConfig()
        }
        sizeLine.addView(sizeBar, LinearLayout.LayoutParams(0, dp(48), 1f))
        sizeLine.addView(sizeValue, LinearLayout.LayoutParams(dp(44), -2))
        space(parent, 16)

        section(parent, "底色浓度")
        val alphaLine = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        parent.addView(alphaLine)
        val opacityValue = text("$opacity%", 13f, white)
        val opacityBar = slider(20, opacity / 5) {
            opacity = it * 5
            opacityValue.text = "$opacity%"
            updatePreviewBackground(preview)
            saveVisualConfig()
        }
        alphaLine.addView(opacityBar, LinearLayout.LayoutParams(0, dp(48), 1f))
        alphaLine.addView(opacityValue, LinearLayout.LayoutParams(dp(44), -2))
        space(parent, 9)
        settingSwitch(parent, "隐藏背景", "仅保留荧光绿数字", noBg) {
            noBg = it
            updatePreviewBackground(preview)
            saveVisualConfig()
        }
        settingSwitch(parent, "防误触", "锁定位置，同时让触摸穿过时钟", safeMode) {
            safeMode = it
            updatePreviewBackground(preview)
            saveVisualConfig()
        }
    }

    private fun updatePreviewBackground(preview: View) {
        preview.background = shape(if (noBg) Color.TRANSPARENT else Color.argb(opacity * 255 / 100, 9, 22, 12))
        preview.alpha = if (safeMode) .79f else 1f
    }

    private fun renderSettings(parent: LinearLayout) {
        header(parent, "设置", "提醒与悬浮窗管理")
        space(parent, 21)
        section(parent, "时间校准")
        space(parent, 10)
        calibrationDetails = text(TimeAuthority.details(), 12f, muted).also { parent.addView(it) }
        space(parent, 10)
        parent.addView(action("立即校准", false) {
            TimeAuthority.syncIfDue(applicationContext, true)
            calibrationDetails?.text = TimeAuthority.details()
        })
        space(parent, 24)
        section(parent, "到点提醒")
        space(parent, 12)
        settingSwitch(parent, "到点震动", "目标时刻触发一次", vibrate) {
            vibrate = it
            saveReminderConfig()
        }
        settingSwitch(parent, "到点提示音", "使用系统闹钟音量", sound) {
            sound = it
            saveReminderConfig()
        }
        settingLink(parent, "精确提醒权限",
            if (ReminderScheduler.canScheduleExact(this)) {
                if (ReminderScheduler.isArmed(this)) "已就绪" else "已授权"
            } else "未授权", android.R.drawable.ic_menu_recent_history) { requestExactAlarm() }
        space(parent, 12)
        parent.addView(action("测试提醒", false) {
            Thread({ ReminderScheduler.test(applicationContext) }, "reminder-preview").start()
            toast("已触发测试提醒")
        }.apply { isEnabled = vibrate || sound })
        space(parent, 29)
        section(parent, "悬浮窗")
        space(parent, 12)
        settingLink(parent, "显示在其他应用上层", if (Settings.canDrawOverlays(this)) "已授权" else "未授权",
            android.R.drawable.ic_menu_view) { requestOverlayPermission(false) }
        settingLink(parent, "时钟外观", "", android.R.drawable.ic_menu_manage) { navigate(Screen.APPEARANCE) }
        settingLink(parent, "悬浮位置", "", android.R.drawable.ic_menu_compass) { navigate(Screen.POSITION) }
    }

    private fun showTargetDialog() {
        val selected = Calendar.getInstance().apply { timeInMillis = targetMillis }
        val dialog = Dialog(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(23), dp(19), dp(23), dp(27))
            background = shape(Color.rgb(22, 31, 24), lineColor, 8f)
        }
        body.addView(text("设置目标时刻", 21f, white, true))
        space(body, 11)
        body.addView(text("目标按分钟设置，到点秒数为 00；倒计时显示到毫秒。", 13f, muted))
        space(body, 19)
        val quick = LinearLayout(this)
        body.addView(quick)
        quick.addView(action("下一个整点", false) {
            dialog.dismiss()
            setTarget(nextHour(), "已设为下一个整点")
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        quick.addView(Space(this), LinearLayout.LayoutParams(dp(8), 1))
        quick.addView(action("约 5 分钟后", false) {
            val candidate = Calendar.getInstance().apply {
                timeInMillis = TimeAuthority.nowMillis()
                add(Calendar.MINUTE, 5)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            dialog.dismiss()
            setTarget(candidate, "目标时间已更新")
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        space(body, 18)
        body.addView(text("日期", 12f, muted, true))
        val dateButton = action(SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(selected.time), false) {}
        body.addView(dateButton)
        dateButton.setOnClickListener {
            DatePickerDialog(this, { _, year, month, day ->
                selected.set(year, month, day)
                dateButton.text = SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(selected.time)
            }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.minDate = TimeAuthority.nowMillis() - 86_400_000L
            }.show()
        }
        space(body, 13)
        body.addView(text("时间", 12f, muted, true))
        val timeButton = action(targetFormat.format(selected.time), false) {}
        body.addView(timeButton)
        timeButton.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                selected.set(Calendar.HOUR_OF_DAY, hour)
                selected.set(Calendar.MINUTE, minute)
                timeButton.text = targetFormat.format(selected.time)
            }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), true).show()
        }
        space(body, 20)
        body.addView(action("保存目标时间") {
            selected.set(Calendar.SECOND, 0)
            selected.set(Calendar.MILLISECOND, 0)
            if (selected.timeInMillis <= TimeAuthority.nowMillis()) {
                toast("请选择未来的时间")
            } else {
                dialog.dismiss()
                setTarget(selected.timeInMillis, "目标时间已更新")
            }
        })
        dialog.setContentView(body)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = .65f }
        }
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun setTarget(value: Long, message: String) {
        targetMillis = value
        lastReachedTarget = 0
        prefs.edit().putLong("targetMillis", value).commit()
        ReminderScheduler.schedule(this)
        TimeAuthority.syncIfDue(applicationContext, false)
        render()
        toast(message)
    }

    private fun saveVisualConfig() {
        prefs.edit().putInt("size", size).putInt("opacity", opacity)
            .putBoolean("noBg", noBg).putBoolean("safeMode", safeMode).apply()
        if (OverlayService.isRunning()) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REFRESH))
        }
    }

    private fun saveReminderConfig() {
        prefs.edit().putBoolean("vibrate", vibrate).putBoolean("sound", sound).commit()
        ReminderScheduler.schedule(this)
        render()
    }

    private fun requestOverlayStart() {
        if (Settings.canDrawOverlays(this)) launchOverlay() else requestOverlayPermission(true)
    }

    private fun requestOverlayPermission(startAfterGrant: Boolean) {
        if (Settings.canDrawOverlays(this)) {
            if (startAfterGrant) launchOverlay() else toast("悬浮窗权限已授权")
            return
        }
        pendingOverlayStart = startAfterGrant
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try { startActivity(intent) } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun launchOverlay() {
        try {
            val intent = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            handler.postDelayed({ navigate(Screen.POSITION) }, 500)
        } catch (_: Exception) { toast("悬浮窗启动失败，请重试") }
    }

    private fun requestExactAlarm() {
        if (ReminderScheduler.canScheduleExact(this)) {
            toast("精确提醒权限已授权")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        } catch (_: Exception) { toast("无法打开精确提醒授权页面") }
    }

    private fun updateClock() {
        val now = TimeAuthority.nowMillis()
        if (now / 1000 != lastStatusSecond) {
            lastStatusSecond = now / 1000
            sourceLabel?.text = "●  ${TimeAuthority.status()}"
            calibrationDetails?.text = TimeAuthority.details()
            TimeAuthority.syncIfDue(applicationContext, false)
            val highRefresh = targetMillis - now in 1L..120_000L
            clockDigits?.setHighRefresh(highRefresh)
            countdownDigits?.setHighRefresh(highRefresh)
        }
        if (clockDigits == null && previewDigits == null && countdownDigits == null) return
        date.time = now
        val value = timeFormat.format(date)
        clockDigits?.setTime(value)
        previewDigits?.setTime(value)
        val remaining = max(0L, targetMillis - now)
        val hours = remaining / 3_600_000L
        val minutes = remaining % 3_600_000L / 60_000L
        val seconds = remaining % 60_000L / 1000L
        val millis = remaining % 1000L
        countdownDigits?.setTime(String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hours % 24, minutes, seconds, millis))
        countdownCaption?.apply {
            text = "还有 ${hours / 24} 天"
            visibility = if (hours >= 24) View.VISIBLE else View.GONE
        }
        if (screen == Screen.CLOCK && remaining == 0L && lastReachedTarget != targetMillis) {
            lastReachedTarget = targetMillis
            toast("目标时间已到")
        }
    }

    private fun navigate(destination: Screen) {
        screen = destination
        render()
    }

    private fun targetDay(): String {
        val target = Calendar.getInstance().apply { timeInMillis = targetMillis }
        val today = Calendar.getInstance().apply { timeInMillis = TimeAuthority.nowMillis() }
        if (target.get(Calendar.YEAR) == today.get(Calendar.YEAR) && target.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) return "今天"
        today.add(Calendar.DAY_OF_YEAR, 1)
        if (target.get(Calendar.YEAR) == today.get(Calendar.YEAR) && target.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) return "明天"
        return "${target.get(Calendar.MONTH) + 1}月${target.get(Calendar.DAY_OF_MONTH)}日"
    }

    private fun bottomNav(): LinearLayout {
        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(10, 14, 12))
        }
        for ((page, name, icon) in listOf(
            Triple(Screen.CLOCK, "时钟", android.R.drawable.ic_menu_recent_history),
            Triple(Screen.POSITION, "悬浮", android.R.drawable.ic_menu_compass),
            Triple(Screen.APPEARANCE, "外观", android.R.drawable.ic_menu_manage)
        )) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { navigate(page) }
            }
            val color = if (screen == page) amber else muted
            item.addView(icon(icon, color), LinearLayout.LayoutParams(dp(22), dp(22)))
            space(item, 4)
            item.addView(text(name, 11f, color, screen == page).apply { gravity = Gravity.CENTER })
            nav.addView(item, LinearLayout.LayoutParams(0, dp(68), 1f))
        }
        return nav
    }

    private fun header(parent: LinearLayout, title: String, subtitle: String, iconId: Int? = null, click: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        parent.addView(row, LinearLayout.LayoutParams(-1, dp(70)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(title, 28f, white, true))
        labels.addView(text(subtitle, 12f, muted))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        if (iconId != null) {
            row.addView(icon(iconId, white).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { click?.invoke() }
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
        }
    }

    private fun section(parent: LinearLayout, title: String) { parent.addView(sectionLabel(title)) }

    private fun sectionLabel(title: String): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(View(this@MainActivity).apply { setBackgroundColor(amber) }, LinearLayout.LayoutParams(dp(4), dp(17)))
        addView(text(title, 14f, white, true).apply { setPadding(dp(10), 0, 0, 0) })
    }

    private fun settingSwitch(parent: LinearLayout, title: String, detail: String, checked: Boolean, changed: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        parent.addView(row, LinearLayout.LayoutParams(-1, dp(68)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(title, 14f, white, true))
        labels.addView(text(detail, 11f, muted))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this).apply {
            isChecked = checked
            showText = false
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(amber, Color.rgb(75, 91, 76)))
            setOnCheckedChangeListener { _, value -> changed(value) }
        }
        row.addView(toggle)
        row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        divider(parent)
    }

    private fun settingLink(parent: LinearLayout, title: String, status: String, iconId: Int, click: () -> Unit) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { click() }
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, dp(60)))
        row.addView(icon(iconId, amber), LinearLayout.LayoutParams(dp(23), dp(23)))
        row.addView(text(title, 14f, white).apply { setPadding(dp(13), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (status.isNotEmpty()) row.addView(text(status, 12f, muted))
        row.addView(text("›", 25f, muted).apply { setPadding(dp(12), 0, 0, 0) })
        divider(parent)
    }

    private fun slider(maximum: Int, value: Int, changed: (Int) -> Unit): SeekBar = SeekBar(this).apply {
        max = maximum
        progress = value
        progressTintList = ColorStateList.valueOf(amber)
        thumbTintList = ColorStateList.valueOf(amber)
        progressBackgroundTintList = ColorStateList.valueOf(lineColor)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) changed(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    private fun action(label: String, primary: Boolean = true, click: (View) -> Unit): Button = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        setTextColor(white)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = if (primary) shape(Color.rgb(12, 43, 21), green) else shape(surface, lineColor)
        minHeight = dp(49)
        setOnClickListener(click)
    }

    private fun icon(resource: Int, color: Int): ImageView = ImageView(this).apply {
        setImageResource(resource)
        imageTintList = ColorStateList.valueOf(color)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = true
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun shape(fill: Int, stroke: Int? = null, radius: Float = 5f): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(lineColor) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun space(parent: LinearLayout, height: Int) {
        parent.addView(Space(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
}
