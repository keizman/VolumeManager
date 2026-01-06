package moe.chensi.volume

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback
import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.CountDownTimer
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import moe.chensi.volume.ui.theme.VolumeManagerTheme
import org.joor.Reflect
import java.util.Objects

@SuppressLint("AccessibilityPolicy")
class Service : AccessibilityService() {
    companion object {
        const val ACTION_SHOW_VIEW = "moe.chensi.volume.ACTION_SHOW_VIEW"
        const val ACTION_OVERLAY_SETTINGS_CHANGED = "moe.chensi.volume.OVERLAY_SETTINGS_CHANGED"

        private const val TAG = "VolumeManager.Service"

        private const val ANIMATION_DURATION = 300L
        private const val IDLE_TIMEOUT = 30000L  // 30 seconds for testing lyrics display
    }

    private val windowManager: WindowManager by lazy {
        Objects.requireNonNull(
            getSystemService(
                WindowManager::class.java
            )!!
        )
    }
    private lateinit var manager: Manager

    // Volume states for instant UI updates
    private var musicVolume by mutableIntStateOf(0)
    private var notificationVolume by mutableIntStateOf(0)

    // Coroutine scope for volume polling during long press
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var volumePollingJob: Job? = null

    // Test mode for lyrics display verification
    private var testLyricsMode by mutableStateOf(false)
    
    // Real-time lyrics captured from floating windows or YouTube subtitles
    private var currentLyrics by mutableStateOf("等待字幕...")
    
    // YouTube字幕管理器
    private lateinit var subtitleManager: YouTubeSubtitleManager
    
    // YouTube播放状态
    private var currentYouTubeVideoId: String? = null
    private var currentSubtitles = listOf<YouTubeSubtitleManager.SubtitleEntry>()
    private var youtubePlaybackPosition = 0L
    private var youtubePlaybackState = android.media.session.PlaybackState.STATE_NONE
    private var youtubeLastPositionUpdateTime = 0L
    private var youtubePlaybackSpeed = 1.0f
    
    // 字幕队列 (滚动显示)
    private var subtitleQueue = mutableStateListOf<String>()
    private val MAX_LINES = 3
    private val MAX_LINE_CHARS = 120
    // Trigger immediate recomposition when overlay style settings change
    private var overlayStyleNonce by mutableIntStateOf(0)
    
    // Track key sequence: Volume Up, Down, Down (+ - -)
    private val keySequence = mutableListOf<Int>()
    private var lastKeyPressTime = 0L
    private val sequenceTimeout = 2000L // 2 seconds to complete sequence
    private val targetSequence = listOf(
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_DOWN
    )

    private var volumeIdleTimer: CountDownTimer? = null  // 音量面板定时器（固定3秒）
    private var lyricsIdleTimer: CountDownTimer? = null  // 字幕面板定时器（可配置）
    private var youtubeTickerJob: Job? = null
    // Volume key handling to avoid double-step on single tap
    private var volumeStartDelayJob: Job? = null
    private var isVolumePressActive: Boolean = false
    private var pollingStartedForCurrentPress: Boolean = false
    private var currentPressDirection: Int? = null

    // Overlay settings storage (仅用于字幕面板)
    private val overlayPrefs by lazy { getSharedPreferences("overlay_settings", Context.MODE_PRIVATE) }
    private fun isLyricsOverlaySticky(): Boolean = overlayPrefs.getBoolean("overlay_sticky", false)
    private fun isTextOnlyOverlay(): Boolean = overlayPrefs.getBoolean("overlay_text_only", true)
    private fun overlayFontSp(): Float {
        return try { overlayPrefs.getFloat("overlay_font_sp", 24f) } catch (_: Throwable) { 24f }
    }
    private fun overlayTextColor(): androidx.compose.ui.graphics.Color {
        val hex = overlayPrefs.getString("overlay_text_color", "#00D9FF") ?: "#00D9FF"
        return try {
            val parsed = android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
            androidx.compose.ui.graphics.Color(parsed)
        } catch (_: Throwable) {
            androidx.compose.ui.graphics.Color(0xFF00D9FF)
        }
    }
    private fun overlayLineHeightSp(fontSp: Float): Float = (fontSp * 1.42f)
    private fun lyricsOverlayHideTimeoutMs(): Long {
        val sec = overlayPrefs.getInt("overlay_hide_timeout_sec", 30)
        return (if (sec < 0) 0 else sec) * 1000L
    }
    private fun overlayLatencyMs(): Long {
        return try {
            val sec = overlayPrefs.getFloat("overlay_latency_sec", 1.0f)
            val ms = (sec * 1000f).toLong()
            if (ms < 0) 0 else ms
        } catch (_: Throwable) { 1000L }
    }

