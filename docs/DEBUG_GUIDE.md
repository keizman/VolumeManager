# 🧪 Debug Guide

## Quick

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb logcat | grep "🎬YT"
```

## Checklist

- Services running? Accessibility + Notification Listener enabled.
- `LyricsListener` logs present and broadcasting PLAYBACK_UPDATE.
- `VolumeManager.Service` receives updates and loads subtitles.
- `videoId` is non-null and stable across updates.

## Useful Commands

```bash
adb shell settings get secure enabled_notification_listeners
adb shell dumpsys notification
adb logcat -s LyricsListener VolumeManager.Service
```

## Expected Logs

```
🎬YT [11] Received PLAYBACK_UPDATE ...
🎬YT [12b] Video ID changed via PLAYBACK_UPDATE -> ...; loading subtitles
🎬YT [16] ✅ Loaded N subtitles successfully
🎬YT [TICK] eligible=true ... latencyMs=...
🎬YT [17] Updating subtitle @...ms: ...
```

## Common Causes

- No caption tracks available for the video.
- PLAYBACK_UPDATE not delivered (OEM limits implicit broadcasts) — fixed by explicit package.
- Latency too small — increase in Settings.

