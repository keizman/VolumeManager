@echo off
chcp 65001 >nul
echo ====================================
echo 🚀 YouTube字幕系统 - 安装和测试
echo ====================================
echo.

echo [1/6] 安装最新APK...
adb install -r app\build\outputs\apk\debug\app-debug.apk
if %ERRORLEVEL% NEQ 0 (
    echo ❌ 安装失败！
    pause
    exit /b 1
)
echo ✅ 安装成功
echo.

echo [2/6] 强制停止VolumeManager...
adb shell am force-stop moe.chensi.volume
echo ✅ 已停止
echo.

echo [3/6] 重启NotificationListener服务...
adb shell cmd notification allow_listener moe.chensi.volume/moe.chensi.volume.LyricsListenerService
echo ✅ 服务已重启
echo.

echo [4/6] 清空日志...
adb logcat -c
echo ✅ 日志已清空
echo.

echo [5/6] 检查服务状态...
timeout /t 2 >nul
adb shell dumpsys notification | findstr "moe.chensi.volume"
echo.

echo [6/6] 开始监控日志...
echo ====================================
echo 📡 正在监控日志（按Ctrl+C停止）
echo ====================================
echo.
echo 👉 现在请打开YouTube播放视频
echo 👉 应该看到 🎬YT 的日志
echo.

adb logcat -s LyricsListener VolumeManager.Service

pause

