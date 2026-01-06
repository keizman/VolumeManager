package moe.chensi.volume

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube Innertube API封装
 * 用于获取视频的播放数据，包括字幕信息
 */
object YouTubeInnertubeApi {
    private const val TAG = "YouTubeInnertubeApi"
    private const val INNERTUBE_API_URL = "https://youtubei.googleapis.com/youtubei/v1/player"
    private const val USER_AGENT = "com.google.android.youtube/19.09.37 (Linux; U; Android 11)"
    private const val CONNECTION_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 10000
    
    data class PlayerResponse(
        val captions: Captions?,
        val videoDetails: VideoDetails?
    )
    
    data class Captions(
        val captionTracks: List<CaptionTrack>
    )
    
    data class CaptionTrack(
        val baseUrl: String,
        val name: String,
        val languageCode: String,
        val vssId: String,
        val isTranslatable: Boolean,
        val translationLanguages: List<String>
    )
    
    data class VideoDetails(
        val videoId: String,
        val title: String,
        val lengthSeconds: String,
        val author: String
    )
    
    /**
     * 请求视频的player response
     * @param videoId YouTube视频ID
     * @return PlayerResponse包含字幕和视频信息
     */
    suspend fun requestPlayerResponse(videoId: String): PlayerResponse? {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "🎬YT [API-1] Requesting player response for videoId: $videoId")
                
                val connection = URL(INNERTUBE_API_URL).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.connectTimeout = CONNECTION_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true
                
                // 构建请求体
                val requestBody = buildRequestBody(videoId)
                Log.d(TAG, "🎬YT [API-1a] URL=$INNERTUBE_API_URL, body=${requestBody.take(200)}...")
                connection.outputStream.write(requestBody.toByteArray(Charsets.UTF_8))
                
                // 读取响应
                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    val err = try {
                        BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
                    } catch (_: Exception) { "" }
                    Log.e(TAG, "🎬YT ❌ [API-2] Failed to get player response: $responseCode err=${err.take(200)}...")
                    return@withContext null
                }
                
                val response = BufferedReader(InputStreamReader(connection.inputStream))
                    .use { it.readText() }
                
                Log.i(TAG, "🎬YT [API-3] ✅ Received player response (${response.length} bytes)")
                Log.d(TAG, "🎬YT [API-3a] Response head: ${response.take(200)}...")
                
                // 解析响应
                val result = parsePlayerResponse(response)
                if (result != null) {
                    Log.i(TAG, "🎬YT [API-4] ✅ Parsed successfully: ${result.captions?.captionTracks?.size ?: 0} caption tracks")
                    result.captions?.captionTracks?.let { tracks ->
                        val langs = tracks.joinToString { it.languageCode }
                        Log.d(TAG, "🎬YT [API-4a] Tracks languages: $langs")
                    }
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "🎬YT ❌ [API] Error requesting player response", e)
                null
            }
        }
    }
    
    /**
     * 构建Innertube API请求体
     */
    private fun buildRequestBody(videoId: String): String {
        val json = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID")
                    put("clientVersion", "19.09.37")
                    put("androidSdkVersion", 30)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        return json.toString()
    }
    
    /**
     * 解析player response JSON
     */
    private fun parsePlayerResponse(json: String): PlayerResponse? {
        try {
            val root = JSONObject(json)
            
            // 解析字幕信息
            val captions = parseCaptions(root)
            
            // 解析视频详情
            val videoDetails = parseVideoDetails(root)
            
            return PlayerResponse(captions, videoDetails)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing player response", e)
            return null
        }
    }
    
    /**
     * 解析字幕信息
     */
    private fun parseCaptions(root: JSONObject): Captions? {
        try {
            val captionsObj = root.optJSONObject("captions") ?: return null
            val renderer = captionsObj.optJSONObject("playerCaptionsTracklistRenderer") 
                ?: return null
            val tracksArray = renderer.optJSONArray("captionTracks") ?: return null
            
            val tracks = mutableListOf<CaptionTrack>()
            for (i in 0 until tracksArray.length()) {
                val trackObj = tracksArray.getJSONObject(i)
                
                val baseUrl = trackObj.optString("baseUrl", "")
                if (baseUrl.isEmpty()) continue
                
                val nameObj = trackObj.optJSONObject("name")
                val name = nameObj?.optString("simpleText", "") ?: ""
                val languageCode = trackObj.optString("languageCode", "")
                val vssId = trackObj.optString("vssId", "")
                val isTranslatable = trackObj.optBoolean("isTranslatable", false)
                val tlArray = trackObj.optJSONArray("translationLanguages")
                val tls = mutableListOf<String>()
                if (tlArray != null) {
                    for (j in 0 until tlArray.length()) {
                        val tlo = tlArray.optJSONObject(j)
                        val lc = tlo?.optString("languageCode")
                        if (!lc.isNullOrEmpty()) tls.add(lc)
                    }
                }
                
                tracks.add(CaptionTrack(baseUrl, name, languageCode, vssId, isTranslatable, tls))
                
                Log.d(TAG, "🎬YT [API-4t] Track: name='$name' lang=$languageCode vssId=$vssId transl=$isTranslatable tl=[${tls.joinToString()}] urlHead=${baseUrl.take(80)}...")
            }
            
            return if (tracks.isNotEmpty()) Captions(tracks) else null
        } catch (e: Exception) {
            Log.e(TAG, "🎬YT ❌ [API] Error parsing captions", e)
            return null
        }
    }
    
    /**
     * 解析视频详情
     */
    private fun parseVideoDetails(root: JSONObject): VideoDetails? {
        try {
            val detailsObj = root.optJSONObject("videoDetails") ?: return null
            
            val videoId = detailsObj.optString("videoId", "")
            val title = detailsObj.optString("title", "")
            val lengthSeconds = detailsObj.optString("lengthSeconds", "0")
            val author = detailsObj.optString("author", "")
            
            if (videoId.isEmpty()) return null
            
            return VideoDetails(videoId, title, lengthSeconds, author)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing video details", e)
            return null
        }
    }
}

