package moe.chensi.volume

import android.app.Notification
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

class LyricsListenerService : NotificationListenerService() {
    
    companion object {
        private const val TAG = "LyricsListener"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val YOUTUBE_REVANCED_PACKAGE = "app.revanced.android.youtube"
        
        // Target music apps (可能的包名)
        private val MUSIC_APPS = setOf(
            YOUTUBE_PACKAGE,               // YouTube官方
            YOUTUBE_REVANCED_PACKAGE,      // YouTube ReVanced
            "com.tencent.qqmusic",         // QQ音乐
            "com.netease.cloudmusic",      // 网易云音乐
            "com.android.music",           // 系统音乐
            "com.google.android.music"     // Google Play Music
        )
        
        // 监听所有通知（调试模式）
        private var LISTEN_ALL = false  // 只监听音乐APP
    }
    
    // YouTube MediaController
    private var youtubeMediaController: MediaController? = null
    private var currentYouTubeVideoId: String? = null
    private var gmsSnifferJob: Job? = null
    private val snifferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mappingProbeJob: Job? = null
    private var lastArtist: String? = null
    private var lastTitle: String? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "")
        Log.e(TAG, "═══════════════════════════════════════════════════════")
        Log.e(TAG, "🚀🚀🚀 LyricsListenerService onCreate() CALLED! 🚀🚀🚀")
        Log.e(TAG, "═══════════════════════════════════════════════════════")
        Log.e(TAG, "Service class: ${this.javaClass.name}")
        Log.e(TAG, "🎬YT [INIT] Service created, waiting for notifications...")
        Log.e(TAG, "")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.e(TAG, "")
        Log.e(TAG, "💀💀💀 SERVICE onDestroy() CALLED! 💀💀💀")
        Log.e(TAG, "")
        stopGmsVideoIdSniffer()
    }
    
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "")
        Log.i(TAG, "═══════════════════════════════════════════════════════")
        Log.i(TAG, "✅✅✅ NotificationListenerService CONNECTED! ✅✅✅")
        Log.i(TAG, "═══════════════════════════════════════════════════════")
        Log.i(TAG, "")
        Log.i(TAG, "📋 Listening mode: ${if (LISTEN_ALL) "ALL NOTIFICATIONS" else "MUSIC APPS ONLY"}")
        if (!LISTEN_ALL) {
            Log.i(TAG, "🎵 Target apps: ${MUSIC_APPS.joinToString()}")
        }
        Log.i(TAG, "")
        
        // 获取当前所有活跃的通知
        try {
            val activeNotifications = getActiveNotifications()
            Log.i(TAG, "📱 Current active notifications: ${activeNotifications.size}")
            activeNotifications.forEach { sbn ->
                Log.i(TAG, "  • ${sbn.packageName} (ID: ${sbn.id})")
            }
            Log.i(TAG, "")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get active notifications: ${e.message}")
        }
    }
    
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "❌ NotificationListenerService Disconnected")
        stopGmsVideoIdSniffer()
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        
        // ⚠️ 调试：打印所有通知的包名
        Log.d(TAG, "🔔 onNotificationPosted: $packageName")
        
        // 过滤：只详细处理音乐APP或所有通知（根据模式）
        if (!LISTEN_ALL && packageName !in MUSIC_APPS) {
            return
        }
        
        // 特别处理YouTube通知（支持官方和ReVanced）
        if (packageName == YOUTUBE_PACKAGE || packageName == YOUTUBE_REVANCED_PACKAGE) {
            Log.i(TAG, "🎬YT [0] YouTube notification detected! Package: $packageName")
            handleYouTubeNotification(sbn)
        } else {
            // 其他音乐APP的日志
            Log.i(TAG, "")
            Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.i(TAG, "📱 NEW NOTIFICATION from: $packageName")
            Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            
            val notification = sbn.notification
            
            // 1. Basic Info
            logBasicInfo(sbn, notification)
            
            // 2. Notification Content
            logNotificationContent(notification)
            
            // 3. Extras (most important for lyrics)
            logNotificationExtras(notification)
            
            // 4. Media Style specific info
            logMediaStyleInfo(notification)
            
            // 5. Actions
            logNotificationActions(notification)
            
            Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
        }
    }
    
    /**
     * 处理YouTube通知，提取MediaSession信息
     */
    private fun handleYouTubeNotification(sbn: StatusBarNotification) {
        try {
            Log.i(TAG, "🎬YT [1] handleYouTubeNotification called")
            
            val notification = sbn.notification
            val extras = notification.extras
            
            // 提取MediaSession Token
            val mediaSessionToken = extras.getParcelable<MediaSession.Token>(
                Notification.EXTRA_MEDIA_SESSION
            )
            
            if (mediaSessionToken != null) {
                Log.i(TAG, "🎬YT [2] MediaSession Token found!")
                
                // 创建或更新MediaController
                if (youtubeMediaController == null) {
                    Log.i(TAG, "🎬YT [3] Creating new MediaController")
                    setupYouTubeMediaController(mediaSessionToken)
                } else {
                    Log.d(TAG, "🎬YT MediaController already exists")
                }
                
                // 提取视频信息
                extractYouTubeVideoInfo(extras)
                
                // 尝试从MediaController获取播放状态
                youtubeMediaController?.let { controller ->
                    val state = controller.playbackState
                    val metadata = controller.metadata
                    
                    Log.i(TAG, "🎬YT [4] Playback state: ${state?.state}, metadata: ${metadata != null}")
                    
                    // 主动尝试提取 Video ID（有些设备不会触发 onMetadataChanged）
                    if (metadata != null) {
                        extractVideoIdFromMetadata(metadata)
                    }

                    // 兜底：从通知 extras 中尝试提取 videoId
                    tryExtractVideoIdFromNotificationExtras(extras)

                    if (state != null) {
                        broadcastYouTubePlaybackInfo(state, metadata)
                    } else {
                        Log.w(TAG, "🎬YT ⚠️ PlaybackState is null!")
                    }
                }

                // 如果仍未拿到 videoId，启动 GMS 日志嗅探（曲线救国）
                if (currentYouTubeVideoId == null) {
                    Log.w(TAG, "🎬YT ⚠️ Video ID still null; starting GMS log sniffer")
                    startGmsVideoIdSniffer()
                }
            } else {
                Log.w(TAG, "🎬YT ⚠️ No MediaSession token in notification")
            }
        } catch (e: Exception) {
            Log.e(TAG, "🎬YT ❌ Error handling YouTube notification", e)
        }
    }
    
    /**
     * 设置YouTube MediaController并注册回调
     */
    private fun setupYouTubeMediaController(token: MediaSession.Token) {
        try {
            youtubeMediaController = MediaController(this, token)
            
            // 注册回调监听播放状态变化
            youtubeMediaController?.registerCallback(object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    Log.i(TAG, "🎬YT [5] Playback state changed: ${stateToString(state?.state ?: -1)}")
                    
                    state?.let {
                        val metadata = youtubeMediaController?.metadata
                        // 状态变化也再次尝试提取 Video ID
                        if (metadata != null) {
                            extractVideoIdFromMetadata(metadata)
                        }
                        broadcastYouTubePlaybackInfo(it, metadata)
                    }
                }
                
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    Log.i(TAG, "🎬YT [6] Metadata changed")
                    if (metadata != null) {
                        extractVideoIdFromMetadata(metadata)
                    }
                }
            })
            
            Log.i(TAG, "🎬YT ✅ MediaController registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "🎬YT ❌ Failed to setup MediaController", e)
        }
    }
    
    /**
     * 从通知extras提取YouTube视频信息
     */
    private fun extractYouTubeVideoInfo(extras: Bundle) {
        // 提取标题（视频标题）
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        // 提取文本（频道名）
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        
        if (title != null) {
            Log.i(TAG, "📺 Video: $title")
            text?.let { Log.i(TAG, "👤 Channel: $it") }
        }
    }
    
    /**
     * 从MediaMetadata提取Video ID
     */
    private fun extractVideoIdFromMetadata(metadata: MediaMetadata) {
        try {
            Log.i(TAG, "🎬YT [7] Attempting to extract Video ID from metadata")
            
            // 方法1: 从description中提取
            val description = metadata.description
            val mediaUri = description?.mediaUri
            Log.d(TAG, "🎬YT mediaUri: $mediaUri")
            
            if (mediaUri != null) {
                val videoId = extractVideoIdFromUrl(mediaUri.toString())
                if (videoId != null && videoId != currentYouTubeVideoId) {
                    Log.i(TAG, "🎬YT [8] ✅ Extracted Video ID from URI: $videoId")
                    currentYouTubeVideoId = videoId
                    broadcastVideoId(videoId)
                    return
                }
            }
            
            // 方法2: 遍历所有字符串字段并尝试严格URL匹配
            try {
                val allKeys = metadata.keySet()
                var artist: String? = null
                var title: String? = null
                for (key in allKeys) {
                    val s = metadata.getString(key)
                    if (!s.isNullOrBlank()) {
                        Log.d(TAG, "🎬YT Metadata[$key] = ${s.take(200)}")
                        if (key == MediaMetadata.METADATA_KEY_ARTIST) artist = s
                        if (key == MediaMetadata.METADATA_KEY_TITLE || key == MediaMetadata.METADATA_KEY_DISPLAY_TITLE) title = s
                        val fromUrl = extractVideoIdFromUrlStrict(s)
                        if (fromUrl != null && fromUrl != currentYouTubeVideoId) {
                            Log.i(TAG, "🎬YT [8] ✅ Extracted Video ID from $key (url): $fromUrl")
                            currentYouTubeVideoId = fromUrl
                            mappingProbeJob?.cancel()
                            lastArtist = artist
                            lastTitle = title
                            // 写入/替换映射（确保纠正之前可能写入的旧值）
                            if (!artist.isNullOrBlank() && !title.isNullOrBlank()) {
                                val keyMd5 = YouTubeVideoIdMapping.makeKey(artist!!, title!!)
                                val put = YouTubeVideoIdMapping(applicationContext).putOrReplace(keyMd5, fromUrl)
                                Log.i(TAG, "🎬YT [MAP] write-on-url: key=$keyMd5 artist='${artist!!.take(120)}' title='${title!!.take(200)}' videoId=$fromUrl wroteOrReplaced=$put")
                            }
                            broadcastVideoId(fromUrl)
                            return
                        }
                    }
                }
                // 如果没解析到 URL，但有 artist+title，可尝试通过映射恢复 videoId
                if (currentYouTubeVideoId == null && !artist.isNullOrBlank() && !title.isNullOrBlank()) {
                    val keyMd5 = YouTubeVideoIdMapping.makeKey(artist!!, title!!)
                    Log.i(TAG, "🎬YT [MAP] lookup-on-meta: key=$keyMd5 artist='${artist!!.take(120)}' title='${title!!.take(200)}'")
                    val mapped = YouTubeVideoIdMapping(applicationContext).get(keyMd5)
                    if (!mapped.isNullOrBlank()) {
                        Log.i(TAG, "🎬YT [8] ✅ Mapped Video ID from artist+title: $mapped")
                        currentYouTubeVideoId = mapped
                        mappingProbeJob?.cancel()
                        lastArtist = artist
                        lastTitle = title
                        broadcastVideoId(mapped)
                        return
                    }
                    // 未命中：记录 artist/title 并在之后调度一次延迟探测（映射可能被并发写入）
                    lastArtist = artist
                    lastTitle = title
                }
            } catch (e: Exception) {
                Log.w(TAG, "🎬YT [7] keySet() not available or failed: ${e.message}")
            }
            
            Log.w(TAG, "🎬YT ⚠️ Failed to extract Video ID from metadata")
            // 在失败后，若已有 artist/title，延迟短暂时间再探测一次映射（prefetch 可能刚好写入）
            scheduleMappingProbeIfPossible()
        } catch (e: Exception) {
            Log.e(TAG, "🎬YT ❌ Error extracting video ID", e)
        }
    }

    private fun scheduleMappingProbeIfPossible() {
        val a = lastArtist
        val t = lastTitle
        if (currentYouTubeVideoId != null || a.isNullOrBlank() || t.isNullOrBlank()) return
        mappingProbeJob?.cancel()
        mappingProbeJob = ioScope.launch {
            try {
                kotlinx.coroutines.delay(700)
                if (currentYouTubeVideoId != null) return@launch
                val keyMd5 = YouTubeVideoIdMapping.makeKey(a!!, t!!)
                Log.i(TAG, "🎬YT [MAP] delayed-lookup: key=$keyMd5 artist='${a.take(120)}' title='${t.take(200)}'")
                val mapped = YouTubeVideoIdMapping(applicationContext).get(keyMd5)
                if (!mapped.isNullOrBlank()) {
                    Log.i(TAG, "🎬YT [8] ✅ Mapped (delayed) Video ID from artist+title: $mapped")
                    currentYouTubeVideoId = mapped
                    broadcastVideoId(mapped)
                }
            } catch (_: Throwable) {}
        }
    }
    
    /**
     * 从URL中提取Video ID
     */
    private fun extractVideoIdFromUrl(url: String): String? {
        val strict = extractVideoIdFromUrlStrict(url)
        if (strict != null) return strict
        val regexLoose = Regex("(?:v=|/shorts/|youtu.be/)([a-zA-Z0-9_-]{11})")
        return regexLoose.find(url)?.groupValues?.get(1)
    }
    
    /**
     * 从字符串中提取Video ID
     */
    private fun extractVideoIdFromString(text: String): String? {
        // 严格依赖URL/参数的形式，避免误抓任意 11 字符串
        return extractVideoIdFromUrlStrict(text)
    }

    private fun extractVideoIdFromUrlStrict(text: String): String? {
        val patterns = listOf(
            Regex("https?://(?:www\\.)?youtube\\.com/watch\\?[^\\s]*[?&]v=([a-zA-Z0-9_-]{11})"),
            Regex("https?://(?:www\\.)?youtu\\.be/([a-zA-Z0-9_-]{11})"),
            Regex("[?&]v=([a-zA-Z0-9_-]{11})(?:&|$)"),
            Regex("/shorts/([a-zA-Z0-9_-]{11})(?:\\b|/|\\?|$)"),
            Regex("docid=([a-zA-Z0-9_-]{11})(?:&|$)")
        )
        for (p in patterns) {
            val m = p.find(text)
            if (m != null) return m.groupValues[1]
        }
        return null
    }
    
    /**
     * 广播YouTube播放信息给Service
     */
    private fun broadcastYouTubePlaybackInfo(
        state: PlaybackState,
        metadata: MediaMetadata?
    ) {
        try {
            val intent = Intent("moe.chensi.volume.YOUTUBE_PLAYBACK_UPDATE").apply {
                setPackage(applicationContext.packageName)
            }
            intent.putExtra("position", state.position)
            intent.putExtra("state", state.state)
            intent.putExtra("playbackSpeed", state.playbackSpeed)
            intent.putExtra("lastUpdateTime", state.lastPositionUpdateTime)
            intent.putExtra("videoId", currentYouTubeVideoId)
            
            // 添加视频信息（不在此路径写入映射，避免用旧 videoId 污染新 key）
            if (metadata != null) {
                val description = metadata.description
                if (description != null) {
                    intent.putExtra("title", description.title?.toString())
                    intent.putExtra("artist", description.subtitle?.toString())
                }
            }
            
            sendBroadcast(intent)
            
            Log.i(TAG, "🎬YT [9] 📡 Broadcast PLAYBACK_UPDATE: position=${state.position}ms, " +
                    "state=${stateToString(state.state)}, speed=${state.playbackSpeed}, " +
                    "lastUpdate=${state.lastPositionUpdateTime}, videoId=$currentYouTubeVideoId")
        } catch (e: Exception) {
            Log.e(TAG, "🎬YT ❌ Error broadcasting playback info", e)
        }
    }

    /**
     * 兜底：从 Notification.extras 里扫描文案/URL 提取 Video ID
     */
    private fun tryExtractVideoIdFromNotificationExtras(extras: Bundle) {
        try {
            if (currentYouTubeVideoId != null) return
            for (key in extras.keySet()) {
                val v = extras.get(key)
                val text = when (v) {
                    is CharSequence -> v.toString()
                    is String -> v
                    else -> null
                } ?: continue
                Log.d(TAG, "🎬YT Extras[$key] = ${text.take(200)}")
                val lower = text.lowercase()
                val looksLikeUrl = ("youtube" in lower) || ("youtu.be" in lower) || ("watch?v=" in lower) || ("shorts/" in lower) || ("docid=" in lower)
                if (!looksLikeUrl) continue
                val fromUrl = extractVideoIdFromUrlStrict(text)
                if (fromUrl != null) {
                    Log.i(TAG, "🎬YT [7] ✅ Extracted Video ID from extras[$key] URL: $fromUrl")
                    currentYouTubeVideoId = fromUrl
                    broadcastVideoId(fromUrl)
                    return
                }
            }
            Log.d(TAG, "🎬YT [7] No Video ID found in extras (strict)")
        } catch (e: Exception) {
            Log.e(TAG, "🎬YT ❌ Error extracting Video ID from extras", e)
        }
    }
    
    /**
     * 广播Video ID（视频切换）
     */
    private fun broadcastVideoId(videoId: String) {
        try {
            val intent = Intent("moe.chensi.volume.YOUTUBE_VIDEO_CHANGED").apply {
                setPackage(applicationContext.packageName)
            }
            intent.putExtra("videoId", videoId)
            sendBroadcast(intent)
            
            Log.i(TAG, "🎬YT [10] 📡 Broadcast VIDEO_CHANGED: $videoId")

            // 曲线救国2：预取字幕到本地缓存，避免 Service 未连接导致未及时拉取
            prefetchSubtitles(videoId)
        } catch (e: Exception) {
            Log.e(TAG, "🎬YT ❌ Error broadcasting video ID", e)
        }
    }

    private fun prefetchSubtitles(videoId: String) {
        ioScope.launch {
            try {
                Log.i(TAG, "🎬YT [PREFETCH] Start prefetch subtitles for $videoId")
                val mgr = YouTubeSubtitleManager(applicationContext)
                val list = mgr.getSubtitles(videoId, "en")
                Log.i(TAG, "🎬YT [PREFETCH] Done: ${list.size} entries for $videoId")
                if (list.isNotEmpty()) {
                    // 通知 Service 字幕已就绪，可立即从本地加载
                    val ready = Intent("moe.chensi.volume.YOUTUBE_SUBTITLES_READY").apply {
                        putExtra("videoId", videoId)
                        putExtra("count", list.size)
                    }
                    sendBroadcast(ready)
                    Log.i(TAG, "🎬YT [READY] Broadcast SUBTITLES_READY: $videoId (${list.size})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "🎬YT [PREFETCH] Error prefetching subtitles", e)
            }
        }
    }
    
    /**
     * 将PlaybackState状态码转换为字符串
     */
    private fun stateToString(state: Int): String {
        return when (state) {
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_BUFFERING -> "BUFFERING"
            else -> "UNKNOWN($state)"
        }
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        
        if (packageName in MUSIC_APPS) {
            Log.d(TAG, "🗑️ REMOVED: $packageName notification (ID: ${sbn.id})")
        }
    }
    
    private fun logBasicInfo(sbn: StatusBarNotification, notification: Notification) {
        Log.d(TAG, "📋 Basic Info:")
        Log.d(TAG, "  • Package: ${sbn.packageName}")
        Log.d(TAG, "  • ID: ${sbn.id}")
        Log.d(TAG, "  • Key: ${sbn.key}")
        Log.d(TAG, "  • Tag: ${sbn.tag ?: "null"}")
        Log.d(TAG, "  • Post Time: ${sbn.postTime}")
        Log.d(TAG, "  • Category: ${notification.category ?: "null"}")
        Log.d(TAG, "  • Priority: ${notification.priority}")
        Log.d(TAG, "  • Flags: ${notification.flags}")
    }
    
    private fun logNotificationContent(notification: Notification) {
        Log.d(TAG, "📝 Content:")
        
        // Ticker text (old API)
        notification.tickerText?.let {
            Log.d(TAG, "  • Ticker: $it")
        }
        
        // Large icon
        notification.getLargeIcon()?.let {
            Log.d(TAG, "  • Has Large Icon: Yes")
        }
        
        // Small icon
        Log.d(TAG, "  • Small Icon: ${notification.smallIcon?.resId ?: "null"}")
    }
    
    private fun logNotificationExtras(notification: Notification) {
        val extras = notification.extras
        
        Log.d(TAG, "🎯 Extras (Key Info):")
        
        // Title
        extras.getCharSequence(Notification.EXTRA_TITLE)?.let {
            Log.d(TAG, "  • TITLE: $it")
        }
        
        // Text (main content)
        extras.getCharSequence(Notification.EXTRA_TEXT)?.let {
            Log.d(TAG, "  • TEXT: $it")
        }
        
        // Sub text
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let {
            Log.d(TAG, "  • SUB_TEXT: $it")
        }
        
        // Info text
        extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.let {
            Log.d(TAG, "  • INFO_TEXT: $it")
        }
        
        // Summary text
        extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.let {
            Log.d(TAG, "  • SUMMARY_TEXT: $it")
        }
        
        // Big text (for expanded notifications)
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let {
            Log.d(TAG, "  • BIG_TEXT: $it")
        }
        
        // Media session token
        extras.getParcelable<android.media.session.MediaSession.Token>(
            Notification.EXTRA_MEDIA_SESSION
        )?.let {
            Log.d(TAG, "  • MEDIA_SESSION: $it")
        }
        
        // Template
        extras.getString(Notification.EXTRA_TEMPLATE)?.let {
            Log.d(TAG, "  • TEMPLATE: $it")
        }
        
        // All other keys
        Log.d(TAG, "  📦 All Extra Keys:")
        extras.keySet().forEach { key ->
            val value = extras.get(key)
            Log.d(TAG, "     - $key: ${value?.javaClass?.simpleName} = $value")
        }
    }
    
    private fun logMediaStyleInfo(notification: Notification) {
        // Check if it's a media notification
        if (notification.category == Notification.CATEGORY_TRANSPORT ||
            notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            
            Log.d(TAG, "🎵 Media Notification Detected!")
            
            // Try to get media metadata
            notification.extras.getParcelable<android.media.session.MediaSession.Token>(
                Notification.EXTRA_MEDIA_SESSION
            )?.let { token ->
                Log.d(TAG, "  • Media Session Token: $token")
                
                // Note: Would need MediaController to get full metadata
                // This requires active media session access
            }
        }
    }
    
    private fun logNotificationActions(notification: Notification) {
        val actions = notification.actions
        
        if (actions != null && actions.isNotEmpty()) {
            Log.d(TAG, "⚡ Actions (${actions.size}):")
            actions.forEachIndexed { index, action ->
                Log.d(TAG, "  [$index] ${action.title}")
            }
        }
    }

    /**
     * 曲线救国：通过 Shizuku 以 shell 用户启动 logcat，嗅探包含 videoId 的 GMS/YT 行
     */
    private fun startGmsVideoIdSniffer() {
        if (gmsSnifferJob != null) return
        gmsSnifferJob = snifferScope.launch {
            try {
                if (!Shizuku.pingBinder()) {
                    Log.w(TAG, "🎬YT [SNIFF] Shizuku not available; cannot start logcat sniffer")
                    return@launch
                }
                val args = arrayOf(
                    "logcat", "-v", "brief",
                    "GmsGuardHandleImpl:D", "YT.qoe:D", "*:S"
                )
                val proc = try {
                    @Suppress("UNCHECKED_CAST")
                    org.joor.Reflect.onClass(Shizuku::class.java)
                        .call("newProcess", args, null, null)
                        .get<ShizukuRemoteProcess>()
                } catch (e: Throwable) {
                    Log.e(TAG, "🎬YT [SNIFF] Failed to start logcat via Shizuku", e)
                    return@launch
                }
                val input = try {
                    org.joor.Reflect.on(proc).call("getInputStream").get<java.io.InputStream>()
                } catch (_: Throwable) {
                    try { org.joor.Reflect.on(proc).get<java.io.InputStream>("inputStream") } catch (e: Throwable) {
                        Log.e(TAG, "🎬YT [SNIFF] Cannot access logcat stream", e)
                        return@launch
                    }
                }

                val reader = BufferedReader(InputStreamReader(input))
                val reE = Regex("\\be=([A-Za-z0-9_-]{11})\\b")
                val reDoc = Regex("\\bdocid=([A-Za-z0-9_-]{11})\\b")
                var lastLine: String? = null
                Log.i(TAG, "🎬YT [SNIFF] Logcat sniffer started")

                while (isActive && currentYouTubeVideoId == null) {
                    val line = reader.readLine() ?: break
                    val text = (lastLine ?: "") + line
                    lastLine = line.takeLast(64)
                    val vid = reE.find(text)?.groupValues?.getOrNull(1)
                        ?: reDoc.find(text)?.groupValues?.getOrNull(1)
                    if (vid != null) {
                        Log.i(TAG, "🎬YT [SNIFF] ✅ Captured videoId from logs: $vid")
                        currentYouTubeVideoId = vid
                        broadcastVideoId(vid)
                        break
                    }
                }

                try { org.joor.Reflect.on(proc).call("destroy") } catch (_: Throwable) {}
                Log.i(TAG, "🎬YT [SNIFF] Stopped")
            } catch (e: Exception) {
                Log.e(TAG, "🎬YT [SNIFF] Error in sniffer", e)
            } finally {
                gmsSnifferJob = null
            }
        }
    }

    private fun stopGmsVideoIdSniffer() {
        try { gmsSnifferJob?.cancel() } catch (_: Throwable) {}
        gmsSnifferJob = null
    }
}

