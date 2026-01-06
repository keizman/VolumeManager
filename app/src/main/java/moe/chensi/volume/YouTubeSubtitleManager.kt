package moe.chensi.volume

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube字幕管理器
 * 负责下载、解析、缓存字幕
 */
class YouTubeSubtitleManager(private val context: Context) {
    
    companion object {
        private const val TAG = "YouTubeSubtitleManager"
        private const val CACHE_DIR_NAME = "youtube_subtitles"
        private const val CONNECTION_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 10000
    }
    
    data class SubtitleEntry(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )
    
    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    // XML 缓存目录：应用外部专属目录，不需要存储权限
    private val xmlCacheDir: File by lazy {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, CACHE_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }
    
    /**
     * 获取字幕（优先从缓存读取）
     * @param videoId YouTube视频ID
     * @param preferredLanguage 优先语言代码（默认"en"）
     * @return 字幕列表
     */
    suspend fun getSubtitles(videoId: String, preferredLanguage: String = "en"): List<SubtitleEntry> {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 优先检查外部目录 XML 缓存（指定位置，文件名为 videoId.xml）
                val xmlFile = getXmlCacheFile(videoId)
                if (xmlFile.exists()) {
                    Log.i(TAG, "🎬YT [SUB] Loading subtitles from XML cache: ${xmlFile.absolutePath}")
                    return@withContext loadFromXml(xmlFile)
                } else {
                    Log.i(TAG, "🎬YT [SUB] XML cache not found: ${xmlFile.absolutePath}")
                }

                // 2. 请求player response获取字幕URL
                Log.i(TAG, "Fetching subtitles for video: $videoId")
                val playerResponse = YouTubeInnertubeApi.requestPlayerResponse(videoId)
                    ?: return@withContext emptyList()
                
                // 3. 选择字幕轨道（优先级：EN -> 可翻译到 EN -> ZH -> 任意）
                val selected = findCaptionTrack(
                    playerResponse.captions,
                    preferredLanguage
                ) ?: run {
                    Log.w(TAG, "🎬YT [SUB] No English subtitle track available; skip download")
                    return@withContext emptyList()
                }
                val captionTrack = selected.track
                Log.i(TAG, "Found caption track: ${captionTrack.name} (${captionTrack.languageCode}) translateTo=${selected.translateTo}")
                Log.d(TAG, "🎬YT [SUB] Track baseUrl head: ${captionTrack.baseUrl.take(120)}...")
                
                // 4. 下载字幕
                val subtitles = downloadSubtitle(captionTrack.baseUrl, selected.translateTo)

                // 5. 保存到 XML 缓存（videoId.xml）
                if (subtitles.isNotEmpty()) {
                    saveToXml(xmlFile, subtitles)
                    Log.i(TAG, "🎬YT [SUB] Cached ${subtitles.size} subtitles to XML: ${xmlFile.name}")
                }

                subtitles
            } catch (e: Exception) {
                Log.e(TAG, "Error getting subtitles", e)
                emptyList()
            }
        }
    }
    
    /**
     * 查找指定语言的字幕轨道
     */
    private data class SelectedTrack(
        val track: YouTubeInnertubeApi.CaptionTrack,
        val translateTo: String?
    )

    private fun findCaptionTrack(
        captions: YouTubeInnertubeApi.Captions?,
        preferredLanguage: String
    ): SelectedTrack? {
        if (captions == null || captions.captionTracks.isEmpty()) {
            Log.w(TAG, "🎬YT [SUB] No caption tracks available")
            return null
        }

        val tracks = captions.captionTracks
        Log.i(TAG, "🎬YT [SUB] Tracks available: ${tracks.size} -> [" + tracks.joinToString { it.languageCode } + "]")

        // 优先级：1) 英文(原生/自动) 2) 可翻译到英文 3) 中文 4) 其他第一条
        tracks.firstOrNull { it.languageCode.startsWith("en", ignoreCase = true) }?.let {
            Log.i(TAG, "🎬YT [SUB] Pick EN native: ${it.languageCode} (${it.name})")
            return SelectedTrack(it, null)
        }
        tracks.firstOrNull { it.isTranslatable && (it.translationLanguages.isEmpty() || it.translationLanguages.any { lc -> lc.startsWith("en", true) }) }?.let {
            Log.i(TAG, "🎬YT [SUB] Pick translate->EN from: ${it.languageCode} (${it.name})")
            return SelectedTrack(it, "en")
        }
        tracks.firstOrNull { it.languageCode.startsWith("zh", ignoreCase = true) }?.let {
            Log.i(TAG, "🎬YT [SUB] Pick ZH native: ${it.languageCode} (${it.name})")
            return SelectedTrack(it, null)
        }
        // 4) 兜底：使用第一条可用轨道
        val any = tracks.first()
        Log.w(TAG, "🎬YT [SUB] Fallback to FIRST track: ${any.languageCode} (${any.name})")
        return SelectedTrack(any, null)
    }
    
    /**
     * 下载字幕
     */
    private suspend fun downloadSubtitle(baseUrl: String, translateTo: String? = null): List<SubtitleEntry> {
        return withContext(Dispatchers.IO) {
            try {
                // 强制使用 fmt=json3（部分轨道会给出 fmt=srv3 XML，我们统一替换为 JSON3）
                val url = run {
                    val fmtRegex = Regex("([?&])fmt=[^&]*")
                    var u = if (fmtRegex.containsMatchIn(baseUrl)) baseUrl.replace(fmtRegex, "${'$'}1fmt=json3")
                            else baseUrl + if (baseUrl.contains("?")) "&fmt=json3" else "?fmt=json3"
                    if (!translateTo.isNullOrBlank()) {
                        val tlRegex = Regex("([?&])tlang=[^&]*")
                        u = if (tlRegex.containsMatchIn(u)) u.replace(tlRegex, "${'$'}1tlang=${translateTo}")
                            else u + if (u.contains("?")) "&tlang=${translateTo}" else "?tlang=${translateTo}"
                    }
                    u
                }
                
                Log.d(TAG, "🎬YT [SUB] Downloading subtitle from: $url")
                
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECTION_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                
                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    Log.e(TAG, "🎬YT [SUB] Failed to download subtitle: $responseCode")
                    return@withContext emptyList()
                }
                
                val response = BufferedReader(InputStreamReader(connection.inputStream))
                    .use { it.readText() }
                
                Log.d(TAG, "🎬YT [SUB] Downloaded subtitle (${response.length} bytes)")
                Log.d(TAG, "🎬YT [SUB] Subtitle head: ${response.take(200)}...")
                
                // 解析字幕
                parseSubtitleJson(response)
            } catch (e: Exception) {
                Log.e(TAG, "🎬YT [SUB] Error downloading subtitle", e)
                emptyList()
            }
        }
    }
    
    /**
     * 解析字幕JSON
     */
    private fun parseSubtitleJson(json: String): List<SubtitleEntry> {
        try {
            val root = JSONObject(json)
            val events = root.optJSONArray("events") ?: return emptyList()
            
            val subtitles = mutableListOf<SubtitleEntry>()
            
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                
                val startMs = event.optLong("tStartMs", -1)
                if (startMs < 0) continue
                
                val durationMs = event.optLong("dDurationMs", 3000)
                val endMs = startMs + durationMs
                
                // 解析字幕文本
                val segs = event.optJSONArray("segs")
                if (segs == null || segs.length() == 0) continue
                
                val text = StringBuilder()
                for (j in 0 until segs.length()) {
                    val seg = segs.getJSONObject(j)
                    val utf8 = seg.optString("utf8", "")
                    text.append(utf8)
                }
                
                val textStr = text.toString().trim()
                if (textStr.isNotEmpty()) {
                    subtitles.add(SubtitleEntry(startMs, endMs, textStr))
                }
            }
            
            Log.i(TAG, "Parsed ${subtitles.size} subtitle entries")
            return subtitles
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing subtitle JSON", e)
            return emptyList()
        }
    }
    
    /**
     * 根据播放时间查找当前字幕
     */
    fun getCurrentSubtitle(timeMs: Long, subtitles: List<SubtitleEntry>): SubtitleEntry? {
        return subtitles.find { timeMs in it.startMs..it.endMs }
    }
    
    /** XML 缓存文件 (指定位置: 外部专属目录, 文件名为 videoId.xml) */
    private fun getXmlCacheFile(videoId: String): File = File(xmlCacheDir, "$videoId.xml")
    
    /**
     * 保存字幕到缓存
     */
    private fun saveToCache(file: File, subtitles: List<SubtitleEntry>) {
        try {
            val json = JSONObject().apply {
                val array = org.json.JSONArray()
                subtitles.forEach { subtitle ->
                    array.put(JSONObject().apply {
                        put("startMs", subtitle.startMs)
                        put("endMs", subtitle.endMs)
                        put("text", subtitle.text)
                    })
                }
                put("subtitles", array)
                put("cachedAt", System.currentTimeMillis())
            }
            
            file.writeText(json.toString())
            Log.d(TAG, "Saved to cache: ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to cache", e)
        }
    }

    /** 保存为 XML 缓存 */
    private fun saveToXml(file: File, subtitles: List<SubtitleEntry>) {
        try {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<subtitles cachedAt=\"${System.currentTimeMillis()}\">\n")
            subtitles.forEach { s ->
                val textEscaped = escapeXml(s.text)
                sb.append("  <entry startMs=\"${s.startMs}\" endMs=\"${s.endMs}\">")
                sb.append(textEscaped)
                sb.append("</entry>\n")
            }
            sb.append("</subtitles>")
            file.writeText(sb.toString())
            Log.d(TAG, "🎬YT [SUB] Saved XML: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "🎬YT [SUB] Error saving XML cache", e)
        }
    }

    /** 从 XML 读取 */
    private fun loadFromXml(file: File): List<SubtitleEntry> {
        try {
            val input = file.inputStream()
            input.use {
                val parser = android.util.Xml.newPullParser()
                parser.setInput(it, "UTF-8")
                var event = parser.eventType
                val result = mutableListOf<SubtitleEntry>()
                var curStart: Long? = null
                var curEnd: Long? = null
                var curText: StringBuilder? = null

                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        org.xmlpull.v1.XmlPullParser.START_TAG -> {
                            if (parser.name == "entry") {
                                curStart = parser.getAttributeValue(null, "startMs")?.toLongOrNull()
                                curEnd = parser.getAttributeValue(null, "endMs")?.toLongOrNull()
                                curText = StringBuilder()
                            }
                        }
                        org.xmlpull.v1.XmlPullParser.TEXT -> {
                            curText?.append(parser.text)
                        }
                        org.xmlpull.v1.XmlPullParser.END_TAG -> {
                            if (parser.name == "entry") {
                                val s = curStart
                                val e = curEnd
                                val t = curText?.toString()?.trim() ?: ""
                                if (s != null && e != null && t.isNotEmpty()) {
                                    result.add(SubtitleEntry(s, e, t))
                                }
                                curStart = null
                                curEnd = null
                                curText = null
                            }
                        }
                    }
                    event = parser.next()
                }
            Log.i(TAG, "🎬YT [SUB] Loaded ${result.size} subtitles from XML: ${file.absolutePath}")
                return result
            }
        } catch (e: Exception) {
            Log.e(TAG, "🎬YT [SUB] Error loading XML cache", e)
            return emptyList()
        }
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
    
    /**
     * 从缓存加载字幕
     */
    private fun loadFromCache(file: File): List<SubtitleEntry> {
        try {
            val json = file.readText()
            val root = JSONObject(json)
            val array = root.getJSONArray("subtitles")
            
            val subtitles = mutableListOf<SubtitleEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val startMs = obj.getLong("startMs")
                val endMs = obj.getLong("endMs")
                val text = obj.getString("text")
                subtitles.add(SubtitleEntry(startMs, endMs, text))
            }
            
            Log.d(TAG, "Loaded ${subtitles.size} subtitles from cache")
            return subtitles
        } catch (e: Exception) {
            Log.e(TAG, "Error loading from cache", e)
            return emptyList()
        }
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
            Log.i(TAG, "Cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
        }
    }
    
    /**
     * 获取缓存大小（MB）
     */
    fun getCacheSizeMB(): Double {
        try {
            val totalSize = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
            return totalSize / (1024.0 * 1024.0)
        } catch (e: Exception) {
            return 0.0
        }
    }
}

