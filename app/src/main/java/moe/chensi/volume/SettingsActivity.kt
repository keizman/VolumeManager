package moe.chensi.volume

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color

class SettingsActivity : ComponentActivity() {
    companion object {
        private const val TAG = "SettingsActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(color = MaterialTheme.colorScheme.background) {
                SettingsScreen()
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val prefs = getSharedPreferences("overlay_settings", Context.MODE_PRIVATE)
        var hideSecText by remember { mutableStateOf(prefs.getInt("overlay_hide_timeout_sec", 30).toString()) }
        var sticky by remember { mutableStateOf(prefs.getBoolean("overlay_sticky", false)) }
        var latencyText by remember { mutableStateOf(prefs.getFloat("overlay_latency_sec", 1.0f).toString()) }
        var textOnly by remember { mutableStateOf(prefs.getBoolean("overlay_text_only", true)) }

        fun broadcastApply() {
            try {
                val intent = Intent(Service.ACTION_OVERLAY_SETTINGS_CHANGED).apply {
                    setPackage(applicationContext.packageName)
                }
                sendBroadcast(intent)
            } catch (_: Throwable) {}
        }

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "Overlay Settings", style = MaterialTheme.typography.titleLarge)

            // 输入框：默认隐藏时间（秒，整数）
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Default disappear time (seconds)")
                TextField(
                    value = hideSecText,
                    onValueChange = { newText -> hideSecText = newText },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            // 输入框：字幕延迟补偿（秒，浮点）
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Latency compensation (seconds, float)")
                TextField(
                    value = latencyText,
                    onValueChange = { nt -> latencyText = nt },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }

            // 输入框：字体大小（sp，浮点）
            var fontSpText by remember { mutableStateOf(prefs.getFloat("overlay_font_sp", 24f).toString()) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Font size (sp, float)")
                TextField(
                    value = fontSpText,
                    onValueChange = { t -> fontSpText = t },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }

            // 输入框：文本颜色（十六进制，如 #00D9FF 或 00D9FF）
            var textColorText by remember { mutableStateOf(prefs.getString("overlay_text_color", "#00D9FF") ?: "#00D9FF") }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Text color (hex, e.g., #00D9FF)")
                TextField(
                    value = textColorText,
                    onValueChange = { t -> textColorText = t },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
            }

            // 预览（与最终展示样式一致）
            val previewFontSp = fontSpText.toFloatOrNull() ?: prefs.getFloat("overlay_font_sp", 24f)
            val previewLineH = (previewFontSp * 1.42f)
            val previewColor = run {
                val s = textColorText
                try {
                    val c = android.graphics.Color.parseColor(if (s.startsWith("#")) s else "#$s")
                    androidx.compose.ui.graphics.Color(c)
                } catch (_: Throwable) { androidx.compose.ui.graphics.Color(0xFF00D9FF) }
            }
            val previewTextOnly = textOnly
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Preview")
                val previewContainer = if (!previewTextOnly) {
                    Modifier.background(Color(0f,0f,0f,0.88f), RoundedCornerShape(24.dp)).padding(12.dp)
                } else Modifier.padding(12.dp)
                val demo = "This is a sample preview text\nSecond line to test line spacing"
                androidx.compose.foundation.layout.Box(modifier = previewContainer) {
                    // 阴影层
                    Text(
                        text = demo,
                        fontSize = previewFontSp.sp,
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 1.dp, top = 2.dp),
                        lineHeight = previewLineH.sp
                    )
                    // 前景层
                    Text(
                        text = demo,
                        fontSize = previewFontSp.sp,
                        color = previewColor,
                        lineHeight = previewLineH.sp
                    )
                }
            }

            // 开关：不消失（仅点击空白处关闭）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Enable not disappear (sticky)")
                Switch(checked = sticky, onCheckedChange = { checked -> sticky = checked })
            }

            // 开关：仅文字（透明背景）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Text-only overlay (transparent background)")
                Switch(checked = textOnly, onCheckedChange = { v -> textOnly = v })
            }

            // 关闭按钮（返回）
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                Button(onClick = {
                    val sec = hideSecText.toIntOrNull()
                    val lat = latencyText.toFloatOrNull()
                    val fsp = fontSpText.toFloatOrNull()
                    val colorHex = textColorText
                    var changed = false
                    prefs.edit().apply {
                        if (sec != null && sec >= 0) {
                            putInt("overlay_hide_timeout_sec", sec)
                            Log.i(TAG, "Settings: overlay_hide_timeout_sec -> ${sec}s (saved)")
                            changed = true
                        }
                        if (lat != null && lat >= 0f) {
                            putFloat("overlay_latency_sec", lat)
                            Log.i(TAG, "Settings: overlay_latency_sec -> ${lat}s (saved)")
                            changed = true
                        }
                        if (fsp != null && fsp > 0f) {
                            putFloat("overlay_font_sp", fsp)
                            Log.i(TAG, "Settings: overlay_font_sp -> ${fsp}sp (saved)")
                            changed = true
                        }
                        if (!colorHex.isNullOrBlank()) {
                            putString("overlay_text_color", colorHex)
                            Log.i(TAG, "Settings: overlay_text_color -> ${colorHex} (saved)")
                            changed = true
                        }
                        putBoolean("overlay_sticky", sticky)
                        Log.i(TAG, "Settings: overlay_sticky -> ${sticky} (saved)")
                        putBoolean("overlay_text_only", textOnly)
                        Log.i(TAG, "Settings: overlay_text_only -> ${textOnly} (saved)")
                        changed = true
                    }.apply()
                    if (changed) broadcastApply()
                }) { Text("Save") }
                Button(onClick = { finish() }) { Text("Close") }
            }
        }
    }
}