        private fun checkKeySequence(keyCode: Int) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastKeyPressTime > sequenceTimeout) {
            keySequence.clear()
        }
        keySequence.add(keyCode)
        lastKeyPressTime = currentTime

        val seqStr = keySequence.joinToString(",")
        Log.i(TAG, "🎬YT [KEY] Sequence: $seqStr")

        if (keySequence.size > 3) {
            keySequence.removeAt(0)
        }
        if (keySequence == targetSequence) {
            testLyricsMode = !testLyricsMode
            Log.i(TAG, "🎬YT [KEY] Toggled testLyricsMode=$testLyricsMode (matched + - -); viewVisible=$viewVisible")
            Toast.makeText(this, "歌词测试模式: ${if (testLyricsMode) "开启" else "关闭"}", Toast.LENGTH_SHORT).show()
            keySequence.clear()
            if (testLyricsMode) {
                showView()
                Log.i(TAG, "🎬YT [KEY] Forcing idle timer restart after toggle (lyrics mode)")
                startIdleTimer()
                // 兜底：若已有记录的 videoId，立即加载字幕
                try {
                    val lastId = MyApplication.lastYouTubeVideoId
                    Log.i(TAG, "🎬YT [KEY] lastId on toggle = $lastId, currentId=$currentYouTubeVideoId")
                    if (!lastId.isNullOrBlank() && lastId != currentYouTubeVideoId) {
                        currentYouTubeVideoId = lastId
                        subtitleQueue.clear()
                        serviceScope.launch { loadYouTubeSubtitles(lastId) }
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun startVolumePolling(adjustDirection: Int) {
        // Cancel any existing polling job
        volumePollingJob?.cancel()
        
        // Start continuous volume monitoring and adjustment during long press
        volumePollingJob = serviceScope.launch {
            while (isActive) {
                // Continuously adjust volume while key is pressed
                manager.audioManager.adjustSuggestedStreamVolume(
                    adjustDirection, AudioManager.USE_DEFAULT_STREAM_TYPE, 0
                )
                
                // Immediately read and update UI
                val currentMusicVolume = manager.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val currentNotificationVolume = manager.audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                
                if (currentMusicVolume != musicVolume || currentNotificationVolume != notificationVolume) {
                    Log.i(TAG, "Volume polling: music=$currentMusicVolume, notification=$currentNotificationVolume")
                    musicVolume = currentMusicVolume
                    notificationVolume = currentNotificationVolume
                }
                
                // Adjust every 100ms for smooth continuous change
                delay(100)
            }
        }
    }
    
    private fun stopVolumePolling() {
        volumePollingJob?.cancel()
        volumePollingJob = null
    }

    /**
     * 启动空闲定时器
     * - 音量面板：固定3秒自动消失
     * - 字幕面板：使用设置的时间（默认30秒）或不消失
     */
    private fun startIdleTimer() {
        // 先统一取消两个计时器，避免模式切换时老计时器误触发（导致3秒就隐藏）
        if (volumeIdleTimer != null || lyricsIdleTimer != null) {
            Log.d(TAG, "IdleTimer: cancel existing timers (vol=${volumeIdleTimer!=null}, lyr=${lyricsIdleTimer!=null})")
        }
        volumeIdleTimer?.cancel()
        lyricsIdleTimer?.cancel()
        volumeIdleTimer = null
        lyricsIdleTimer = null

        if (testLyricsMode) {
            // 字幕面板：使用可配置的定时器
            if (isLyricsOverlaySticky()) {
                // Sticky模式：仅手动关闭
                Log.d(TAG, "IdleTimer: lyrics sticky mode -> no auto-hide")
                return
            }
            val timeout = lyricsOverlayHideTimeoutMs()
            Log.d(TAG, "IdleTimer: start lyrics timer ${timeout}ms")
            lyricsIdleTimer = object : CountDownTimer(timeout, timeout) {
                override fun onTick(millisUntilFinished: Long) {}
                override fun onFinish() {
                    Log.d(TAG, "IdleTimer: lyrics timer expired -> hideView")
                    hideView()
                }
            }.start()
        } else {
            // 音量面板：固定3秒
            Log.d(TAG, "IdleTimer: start volume timer 3000ms")
            volumeIdleTimer = object : CountDownTimer(3000L, 3000L) {
                override fun onTick(millisUntilFinished: Long) {}
                override fun onFinish() {
                    Log.d(TAG, "IdleTimer: volume timer expired -> hideView")
                    hideView()
                }
            }.start()
        }
    }

    private var lastTickerEligible: Boolean? = null
    private fun startYouTubeTicker() {
        youtubeTickerJob?.cancel()
        youtubeTickerJob = serviceScope.launch {
            while (isActive) {
                val canUpdate = (youtubePlaybackState == android.media.session.PlaybackState.STATE_PLAYING
                        && currentYouTubeVideoId != null && currentSubtitles.isNotEmpty())
                if (lastTickerEligible != canUpdate) {
                    Log.d(
                        TAG,
                        "🎬YT [TICK] eligible=$canUpdate state=$youtubePlaybackState idSet=${currentYouTubeVideoId != null} subs=${currentSubtitles.size} test=$testLyricsMode latencyMs=${overlayLatencyMs()}"
                    )
                    lastTickerEligible = canUpdate
                }
                // 播放中按插值更新字幕
                if (canUpdate) {
                    updateYouTubeSubtitle()
                }
                delay(250)
            }
        }
    }

    private var lifecycle: LifecycleRegistry? = null

    private fun createView(): View {
        return object : AbstractComposeView(this) {
            init {
                val owner = object : SavedStateRegistryOwner {
                    private val lifecycleRegistry = LifecycleRegistry(this)

                    private val savedStateRegistryController =
                        SavedStateRegistryController.create(this)

                    init {
                        savedStateRegistryController.performRestore(null)
                        lifecycleRegistry.currentState = Lifecycle.State.STARTED
                        this@Service.lifecycle = lifecycleRegistry
                    }

                    override val lifecycle: Lifecycle
                        get() = lifecycleRegistry

                    override val savedStateRegistry: SavedStateRegistry
                        get() = savedStateRegistryController.savedStateRegistry
                }

                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
            }

            override fun onAttachedToWindow() {
                super.onAttachedToWindow()

                Log.i(TAG, "onAttachedToWindow")

                // 在文字模式下不创建模糊背景，保持底层像素不变
                if (!isTextOnlyOverlay()) {
                    if (windowManager.isCrossWindowBlurEnabled && isHardwareAccelerated) {
                        background =
                            Reflect.on(rootSurfaceControl).call("createBackgroundBlurDrawable").apply {
                                call("setBlurRadius", 200)
                                call("setCornerRadius", 40f)
                            }.get()
                    }
                } else {
                    background = null
                }

                startIdleTimer()
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                Log.i(TAG, "onTouchEvent ${event.actionMasked}")

                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    // hideView() will automatically exit test mode
                    hideView()
                    return true
                }

                return super.onTouchEvent(event)
            }

            @Composable
            override fun Content() {
                // Initialize volumes on first composition
                LaunchedEffect(Unit) {
                    musicVolume = manager.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    notificationVolume = manager.audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                }

                DisposableEffect(manager.audioManager) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            // Update volumes when broadcast received (backup mechanism)
                            musicVolume = manager.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            notificationVolume = manager.audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                            startIdleTimer()
                        }
                    }

                    registerReceiver(receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))

                    onDispose {
                        unregisterReceiver(receiver)
                    }
                }

                return VolumeManagerTheme {
                    Surface(
                        color = Color.Transparent,
                        contentColor = Color.White,
                    ) {
                        if (testLyricsMode) {
                            // Test mode: Display scrolling lyrics simulation
                            TestLyricsDisplay()
                        } else {
                            // Normal mode: Volume control
                            Column(
                                modifier = Modifier
                                    .background(
                                        Color(1f, 1f, 1f, 0.3f), RoundedCornerShape(40f)
                                    )
                                    .padding(20.dp, 16.dp)
                            ) {
                                AppVolumeList(
                                    manager.apps.values,
                                    showAll = false,
                                    onChange = { startIdleTimer() }) {
                                    item(AudioManager.STREAM_MUSIC) {
                                        StreamVolumeSlider(
                                            AudioManager.STREAM_MUSIC,
                                            musicVolume,
                                            Icons.Default.MusicNote,
                                            "Music",
                                            onVolumeUpdate = { newVolume -> musicVolume = newVolume },
                                            onChange = { startIdleTimer() })
                                    }

                                    item(AudioManager.STREAM_NOTIFICATION) {
                                        StreamVolumeSlider(
                                            AudioManager.STREAM_NOTIFICATION,
                                            notificationVolume,
                                            Icons.Default.Notifications,
                                            "Notifications",
                                            onVolumeUpdate = { newVolume -> notificationVolume = newVolume },
                                            onChange = { startIdleTimer() })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private val layoutParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, // Width
            WindowManager.LayoutParams.WRAP_CONTENT, // Height
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            // Only show on lock screen, don't wake or keep screen on
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT // Make the background translucent
        ).apply {
            gravity = Gravity.CENTER // Center the view
        }
    }

    private var view: View? = null
    private var viewVisible = false

    private fun showView() {
        if (view == null) {
            Log.i(TAG, "add view")
            // The view doesn't respond to input events if reused
            view = createView()
            layoutParams.alpha = 0f
            windowManager.addView(view, layoutParams)
        }

        if (!viewVisible) {
            Log.i(TAG, "animate in")
            animateAlpha(layoutParams.alpha, 1f, ANIMATION_DURATION)
            startIdleTimer()
            // 打印当前面板配置，便于确认生效
            Log.i(
                TAG,
                "🎬YT [UI] showView cfg: sticky=${isLyricsOverlaySticky()}, timeoutMs=${lyricsOverlayHideTimeoutMs()}, latencyMs=${overlayLatencyMs()}, maxLines=$MAX_LINES, maxLineChars=$MAX_LINE_CHARS, fontSp=${overlayFontSp()}, lineHeightSp=${overlayLineHeightSp(overlayFontSp())}, textColor=${overlayPrefs.getString("overlay_text_color", "#00D9FF")}, textOnly=${isTextOnlyOverlay()}"
            )
            viewVisible = true
        }
    }

    private fun hideView() {
        if (viewVisible) {
            Log.i(TAG, "animate out")
            
            // 取消所有定时器
            volumeIdleTimer?.cancel()
            lyricsIdleTimer?.cancel()
            
            animateAlpha(layoutParams.alpha, 0f, ANIMATION_DURATION) {
                if (!viewVisible) {
                    Log.i(TAG, "remove view")
                    view!!.background = null
                    lifecycle?.currentState = Lifecycle.State.DESTROYED
                    windowManager.removeView(view)
                    view = null
                }
            }
            viewVisible = false
            
            // Exit test lyrics mode when view is hidden (timeout or dismissed)
            if (testLyricsMode) {
                testLyricsMode = false
                Log.i(TAG, "Exiting test lyrics mode (view hidden)")
            }
        }
    }

    private var currentAnimator: ValueAnimator? = null

    private fun animateAlpha(from: Float, to: Float, duration: Long, onEnd: (() -> Unit)? = null) {
        currentAnimator?.cancel()

        val animator = ValueAnimator.ofFloat(from, to)
        animator.duration = duration
        animator.interpolator = AccelerateDecelerateInterpolator()

        animator.addUpdateListener { animation ->
            if (view != null) {
                layoutParams.alpha = animation.animatedValue as Float
                windowManager.updateViewLayout(view, layoutParams)
            }
        }

        animator.addListener(object : Animator.AnimatorListener {
            var canceled = false

            override fun onAnimationStart(animation: Animator) {}

            override fun onAnimationEnd(animation: Animator) {
                if (canceled) {
                    return
                }

                layoutParams.alpha = to
                windowManager.updateViewLayout(view, layoutParams)

                onEnd?.invoke()
            }

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationRepeat(animation: Animator) {}
        })

        animator.start()
        currentAnimator = animator
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "onReceive ${intent.action}")
            if (intent.action == ACTION_SHOW_VIEW) {
                showView()
            }
        }
    }

    // 监听设置变更（从 SettingsActivity 发出）
    private val overlaySettingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sticky = isLyricsOverlaySticky()
            val timeout = lyricsOverlayHideTimeoutMs()
            Log.i(TAG, "🎬YT [CFG] Overlay settings changed: sticky=$sticky, timeout=${timeout}ms, latencyMs=${overlayLatencyMs()}")
            // 若面板可见，重启计时器以应用新配置
            startIdleTimer()
            // Force a recompose to apply font/color/background instantly
            overlayStyleNonce++
        }
    }
    
    // YouTube播放进度更新接收器
    private val youtubePlaybackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            youtubePlaybackPosition = intent.getLongExtra("position", 0L)
            youtubePlaybackState = intent.getIntExtra("state", android.media.session.PlaybackState.STATE_NONE)
            youtubePlaybackSpeed = intent.getFloatExtra("playbackSpeed", 1.0f)
            youtubeLastPositionUpdateTime = intent.getLongExtra("lastUpdateTime", 0L)
            val videoId = intent.getStringExtra("videoId")

            Log.i(
                TAG,
                "🎬YT [11] ✅ Received PLAYBACK_UPDATE: position=$youtubePlaybackPosition, speed=$youtubePlaybackSpeed, lastUpdate=$youtubeLastPositionUpdateTime, videoId=$videoId, currentId=$currentYouTubeVideoId"
            )

            if (!videoId.isNullOrBlank()) {
                if (currentYouTubeVideoId == null || currentYouTubeVideoId != videoId) {
                    Log.i(TAG, "🎬YT [12b] Video ID changed via PLAYBACK_UPDATE -> $videoId; loading subtitles")
                    currentYouTubeVideoId = videoId
                    subtitleQueue.clear()
                    serviceScope.launch { loadYouTubeSubtitles(videoId) }
                } else {
                    Log.i(TAG, "🎬YT [12] Video ID matches, updating subtitle")
                    updateYouTubeSubtitle()
                }
            } else {
                Log.w(TAG, "🎬YT ⚠️ videoId is null in PLAYBACK_UPDATE; waiting")
            }
        }
    }
    
    // YouTube视频切换接收器
    private val youtubeVideoChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val videoId = intent.getStringExtra("videoId") ?: return
            
            Log.i(TAG, "🎬YT [13] ✅ Received VIDEO_CHANGED: $videoId")
            
            if (videoId != currentYouTubeVideoId) {
                Log.i(TAG, "🎬YT [14] Video changed, loading subtitles...")
                currentYouTubeVideoId = videoId
                
                // 清空字幕队列
                subtitleQueue.clear()
                
                // 加载新视频的字幕
                serviceScope.launch {
                    loadYouTubeSubtitles(videoId)
                }
            } else {
                Log.d(TAG, "🎬YT Video ID unchanged")
            }
        }
    }

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected")

        val application = super.getApplication() as MyApplication
        manager = application.manager
        
        // 初始化YouTube字幕管理器
        subtitleManager = YouTubeSubtitleManager(this)

        accessibilityButtonController.registerAccessibilityButtonCallback(object :
            AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController?) {
                if (manager.shizukuPermission) {
                    showView()
                }
            }
        })

        registerReceiver(broadcastReceiver, IntentFilter(ACTION_SHOW_VIEW), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(overlaySettingsReceiver, IntentFilter(ACTION_OVERLAY_SETTINGS_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        
        // 注册YouTube广播接收器
        registerReceiver(youtubePlaybackReceiver, IntentFilter("moe.chensi.volume.YOUTUBE_PLAYBACK_UPDATE"), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(youtubeVideoChangedReceiver, IntentFilter("moe.chensi.volume.YOUTUBE_VIDEO_CHANGED"), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(youtubeSubtitlesReadyReceiver, IntentFilter("moe.chensi.volume.YOUTUBE_SUBTITLES_READY"), Context.RECEIVER_NOT_EXPORTED)

        Log.i(TAG, "🎬YT [INIT] Receivers registered. lastId=${MyApplication.lastYouTubeVideoId}")
        Log.i(TAG, "onServiceConnected done ${serviceInfo.capabilities.toString(2)}")

        // 启动播放进度插值循环
        startYouTubeTicker()

        // 若应用在 Service 连接前已记录了 last videoId，则立即加载
        try {
            val lastId = MyApplication.lastYouTubeVideoId
            if (!lastId.isNullOrBlank()) {
                if (currentYouTubeVideoId != lastId) {
                    Log.i(TAG, "🎬YT [13] (late) Using recorded VIDEO_CHANGED: $lastId")
                    currentYouTubeVideoId = lastId
                    subtitleQueue.clear()
                    serviceScope.launch { loadYouTubeSubtitles(lastId) }
                }
            }
        } catch (_: Throwable) {}
    }

    // 字幕就绪（已预取并缓存）接收器
    private val youtubeSubtitlesReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val videoId = intent.getStringExtra("videoId") ?: return
            val count = intent.getIntExtra("count", -1)
            Log.i(TAG, "🎬YT [18] ✅ Received SUBTITLES_READY: $videoId ($count)")
            if (currentYouTubeVideoId == null || currentYouTubeVideoId == videoId) {
                currentYouTubeVideoId = videoId
                serviceScope.launch {
                    loadYouTubeSubtitles(videoId) // 命中本地 XML
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 当前未使用无障碍事件进行歌词采集
    }

    /**
     * 加载YouTube字幕
     */
    private suspend fun loadYouTubeSubtitles(videoId: String) {
        try {
            Log.i(TAG, "🎬YT [15] Loading subtitles for video: $videoId")
            currentLyrics = "正在加载字幕..."
            
            val subtitles = subtitleManager.getSubtitles(videoId, "en")
            
            if (subtitles.isNotEmpty()) {
                currentSubtitles = subtitles
                currentLyrics = "字幕已加载 (${subtitles.size}条)"
                Log.i(TAG, "🎬YT [16] ✅ Loaded ${subtitles.size} subtitles successfully")
            } else {
                currentSubtitles = emptyList()
                currentLyrics = "无可用字幕"
                Log.w(TAG, "🎬YT ⚠️ No subtitles available for video: $videoId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "🎬YT ❌ Failed to load subtitles for $videoId", e)
            currentLyrics = "加载字幕失败: ${e.message}"
        }
    }
    
    /**
     * 根据播放进度更新字幕显示
     */
    private fun updateYouTubeSubtitle() {
        if (!testLyricsMode) {
            Log.d(TAG, "🎬YT Not in testLyricsMode, skipping update")
            return
        }
        
        if (currentSubtitles.isEmpty()) {
            Log.w(TAG, "🎬YT ⚠️ No subtitles loaded, cannot update")
            return
        }
        // 计算插值后的位置（使用 MediaSession 的 lastUpdateTime 和 playbackSpeed）
        var effectivePos = youtubePlaybackPosition
        if (youtubePlaybackState == android.media.session.PlaybackState.STATE_PLAYING && youtubeLastPositionUpdateTime > 0L) {
            val now = SystemClock.elapsedRealtime()
            val delta = (now - youtubeLastPositionUpdateTime).coerceAtLeast(0L)
            val add = (delta.toDouble() * youtubePlaybackSpeed).toLong()
            effectivePos = (youtubePlaybackPosition + add).coerceAtLeast(0L)
        }

        // 补偿传输/处理延迟（可配置，默认+1000ms）
        val subtitle = subtitleManager.getCurrentSubtitle(effectivePos + overlayLatencyMs(), currentSubtitles)
        
        if (subtitle != null && subtitle.text.isNotEmpty()) {
            Log.d(TAG, "🎬YT [17] Updating subtitle @${effectivePos}ms: ${subtitle.text.take(20)}...")
            addToSubtitleQueue(subtitle.text)
        }
    }
    
    /**
     * 添加字幕到队列（实现滚动效果）
     * 最多50字，超出时从顶部移除
     * 队列对比：如果新行和已有任何行完全相同，则放弃新行
     */
    private fun normalizeForMerge(s: String): String = s.trim().replace(Regex("\\s+"), " ")

    private fun addToSubtitleQueue(text: String) {
        val incoming = normalizeForMerge(text)
        if (incoming.isEmpty()) return

        // 若与任意现有行完全相同，则忽略
        if (subtitleQueue.contains(incoming)) {
            Log.d(TAG, "🎬YT [18] Duplicate subtitle ignored: ${incoming.take(20)}...")
            return
        }

        // 智能合并：优先与最后一行合并，避免重复和过度碎片化
        if (subtitleQueue.isNotEmpty()) {
            val lastIdx = subtitleQueue.size - 1
            val last = normalizeForMerge(subtitleQueue[lastIdx])

            // 渐进补全：incoming 以 last 为前缀 → 用 incoming 覆盖 last
            if (incoming.startsWith(last) && incoming.length > last.length) {
                subtitleQueue[lastIdx] = incoming
                currentLyrics = subtitleQueue.joinToString("\n")
                Log.d(TAG, "🎬YT [19a] Progressive replace: ${incoming.take(40)}...")
                return
            }

            // last 已包含 incoming → 忽略 incoming
            if (last.contains(incoming)) {
                Log.d(TAG, "🎬YT [19b] Incoming contained in last; ignore: ${incoming.take(40)}")
                return
            }

            // 后缀-前缀重叠拼接，避免 today + day? 重复
            fun overlap(a: String, b: String): Int {
                val max = minOf(a.length, b.length)
                for (k in max downTo 1) {
                    if (a.endsWith(b.substring(0, k))) return k
                }
                return 0
            }
            val ov = overlap(last, incoming)
            val candidate = if (ov > 0) last + incoming.substring(ov) else "$last $incoming"
            if (candidate.length <= MAX_LINE_CHARS) {
                subtitleQueue[lastIdx] = candidate
                currentLyrics = subtitleQueue.joinToString("\n")
                Log.d(TAG, "🎬YT [19c] Merged with overlap (${candidate.length}): ${candidate.take(60)}...")
                return
            }

            // 尾部重复：如果 last 已以 incoming 结尾，则忽略
            if (last.endsWith(incoming)) {
                Log.d(TAG, "🎬YT [18] Suffix duplicate ignored: ${incoming.take(20)}...")
                return
            }
        }

        // 无法合并：追加为新行
        subtitleQueue.add(incoming)
        // 控制行数上限
        while (subtitleQueue.size > MAX_LINES) {
            val removed = subtitleQueue.removeAt(0)
            Log.d(TAG, "🎬YT [20] Dropped oldest line: ${removed.take(30)}...")
        }
        currentLyrics = subtitleQueue.joinToString("\n")
        Log.d(TAG, "🎬YT [21] Queue now ${subtitleQueue.size} lines; last='${incoming.take(40)}'")
    }
    
    override fun onInterrupt() {
        Log.i(TAG, "onInterrupt")

        Toast.makeText(this, "Accessibility service died!", Toast.LENGTH_SHORT).show()
        
        stopVolumePolling()
        volumeIdleTimer?.cancel()
        lyricsIdleTimer?.cancel()
        unregisterReceiver(broadcastReceiver)
        unregisterReceiver(youtubePlaybackReceiver)
        unregisterReceiver(youtubeVideoChangedReceiver)
        unregisterReceiver(youtubeSubtitlesReadyReceiver)
        unregisterReceiver(overlaySettingsReceiver)
        youtubeTickerJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.i(TAG, "onDestroy")

        Toast.makeText(this, "Accessibility service died!", Toast.LENGTH_SHORT).show()
        
        stopVolumePolling()
        volumeIdleTimer?.cancel()
        lyricsIdleTimer?.cancel()
        unregisterReceiver(broadcastReceiver)
        youtubeTickerJob?.cancel()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.i(TAG, "onKeyEvent action = ${event.action}, key code = ${event.keyCode}, shizuku permission = ${manager.shizukuPermission}")

        if (!manager.shizukuPermission) {
            return false
        }

        // Check for secret key sequence (+ - -) to toggle test mode
        // Only on first press (repeatCount == 0) to avoid long press interference
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    // checkKeySequence(event.keyCode)
                    
                    // If we just toggled test mode, consume the event
                    if (System.currentTimeMillis() - lastKeyPressTime < 100) {
                        // Check if sequence was just completed
                        if (keySequence.isEmpty()) {
                            return true
                        }
                    }
                }
            }
        }

        // Skip normal volume control if we're in test mode
        if (testLyricsMode) {
            // In test mode, volume keys don't do anything
            // Only the secret sequence can show/hide the lyrics view
            return true
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        Log.i(TAG, "VOLUME_UP DOWN: repeat=${event.repeatCount}")
                        if (event.repeatCount == 0) {
                            // Start a delayed long-press detection to avoid double-step on single tap
                            isVolumePressActive = true
                            pollingStartedForCurrentPress = false
                            currentPressDirection = AudioManager.ADJUST_RAISE
                            showView()
                            // Delay before starting continuous polling; if user releases quickly, we do single step on ACTION_UP
                            volumeStartDelayJob?.cancel()
                            volumeStartDelayJob = serviceScope.launch {
                                delay(250)
                                if (isVolumePressActive) {
                                    pollingStartedForCurrentPress = true
                                    startVolumePolling(AudioManager.ADJUST_RAISE)
                                }
                            }
                        }
                        return true
                    }
                    KeyEvent.ACTION_UP -> {
                        Log.i(TAG, "VOLUME_UP UP")
                        // End of press
                        isVolumePressActive = false
                        volumeStartDelayJob?.cancel()
                        volumeStartDelayJob = null
                        if (pollingStartedForCurrentPress) {
                            stopVolumePolling()
                        } else {
                            // Short tap: perform a single-step adjust
                            manager.audioManager.adjustSuggestedStreamVolume(
                                AudioManager.ADJUST_RAISE, AudioManager.USE_DEFAULT_STREAM_TYPE, 0
                            )
                        }
                        // Final sync of UI
                        musicVolume = manager.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        notificationVolume = manager.audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                        return true
                    }
                }
            }
            
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        Log.i(TAG, "VOLUME_DOWN DOWN: repeat=${event.repeatCount}")
                        if (event.repeatCount == 0) {
                            // Start a delayed long-press detection to avoid double-step on single tap
                            isVolumePressActive = true
                            pollingStartedForCurrentPress = false
                            currentPressDirection = AudioManager.ADJUST_LOWER
                            showView()
                            volumeStartDelayJob?.cancel()
                            volumeStartDelayJob = serviceScope.launch {
                                delay(250)
                                if (isVolumePressActive) {
                                    pollingStartedForCurrentPress = true
                                    startVolumePolling(AudioManager.ADJUST_LOWER)
                                }
                            }
                        }
                        return true
                    }
                    KeyEvent.ACTION_UP -> {
                        Log.i(TAG, "VOLUME_DOWN UP")
                        // End of press
                        isVolumePressActive = false
                        volumeStartDelayJob?.cancel()
                        volumeStartDelayJob = null
                        if (pollingStartedForCurrentPress) {
                            stopVolumePolling()
                        } else {
                            // Short tap: perform a single-step adjust
                            manager.audioManager.adjustSuggestedStreamVolume(
                                AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, 0
                            )
                        }
                        // Final sync of UI
                        musicVolume = manager.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        notificationVolume = manager.audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                        return true
                    }
                }
            }
        }

        return false
    }

    @Composable
    fun TestLyricsDisplay() {
        // Read once to create dependency; settings changes will trigger recomposition
        val _styleNonce = overlayStyleNonce
        val containerModifier = if (!isTextOnlyOverlay()) {
            Modifier.background(Color(0f, 0f, 0f, 0.88f), RoundedCornerShape(24.dp)).padding(16.dp)
        } else {
            Modifier.padding(16.dp)
        }
        androidx.compose.foundation.layout.Box(
            modifier = containerModifier
        ) {
            // 顶部右上角设置按钮
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                val ctx = this@Service
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .size(24.dp)
                        .padding(6.dp)
                        .clickable {
                            try {
                                val intent = Intent(ctx, SettingsActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                ctx.startActivity(intent)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to open SettingsActivity", e)
                            }
                        }
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 98.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 半透明黑色影子（后绘制），主体前景色（前绘制）
                val fontSp = overlayFontSp()
                val lineH = overlayLineHeightSp(fontSp)
                val fg = overlayTextColor()
                androidx.compose.foundation.layout.Box {
                    Text(
                        text = currentLyrics,
                        fontSize = fontSp.sp,
                        color = Color.Black.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .padding(start = 1.dp, top = 2.dp),
                        lineHeight = lineH.sp
                    )
                    Text(
                        text = currentLyrics,
                        fontSize = fontSp.sp,
                        color = fg,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(vertical = 8.dp),
                        lineHeight = lineH.sp
                    )
                }
                Text(
                    text = "点击空白处关闭",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // 设置改为跳转到 SettingsActivity，不再内嵌弹窗
        }
    }

    @Composable
    private fun LyricsOverlaySettingsDialog(onDismiss: () -> Unit) {
        var hideSec by remember { mutableStateOf((lyricsOverlayHideTimeoutMs() / 1000L).toInt()) }
        var sticky by remember { mutableStateOf(isLyricsOverlaySticky()) }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("字幕面板设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("隐藏时间(秒)")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Button(onClick = { if (hideSec > 0) hideSec -= 5 }) { Text("-5") }
                            androidx.compose.material3.Button(onClick = { if (hideSec > 0) hideSec -= 1 }) { Text("-1") }
                            Text("$hideSec s", modifier = Modifier.padding(horizontal = 8.dp))
                            androidx.compose.material3.Button(onClick = { hideSec += 1 }) { Text("+1") }
                            androidx.compose.material3.Button(onClick = { hideSec += 5 }) { Text("+5") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("不消失(仅点击空白处关闭)")
                        androidx.compose.material3.Switch(checked = sticky, onCheckedChange = { sticky = it })
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    overlayPrefs.edit()
                        .putInt("overlay_hide_timeout_sec", hideSec)
                        .putBoolean("overlay_sticky", sticky)
                        .apply()
                    onDismiss()
                    Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
                    startIdleTimer()
                }) { Text("保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
            }
        )
    }

    @Composable
    fun StreamVolumeSlider(
        streamType: Int,
        currentVolume: Int,
        icon: ImageVector,
        name: String,
        onVolumeUpdate: (Int) -> Unit,
        onChange: (() -> Unit)? = null
    ) {
        TrackSlider(
            cornerRadius = 20.dp,
            value = currentVolume.toFloat(),
            valueRange = 0f..manager.audioManager.getStreamMaxVolume(streamType).toFloat(),
            onValueChange = { value ->
                val newVolume = value.toInt()
                manager.audioManager.setStreamVolume(streamType, newVolume, 0)
                // Immediately update the local state for instant UI feedback
                onVolumeUpdate(newVolume)
                onChange?.invoke()
            },
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp, 8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = name,
                    modifier = Modifier.size(32.dp),
                )

                Text(text = name)
            }
        }
    }
}



