# 🧠 YouTube Subtitles — Technical Notes

This file merges prior analysis and solution notes into a concise reference.

## Architecture

- Notification listener (MediaSession): extracts playback updates and videoId, broadcasts to Service.
- Innertube player response: fetches caption tracks and downloads JSON3 subtitles.
- Cache: XML per videoId at `externalFilesDir/youtube_subtitles/<videoId>.xml`.
- Mapping: `md5(artist + "\n" + title)` → `videoId` at `externalFilesDir/youtube_map/<key>.txt` (append-only, tiny LRU).

## Caption Track Selection

Priority: `en` → translatable→`en` (append `tlang=en`) → `zh` → first.

## Time Model

Effective time (ms) = `position + (now - lastUpdateTime) * speed + latencyMs`.

`latencyMs` is user-configurable; defaults to 1000ms.

## Overlay & Display

- Text-only overlay (transparent) or semi-transparent rounded background.
- Merging: normalize whitespace; merge incoming fragment with the last line if total ≤ 120 chars, else create a new line.
- De-dup: ignore if last line already ends with the incoming text.
- Keep up to 3 lines (drop oldest when adding a 4th).

## Settings

All settings are saved on explicit Save, then broadcast to Service:

- `overlay_hide_timeout_sec` (int)
- `overlay_sticky` (bool)
- `overlay_latency_sec` (float)
- `overlay_text_only` (bool)
- `overlay_font_sp` (float)
- `overlay_text_color` (hex string)

## Logging Keys

- `[INIT]`, `[UI]`, `[CFG]`, `[TICK]`, `[READY]`, `[PREFETCH]`.

