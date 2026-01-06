# ⏱️ Separate Timers — Feature Notes

Summarizes separate timers design and testing.

## Behavior

- Volume panel: fixed 3s auto-hide.
- Subtitles panel: configurable hide timeout or sticky (no auto-hide).
- On any config change, Service cancels existing timers and starts the appropriate one.

## Settings

- `overlay_hide_timeout_sec` (seconds)
- `overlay_sticky` (true → no auto-hide)

## Implementation Highlights

- Always cancel old timers before starting new ones to avoid 3s hide race.
- Sticky mode returns early without starting timers.
- Logs: `IdleTimer:` cancel/start/expired.

## Quick Test

1. Open overlay; observe `IdleTimer:` logs.
2. Set 60s and Save; overlay should persist ~60s.
3. Enable sticky and Save; overlay persists until tapping outside.

