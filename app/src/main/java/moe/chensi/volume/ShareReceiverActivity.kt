package moe.chensi.volume

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast

class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        val type = intent?.type
        Log.i("ShareReceiver", "🎬YT [SHARE] onCreate action=$action type=$type")

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.clipData?.getItemAt(0)?.text?.toString()
            ?: intent.dataString

        val videoId = text?.let { extractVideoIdFromUrlStrict(it) }

        if (videoId != null) {
            Log.i("ShareReceiver", "🎬YT [SHARE] ✅ Parsed videoId=$videoId from: ${text.take(200)}")
            val broadcast = Intent("moe.chensi.volume.YOUTUBE_VIDEO_CHANGED")
            broadcast.putExtra("videoId", videoId)
            sendBroadcast(broadcast)
            Toast.makeText(this, "导入视频成功: $videoId", Toast.LENGTH_SHORT).show()
        } else {
            Log.w("ShareReceiver", "🎬YT [SHARE] ❌ Unable to parse from text: ${text?.take(200)}")
            Toast.makeText(this, "未识别的 YouTube 链接", Toast.LENGTH_SHORT).show()
        }

        finish()
    }

    private fun extractVideoIdFromUrlStrict(text: String): String? {
        val patterns = listOf(
            Regex("https?://(?:www\\.)?youtube\\.com/watch\\?[^\\s]*[?&]v=([a-zA-Z0-9_-]{11})"),
            Regex("https?://(?:www\\.)?youtu\\.be/([a-zA-Z0-9_-]{11})"),
            Regex("[?&]v=([a-zA-Z0-9_-]{11})(?:&|$)"),
            Regex("/shorts/([a-zA-Z0-9_-]{11})(?:\\b|/|\\?|$)")
        )
        for (p in patterns) {
            val m = p.find(text)
            if (m != null) return m.groupValues[1]
        }
        return null
    }
}

