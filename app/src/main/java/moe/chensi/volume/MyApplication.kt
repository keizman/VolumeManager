package moe.chensi.volume

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuProvider

class MyApplication : Application() {
    companion object {
        @Volatile
        var lastYouTubeVideoId: String? = null
    }
    val dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_volumes")
    val manager by lazy {
        Manager(this, dataStore)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        val processName = getProcessName()
        Log.i("Application", "process name = $processName")
        val singleProcess = (processName?.indexOf(':') ?: -1) < 0
        ShizukuProvider.enableMultiProcessSupport(singleProcess)
        HiddenApiBypass.addHiddenApiExemptions("")
    }

    override fun onCreate() {
        super.onCreate()
        // 记录最后一次 videoId（防止 Service 尚未连接时丢失）
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val vid: String? = intent?.getStringExtra("videoId")
                if (!vid.isNullOrBlank()) {
                    lastYouTubeVideoId = vid
                    Log.i("Application", "🎬YT [APP] lastYouTubeVideoId=$vid recorded")
                }
            }
        }, IntentFilter("moe.chensi.volume.YOUTUBE_VIDEO_CHANGED"), Context.RECEIVER_NOT_EXPORTED)

        // 兜底：也监听 PLAYBACK_UPDATE（其中通常也会携带 videoId），保证无 VIDEO_CHANGED 也能记录
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val vid: String? = intent?.getStringExtra("videoId")
                if (!vid.isNullOrBlank()) {
                    lastYouTubeVideoId = vid
                    Log.i("Application", "🎬YT [APP] (fallback) lastYouTubeVideoId=$vid recorded from PLAYBACK_UPDATE")
                }
            }
        }, IntentFilter("moe.chensi.volume.YOUTUBE_PLAYBACK_UPDATE"), Context.RECEIVER_NOT_EXPORTED)
    }
}
