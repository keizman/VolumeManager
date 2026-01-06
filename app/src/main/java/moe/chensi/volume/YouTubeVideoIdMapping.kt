package moe.chensi.volume

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 简单的持久化映射：key -> videoId（Append-Only）
 * - key = md5(artist + "\n" + title)
 * - 存储为每键一个文件：externalFilesDir/youtube_map/<key>.txt，内容为 videoId
 * - 只新增，不修改（存在即返回）
 * - 不维护全量内存索引，避免占用内存
 * - 查找走“按键直达文件”的 O(1) 路径；命中后可做极小 LRU 缓存
 */
class YouTubeVideoIdMapping(private val context: Context) {
    companion object {
        private const val TAG = "YouTubeVideoIdMapping"
        private const val DIR_NAME = "youtube_map"

        fun md5Hex(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(String.format("%02x", b))
            }
            return sb.toString()
        }

        fun makeKey(artist: String, title: String): String {
            val a = artist.trim()
            val t = title.trim()
            val raw = "$a\n$t"
            val key = md5Hex(raw)
            try {
                Log.d(TAG, "makeKey: artist='${a.take(120)}', title='${t.take(120)}', md5=$key")
            } catch (_: Throwable) {}
            return key
        }
    }

    private val dir: File by lazy {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    // 极小 LRU 缓存（防抖常见重复查询），不保留大量内存
    private val cache = object : android.util.LruCache<String, String>(256) {}

    fun get(videoKey: String): String? {
        // LRU 命中快速返回
        cache.get(videoKey)?.let {
            try { Log.d(TAG, "get: LRU hit key=$videoKey -> ${it}") } catch (_: Throwable) {}
            return it
        }
        return try {
            val f = File(dir, "$videoKey.txt")
            try { Log.d(TAG, "get: probing file ${f.absolutePath}") } catch (_: Throwable) {}
            if (!f.exists()) {
                try { Log.d(TAG, "get: miss, file not exists for key=$videoKey") } catch (_: Throwable) {}
                return null
            }
            val v = f.readText().trim().ifEmpty { null }
            if (v != null) {
                cache.put(videoKey, v)
                try { Log.i(TAG, "get: disk hit key=$videoKey -> $v") } catch (_: Throwable) {}
            } else {
                try { Log.w(TAG, "get: file empty for key=$videoKey") } catch (_: Throwable) {}
            }
            v
        } catch (e: Exception) {
            Log.e(TAG, "Error reading mapping", e)
            null
        }
    }

    fun putIfAbsent(videoKey: String, videoId: String): Boolean {
        return try {
            val f = File(dir, "$videoKey.txt")
            if (f.exists()) {
                try { Log.d(TAG, "putIfAbsent: exists, skip key=$videoKey path=${f.absolutePath}") } catch (_: Throwable) {}
                return false
            }
            // 写入并强制落盘，避免仅缓存在内存导致的丢失
            java.io.FileOutputStream(f).use { fos ->
                val bytes = videoId.toByteArray(Charsets.UTF_8)
                fos.write(bytes)
                try {
                    fos.fd.sync() // 保守落盘
                } catch (_: Throwable) {
                    try { fos.channel.force(true) } catch (_: Throwable) {}
                }
                try { Log.i(TAG, "putIfAbsent: wrote ${bytes.size}B for key=$videoKey -> $videoId at ${f.absolutePath}") } catch (_: Throwable) {}
            }
            cache.put(videoKey, videoId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing mapping", e)
            false
        }
    }

    /**
     * 写入或替换：如果已存在且值不同，则覆盖
     * @return true 表示写入或替换发生
     */
    fun putOrReplace(videoKey: String, videoId: String): Boolean {
        return try {
            val f = File(dir, "$videoKey.txt")
            if (f.exists()) {
                val existing = try { f.readText().trim() } catch (_: Throwable) { null }
                if (existing == videoId) {
                    try { Log.d(TAG, "putOrReplace: identical, skip key=$videoKey -> $videoId") } catch (_: Throwable) {}
                    cache.put(videoKey, videoId)
                    return false
                }
                // 覆盖为新值
                java.io.FileOutputStream(f, false).use { fos ->
                    val bytes = videoId.toByteArray(Charsets.UTF_8)
                    fos.write(bytes)
                    try { fos.fd.sync() } catch (_: Throwable) { try { fos.channel.force(true) } catch (_: Throwable) {} }
                }
                cache.put(videoKey, videoId)
                try { Log.i(TAG, "putOrReplace: replaced key=$videoKey from '${existing ?: "?"}' to '$videoId'") } catch (_: Throwable) {}
                true
            } else {
                // 不存在则直接写入
                java.io.FileOutputStream(f).use { fos ->
                    val bytes = videoId.toByteArray(Charsets.UTF_8)
                    fos.write(bytes)
                    try { fos.fd.sync() } catch (_: Throwable) { try { fos.channel.force(true) } catch (_: Throwable) {} }
                }
                cache.put(videoKey, videoId)
                try { Log.i(TAG, "putOrReplace: wrote key=$videoKey -> $videoId at ${f.absolutePath}") } catch (_: Throwable) {}
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error putOrReplace mapping", e)
            false
        }
    }
}
