# 🎬 YouTube Subtitles — Guide

This guide consolidates Quick Start, flow, and test notes into a single place.

## 1) Install & Permissions

- Build & install: `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Enable Accessibility: `adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS`
- Enable Notification Listener: `adb shell am start -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`

## 2) Use

- Play a YouTube video (official or ReVanced).
- Toggle overlay: press Volume Up, then Volume Down twice within 2s.
- Text-only overlay shows subtitles centered on screen; tap outside to close.

## 3) How It Works (Short)

- LyricsListener broadcasts playback updates (position, state, speed, lastUpdateTime, videoId).
- Service treats changed `videoId` as a new video, loads subtitles (local XML cache or download), and updates every 250ms.
- Track order: `en → translatable→en (tlang=en) → zh → first`.
- Time = `reportedPosition + (now-lastUpdateTime)*speed + latencyMs`.

## 4) Settings (Save to apply)

- Default disappear time (seconds)
- Enable not disappear (sticky) — only tap outside closes
- Latency compensation (seconds, float) — e.g., 0.5
- Text-only overlay (transparent background)
- Font size (sp, float)
- Text color (hex, e.g. #00D9FF)

Open via app top-right Settings or overlay gear. Use the live Preview to confirm style, then Save to apply.

## 5) Logs (Useful filters)

- Subtitles: `VolumeManager.Service`
- Notification listener: `LyricsListener`
- All YouTube flow: search for "🎬YT"

Expected flow when playing:
```
🎬YT [11] Received PLAYBACK_UPDATE ... videoId=...
🎬YT [12b] Video ID changed via PLAYBACK_UPDATE -> ...; loading subtitles
🎬YT [16] ✅ Loaded N subtitles successfully
🎬YT [TICK] eligible=true ... latencyMs=...
🎬YT [17] Updating subtitle @...ms: ...
```

