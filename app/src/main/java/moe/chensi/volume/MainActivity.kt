@file:OptIn(ExperimentalMaterial3Api::class)

package moe.chensi.volume

import android.annotation.SuppressLint
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import moe.chensi.volume.ui.theme.VolumeManagerTheme
import org.joor.Reflect
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

@SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "VolumeManager.Activity"

        private const val SERVICE_NAME_SEPARATOR = ":"
    }

    private lateinit var application: MyApplication

    @Suppress("SameParameterValue")
    @SuppressLint("MissingPermission")
    private fun grantSelfPermission(permission: String) {
        var state = this@MainActivity.checkSelfPermission(permission)
        if (state == PackageManager.PERMISSION_GRANTED) {
            return
        }

        // Grant permission via `PackageManager` doesn't work on some Samsung devices
        val process = Reflect.onClass(Shizuku::class.java).call(
            "newProcess", arrayOf("pm", "grant", packageName, permission), null, null
        ).get<ShizukuRemoteProcess>()
        process.waitFor()

        state = this@MainActivity.checkSelfPermission(permission)
        if (state == PackageManager.PERMISSION_GRANTED) {
            return
        }

        throw SecurityException("Can't grant self permission $permission")
    }

    private fun enableAccessibilityService(name: String) {
        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)

        var enabledAccessibilityServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        if (enabledAccessibilityServices.isNullOrBlank()) {
            enabledAccessibilityServices = name
        } else if (enabledAccessibilityServices.contains(name)) {
            return
        } else {
            enabledAccessibilityServices += SERVICE_NAME_SEPARATOR + name
        }

        Settings.Secure.putString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            enabledAccessibilityServices
        )

        enabledAccessibilityServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (enabledAccessibilityServices == null || !enabledAccessibilityServices.contains(name)) {
            throw SecurityException("Can't enable accessibility service $name")
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        application = super.getApplication() as MyApplication
        val manager = application.manager

        setContent {
            var showAll by remember { mutableStateOf(false) }
            // settings dialog removed; use SettingsActivity instead

            VolumeManagerTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(), topBar = {
                        TopAppBar(title = { Text("Volume Manager") }, actions = {
                            if (manager.shizukuPermission) {
                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                        TooltipAnchorPosition.Below,
                                        12.dp
                                    ),
                                    tooltip = { PlainTooltip { Text(if (showAll) "Hide inactive or hidden apps" else "Show all apps") } },
                                    state = rememberTooltipState()
                                ) {
                                    IconButton(
                                        onClick = { showAll = !showAll }) {
                                        Icon(
                                            if (showAll) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = if (showAll) "Hide inactive or hidden apps" else "Show all apps"
                                        )
                                    }
                                }

                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                        TooltipAnchorPosition.Below,
                                        12.dp
                                    ),
                                    tooltip = { PlainTooltip { Text("Settings") } },
                                    state = rememberTooltipState()
                                ) {
                                    val ctx = LocalContext.current
                                    IconButton(onClick = {
                                        try {
                                            ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to open SettingsActivity from MainActivity", e)
                                        }
                                    }) {
                                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                                    }
                                }
                            }
                        })
                    }) { innerPadding ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                    ) {
                        if (manager.shizukuReady) {
                            if (manager.shizukuPermission) {
                                AccessibilityService()
                                NotificationListenerPermission()
                                AppVolumeList(manager.apps.values, showAll)
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(
                                        16.dp, Alignment.CenterVertically
                                    )
                                ) {
                                    Text("Shizuku is installed and enabled")
                                    Text(
                                        textAlign = TextAlign.Center,
                                        text = "Allow volume manager to access Shizuku?"
                                    )

                                    Button(onClick = { Shizuku.requestPermission(0) }) {
                                        Text(text = "Add permission")
                                    }
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(
                                    16.dp, Alignment.CenterVertically
                                )
                            ) {
                                Text("Waiting for Shizuku...")
                                Text(
                                    textAlign = TextAlign.Center,
                                    text = "Make sure Shizuku is installed and enabled"
                                )
                            }
                        }
                    }
                    // settings dialog deprecated; open SettingsActivity instead
                }
            }
        }
    }

    data class ErrorInfo(val message: String, val stack: String)

    @Composable
    fun AccessibilityService() {
        var permissionGranted by remember { mutableStateOf(false) }
        var serviceEnabled by remember { mutableStateOf(false) }
        var errorInfo by remember { mutableStateOf<ErrorInfo?>(null) }

        LaunchedEffect(0) {
            try {
                grantSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                permissionGranted = true
            } catch (e: Exception) {
                Log.e(TAG, "Can't add WRITE_SECURE_SETTINGS permission", e)
                errorInfo = ErrorInfo(e.message!!, e.stackTraceToString())
                return@LaunchedEffect
            }

            try {
                enableAccessibilityService(
                    ComponentName(this@MainActivity, Service::class.java).flattenToString()
                )
                serviceEnabled = true
            } catch (e: Exception) {
                Log.e(TAG, "Can't enable accessibility service", e)
            }
        }

        errorInfo?.let { info ->
            val context = LocalContext.current

            AlertDialog(
                onDismissRequest = { errorInfo = null },
                title = { Text("Can't add permission") },
                text = { Text(info.message) },
                confirmButton = {
                    Button(onClick = { errorInfo = null }) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val clip = ClipData.newPlainText("error_message", info.stack)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy full message")
                    }
                })
        }

        Column {
            Text(text = "Permission granted: ${if (permissionGranted) "Yes" else "No"}")
            Text(text = "Service enabled: ${if (serviceEnabled) "Yes" else "No"}")
        }
    }
    
    @Composable
    fun NotificationListenerPermission() {
        val context = LocalContext.current
        var listenerEnabled by remember { mutableStateOf(false) }
        
        LaunchedEffect(Unit) {
            // Check if notification listener is enabled
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            val componentName = ComponentName(context, LyricsListenerService::class.java).flattenToString()
            listenerEnabled = enabledListeners?.contains(componentName) == true
            
            Log.d(TAG, "Enabled listeners: $enabledListeners")
            Log.d(TAG, "Component name: $componentName")
            Log.d(TAG, "Is enabled: $listenerEnabled")
        }
        
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "通知监听权限: ${if (listenerEnabled) "已开启 ✅" else "未开启"}")
            
            if (!listenerEnabled) {
                Button(onClick = {
                    // Open notification listener settings
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    context.startActivity(intent)
                    Toast.makeText(context, "请找到并开启 VolumeManager 的通知访问权限", Toast.LENGTH_LONG).show()
                }) {
                    Text("开启通知监听")
                }
                
                Text(
                    text = "⚠️ 开启后需要播放音乐才能看到通知",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    text = "✅ 通知监听已启用",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                )
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        // Request rebind to trigger onListenerConnected (API 24+)
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                android.service.notification.NotificationListenerService.requestRebind(
                                    ComponentName(context, LyricsListenerService::class.java)
                                )
                                Toast.makeText(context, "已请求重新绑定服务，查看 logcat", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "需要 Android 7.0+", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "重新绑定失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("🔄 重启")
                    }
                    
                    Button(onClick = {
                        // Use ADB command to list notifications
                        Toast.makeText(context, "在终端运行：adb shell dumpsys notification", Toast.LENGTH_LONG).show()
                    }) {
                        Text("📋 查看通知")
                    }
                }
                
                Text(
                    text = "💡 调试步骤：\n" +
                            "1. 点击'重启'按钮\n" +
                            "2. adb logcat -s LyricsListener\n" +
                            "3. 播放目标音乐APP\n" +
                            "4. 如果没日志，可能该APP使用悬浮窗而非通知",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    @Composable
    fun OverlaySettingsDialog(onDismiss: () -> Unit) {
        val context = LocalContext.current
        val prefs = context.getSharedPreferences("overlay_settings", Context.MODE_PRIVATE)
        var hideSeconds by remember { mutableStateOf(prefs.getInt("overlay_hide_timeout_sec", 30)) }
        var sticky by remember { mutableStateOf(prefs.getBoolean("overlay_sticky", false)) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Overlay Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("隐藏时间(秒)")
                        // very small text input substitute using buttons to avoid full TextField import/boilerplate
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { if (hideSeconds > 0) hideSeconds -= 5 }) { Text("-5") }
                            Button(onClick = { if (hideSeconds > 0) hideSeconds -= 1 }) { Text("-1") }
                            Text("$hideSeconds s", modifier = Modifier.padding(horizontal = 8.dp))
                            Button(onClick = { hideSeconds += 1 }) { Text("+1") }
                            Button(onClick = { hideSeconds += 5 }) { Text("+5") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("不消失(仅点击空白处关闭)")
                        androidx.compose.material3.Switch(checked = sticky, onCheckedChange = { sticky = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            val videoId = text?.let { extractVideoIdFromUrlStrict(it) }
                            if (videoId != null) {
                                val intent = Intent("moe.chensi.volume.YOUTUBE_VIDEO_CHANGED").apply {
                                    putExtra("videoId", videoId)
                                }
                                context.sendBroadcast(intent)
                                Toast.makeText(context, "从剪贴板导入: $videoId", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "剪贴板未识别到 YouTube 链接", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("从剪贴板导入链接") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    prefs.edit()
                        .putInt("overlay_hide_timeout_sec", hideSeconds)
                        .putBoolean("overlay_sticky", sticky)
                        .apply()
                    onDismiss()
                    Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        )
    }

    // 严格解析 YouTube 链接中的 videoId（与 Service 中一致）
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
