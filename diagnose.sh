#!/bin/bash

echo "🔍 YouTube字幕系统诊断工具"
echo "================================"
echo ""

# 1. 检查设备连接
echo "1️⃣ 检查设备连接..."
adb devices
echo ""

# 2. 检查NotificationListener权限
echo "2️⃣ 检查NotificationListener权限..."
LISTENERS=$(adb shell settings get secure enabled_notification_listeners)
echo "当前启用的监听器: $LISTENERS"

if [[ $LISTENERS == *"moe.chensi.volume"* ]]; then
    echo "✅ NotificationListener权限已开启"
else
    echo "❌ NotificationListener权限未开启！"
    echo "请运行: adb shell am start -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
fi
echo ""

# 3. 检查AccessibilityService
echo "3️⃣ 检查AccessibilityService..."
ACCESSIBILITY=$(adb shell settings get secure enabled_accessibility_services)
echo "当前启用的服务: $ACCESSIBILITY"

if [[ $ACCESSIBILITY == *"moe.chensi.volume"* ]]; then
    echo "✅ AccessibilityService已开启"
else
    echo "❌ AccessibilityService未开启！"
    echo "请运行: adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS"
fi
echo ""

# 4. 检查VolumeManager进程
echo "4️⃣ 检查VolumeManager进程..."
PROCESS=$(adb shell ps -A | grep "moe.chensi.volume")
if [ -z "$PROCESS" ]; then
    echo "❌ VolumeManager未运行"
else
    echo "✅ VolumeManager正在运行:"
    echo "$PROCESS"
fi
echo ""

# 5. 检查YouTube进程
echo "5️⃣ 检查YouTube进程..."
YT_PROCESS=$(adb shell ps -A | grep "youtube")
if [ -z "$YT_PROCESS" ]; then
    echo "❌ YouTube未运行"
else
    echo "✅ YouTube正在运行"
fi
echo ""

# 6. 测试日志
echo "6️⃣ 清空日志并开始监控..."
adb logcat -c
echo "✅ 日志已清空"
echo ""
echo "📡 现在请打开YouTube播放视频，然后查看日志："
echo ""
echo "运行以下命令查看日志："
echo "  adb logcat | grep '🎬YT'"
echo ""
echo "或者查看完整的LyricsListener日志："
echo "  adb logcat -s LyricsListener"
echo ""

