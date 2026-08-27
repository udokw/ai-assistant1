package com.example.aiapkanalyzer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingWidgetService : Service() {
    companion object {
        const val CHANNEL_ID = "floating_widget_channel"
        const val NOTIFICATION_ID = 1002
        const val ACTION_START = "action_start_floating"
        const val ACTION_STOP = "action_stop_floating"
        const val ACTION_APPLY_CONFIG = "action_apply_floating_config"

        private const val EDGE_MARGIN_DP = 8
        private const val AUTO_HIDE_DELAY = 3000L
        private const val CLICK_THRESHOLD_DP = 5
        private const val DOUBLE_CLICK_TIMEOUT = 400L

        private val ICON_OPTIONS = listOf(
            R.drawable.ic_launcher_foreground,
            R.drawable.ic_floating_light
        )

        fun start(context: Context) {
            val intent = Intent(context, FloatingWidgetService::class.java)
            intent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingWidgetService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }

        fun applyConfig(context: Context, config: FloatingBallConfig) {
            val intent = Intent(context, FloatingWidgetService::class.java)
            intent.action = ACTION_APPLY_CONFIG
            intent.putExtra("size_dp", config.sizeDp)
            intent.putExtra("normal_alpha", config.normalAlpha)
            intent.putExtra("hidden_alpha", config.hiddenAlpha)
            intent.putExtra("hidden_ratio", config.hiddenRatio)
            intent.putExtra("icon_index", config.iconIndex)
            intent.putExtra("custom_icon_path", config.customIconPath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var floatingView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    private var lastClickTime = 0L
    private var ballSizePx = 0
    private var edgeMarginPx = 0
    private var touchSlopPx = 0f
    private var snappedToEdge = false

    private var config: FloatingBallConfig = FloatingBallConfig()

    private val autoHideRunnable = Runnable {
        hideToEdge()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        config = AppConfig.load(this).floatingBall
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopFloatingWidget()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APPLY_CONFIG -> {
                intent?.let {
                    val sizeDp = it.getIntExtra("size_dp", config.sizeDp)
                    val normalAlpha = it.getFloatExtra("normal_alpha", config.normalAlpha)
                    val hiddenAlpha = it.getFloatExtra("hidden_alpha", config.hiddenAlpha)
                    val hiddenRatio = it.getFloatExtra("hidden_ratio", config.hiddenRatio)
                    val iconIndex = it.getIntExtra("icon_index", config.iconIndex)
                    val customIconPath = it.getStringExtra("custom_icon_path") ?: config.customIconPath
                    config = FloatingBallConfig(
                        sizeDp = sizeDp,
                        normalAlpha = normalAlpha,
                        hiddenAlpha = hiddenAlpha,
                        hiddenRatio = hiddenRatio,
                        iconIndex = iconIndex,
                        customIconPath = customIconPath
                    )
                    applyConfigToView()
                }
                return START_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        createFloatingBall()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun applyConfigToView() {
        val view = floatingView ?: return
        val params = layoutParams ?: return
        val wm = windowManager ?: return

        val displayMetrics = resources.displayMetrics
        val newBallSizePx = (config.sizeDp * displayMetrics.density).toInt()

        val currentCenterX = params.x + ballSizePx / 2
        val currentCenterY = params.y + ballSizePx / 2

        ballSizePx = newBallSizePx
        edgeMarginPx = (EDGE_MARGIN_DP * displayMetrics.density).toInt()

        params.width = ballSizePx
        params.height = ballSizePx

        params.x = (currentCenterX - ballSizePx / 2).toInt()
        params.y = (currentCenterY - ballSizePx / 2).toInt()

        updateViewAppearance(view)
        wm.updateViewLayout(view, params)
    }

    private fun updateViewAppearance(view: ImageView) {
        when (config.iconIndex) {
            0 -> view.setImageResource(R.drawable.ic_launcher_foreground)
            1 -> view.setImageResource(R.drawable.ic_floating_light)
            2 -> {
                if (config.customIconPath.isNotEmpty()) {
                    try {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(config.customIconPath)
                        if (bitmap != null) view.setImageBitmap(bitmap)
                        else view.setImageResource(R.drawable.ic_launcher_foreground)
                    } catch (e: Exception) {
                        view.setImageResource(R.drawable.ic_launcher_foreground)
                    }
                } else {
                    view.setImageResource(R.drawable.ic_launcher_foreground)
                }
            }
            else -> view.setImageResource(R.drawable.ic_launcher_foreground)
        }
        view.alpha = config.normalAlpha
    }

    @SuppressLint("InflateParams")
    private fun createFloatingBall() {
        if (floatingView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val displayMetrics = resources.displayMetrics
        ballSizePx = (config.sizeDp * displayMetrics.density).toInt()
        edgeMarginPx = (EDGE_MARGIN_DP * displayMetrics.density).toInt()
        touchSlopPx = CLICK_THRESHOLD_DP * displayMetrics.density

        floatingView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(
                (ballSizePx * 0.025).toInt(),
                (ballSizePx * 0.025).toInt(),
                (ballSizePx * 0.025).toInt(),
                (ballSizePx * 0.025).toInt()
            )
            alpha = config.normalAlpha
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#7C4DFF"))
                setStroke(3, android.graphics.Color.parseColor("#FFFFFF"))
            }
        }
        updateViewAppearance(floatingView!!)

        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        layoutParams = WindowManager.LayoutParams(
            ballSizePx,
            ballSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - ballSizePx - edgeMarginPx
            y = screenHeight / 4
        }

        setupTouchListener()

        try {
            windowManager!!.addView(floatingView, layoutParams)
            scheduleAutoHide()
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun setupTouchListener() {
        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchStartX = 0f
            private var touchStartY = 0f
            private var hasMoved = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val params = layoutParams ?: return false
                val wm = windowManager ?: return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        hasMoved = false
                        snappedToEdge = false
                        handler.removeCallbacks(autoHideRunnable)
                        showFullView()
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchStartX
                        val dy = event.rawY - touchStartY

                        if (!hasMoved && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
                            hasMoved = true
                        }

                        if (hasMoved) {
                            params.x = (startX + dx).toInt()
                            params.y = (startY + dy).toInt()
                            wm.updateViewLayout(floatingView, params)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!hasMoved) {
                            val now = System.currentTimeMillis()
                            if (now - lastClickTime < DOUBLE_CLICK_TIMEOUT) {
                                openMiniWindow()
                                lastClickTime = 0L
                            } else {
                                lastClickTime = now
                            }
                        }
                        snapToEdge()
                        scheduleAutoHide()
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        snapToEdge()
                        scheduleAutoHide()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun snapToEdge() {
        val params = layoutParams ?: return
        val wm = windowManager ?: return
        val view = floatingView ?: return

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val centerX = params.x + ballSizePx / 2

        params.x = if (centerX < screenWidth / 2) {
            edgeMarginPx
        } else {
            screenWidth - ballSizePx - edgeMarginPx
        }

        val maxY = screenHeight - ballSizePx - edgeMarginPx * 4
        params.y = max(edgeMarginPx * 2, min(params.y, maxY))

        wm.updateViewLayout(view, params)
        snappedToEdge = true
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
        handler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY)
    }

    private fun hideToEdge() {
        if (!snappedToEdge) return

        val params = layoutParams ?: return
        val wm = windowManager ?: return
        val view = floatingView ?: return

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels

        val centerX = params.x + ballSizePx / 2
        val isLeftSide = centerX < screenWidth / 2

        val hiddenPx = (ballSizePx * (1 - config.hiddenRatio)).toInt()

        params.x = if (isLeftSide) {
            -hiddenPx
        } else {
            screenWidth - (ballSizePx - hiddenPx)
        }

        wm.updateViewLayout(view, params)
        view.animate().alpha(config.hiddenAlpha).setDuration(300).start()
    }

    private fun showFullView() {
        val view = floatingView ?: return
        view.animate().alpha(config.normalAlpha).setDuration(150).start()
    }

    private fun openMiniWindow() {
        handler.removeCallbacks(autoHideRunnable)
        showFullView()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    private fun stopFloatingWidget() {
        handler.removeCallbacks(autoHideRunnable)
        try {
            floatingView?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {
        }
        floatingView = null
        layoutParams = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮球服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮球功能运行中"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, FloatingWidgetService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI 悬浮球")
            .setContentText("双击悬浮球打开 AI 助手")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .addAction(0, "停止", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFloatingWidget()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
