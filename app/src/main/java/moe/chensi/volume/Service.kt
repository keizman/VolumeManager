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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    
    // Track key sequence: Volume Up, Down, Down (+ - -)
    private val keySequence = mutableListOf<Int>()
    private var lastKeyPressTime = 0L
    private val sequenceTimeout = 2000L // 2 seconds to complete sequence
    private val targetSequence = listOf(
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_DOWN
    )

    private var idleTimer: CountDownTimer? = null

    private fun checkKeySequence(keyCode: Int) {
        val currentTime = System.currentTimeMillis()
        
        // Reset sequence if timeout
        if (currentTime - lastKeyPressTime > sequenceTimeout) {
            keySequence.clear()
        }
        
        // Add key to sequence
        keySequence.add(keyCode)
        lastKeyPressTime = currentTime
        
        Log.i(TAG, "Key sequence: ${keySequence.joinToString(",")}")
        
        // Keep only last 3 keys
        if (keySequence.size > 3) {
            keySequence.removeAt(0)
        }
        
        // Check if sequence matches target (+ - -)
        if (keySequence == targetSequence) {
            testLyricsMode = !testLyricsMode
            Log.i(TAG, "Test lyrics mode toggled: $testLyricsMode (sequence matched: + - -)")
            Toast.makeText(this, "歌词测试模式: ${if (testLyricsMode) "开启" else "关闭"}", Toast.LENGTH_SHORT).show()
            
            // Reset sequence
            keySequence.clear()
            
            // Show view if entering test mode
            if (testLyricsMode) {
                showView()
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

    private fun startIdleTimer() {
        idleTimer?.cancel()
        idleTimer = object : CountDownTimer(IDLE_TIMEOUT, IDLE_TIMEOUT) {
            override fun onTick(millisUntilFinished: Long) {}

            override fun onFinish() {
                hideView()
            }
        }.start()
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

                if (windowManager.isCrossWindowBlurEnabled && isHardwareAccelerated) {
                    background =
                        Reflect.on(rootSurfaceControl).call("createBackgroundBlurDrawable").apply {
                            call("setBlurRadius", 200)
                            call("setCornerRadius", 40f)
                        }.get()
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
            viewVisible = true
        }
    }

    private fun hideView() {
        if (viewVisible) {
            Log.i(TAG, "animate out")
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

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected")

        val application = super.getApplication() as MyApplication
        manager = application.manager

        accessibilityButtonController.registerAccessibilityButtonCallback(object :
            AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController?) {
                if (manager.shizukuPermission) {
                    showView()
                }
            }
        })

        registerReceiver(broadcastReceiver, IntentFilter(ACTION_SHOW_VIEW), RECEIVER_NOT_EXPORTED)

        Log.i(TAG, "onServiceConnected done ${serviceInfo.capabilities.toString(2)}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
    }

    override fun onInterrupt() {
        Log.i(TAG, "onInterrupt")

        Toast.makeText(this, "Accessibility service died!", Toast.LENGTH_SHORT).show()
        
        stopVolumePolling()
        unregisterReceiver(broadcastReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.i(TAG, "onDestroy")

        Toast.makeText(this, "Accessibility service died!", Toast.LENGTH_SHORT).show()
        
        stopVolumePolling()
        unregisterReceiver(broadcastReceiver)
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
                    checkKeySequence(event.keyCode)
                    
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
                        
                        // Only start polling on first press
                        if (event.repeatCount == 0) {
                            // Immediately adjust volume once
                            manager.audioManager.adjustSuggestedStreamVolume(
                                AudioManager.ADJUST_RAISE, AudioManager.USE_DEFAULT_STREAM_TYPE, 0
                            )
                            // Update UI immediately
                            musicVolume = manager.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            notificationVolume = manager.audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                            
                            // Show view and start continuous polling for long press
                            showView()
                            startVolumePolling(AudioManager.ADJUST_RAISE)
                        }
                        return true
                    }
                    KeyEvent.ACTION_UP -> {
                        Log.i(TAG, "VOLUME_UP UP")
                        stopVolumePolling()
                        // Final sync
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
                        
                        // Only start polling on first press
                        if (event.repeatCount == 0) {
                            // Immediately adjust volume once
                            manager.audioManager.adjustSuggestedStreamVolume(
                                AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, 0
                            )
                            // Update UI immediately
                            musicVolume = manager.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            notificationVolume = manager.audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                            
                            // Show view and start continuous polling for long press
                            showView()
                            startVolumePolling(AudioManager.ADJUST_LOWER)
                        }
                        return true
                    }
                    KeyEvent.ACTION_UP -> {
                        Log.i(TAG, "VOLUME_DOWN UP")
                        stopVolumePolling()
                        // Final sync
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
        // Simulated lyrics for testing
        val testLyrics = listOf(
            "天空好想下雨",
            "我好想住你隔壁",
            "傻站在你家楼下",
            "抬起头 数乌云",
            "如果场景里出现一架钢琴",
            "我会唱歌给你听",
            "哪怕好多盆水往下淋"
        )
        
        var currentLyricIndex by remember { mutableIntStateOf(0) }
        
        // Auto-scroll lyrics every 3 seconds
        LaunchedEffect(Unit) {
            while (true) {
                delay(3000)
                currentLyricIndex = (currentLyricIndex + 1) % testLyrics.size
            }
        }
        
        Column(
            modifier = Modifier
                .background(
                    Color(0f, 0f, 0f, 0.8f), RoundedCornerShape(20.dp)
                )
                .padding(40.dp, 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Previous lyric (dimmed)
            if (currentLyricIndex > 0) {
                Text(
                    text = testLyrics[currentLyricIndex - 1],
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center
                )
            }
            
            // Current lyric (highlighted)
            Text(
                text = testLyrics[currentLyricIndex],
                fontSize = 24.sp,
                color = Color(0xFF00D9FF),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // Next lyric (dimmed)
            if (currentLyricIndex < testLyrics.size - 1) {
                Text(
                    text = testLyrics[currentLyricIndex + 1],
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center
                )
            }
            
            // Indicator
            Text(
                text = "测试歌词模式 (${currentLyricIndex + 1}/${testLyrics.size})",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 16.dp)
            )
        }
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
