# YouTube 字幕展示修复记录（2025-10-14）

本文记录本次“解析成功但不展示字幕”的问题排查过程、错误修复思路与最终正确方案；并附带后续可读性与可配置项的改动说明，供后续回溯与维护参考。

## 现象与影响
- 通知监听日志可见：持续收到 `PLAYBACK_UPDATE` 且包含有效 `videoId`。
- 无障碍 Service 侧无任何“加载字幕/更新字幕”的日志，界面只显示“等待字幕…”。
- 打开测试模式（+,-,-）后面板 3 秒自动消失。
- 有些视频没有英文原生轨道，之前逻辑仅支持 `en*`，导致返回空列表。

## 错误修复尝试（不正确/不完整）
- 仅等待 `VIDEO_CHANGED` 才触发加载：如果发端只广播 `PLAYBACK_UPDATE`（但携带了 `videoId`），Service 永远不加载。
- 粗暴延长定时器：忽略了“旧的 3 秒计时器未取消”的并发问题，依然会在 3 秒后被旧计时器关闭。
- 只支持英文轨道：中文视频无 `en*` 时，返回空，Service 永远拿不到 `currentSubtitles`。

## 正确修复方案
1. 用 `PLAYBACK_UPDATE` 触发加载
   - 若 `PLAYBACK_UPDATE` 携带的 `videoId` 与当前不同，则视作“视频变更”，立即加载字幕并清空滚动队列。
   - 文件：`app/src/main/java/moe/chensi/volume/Service.kt:508`

2. 广播可靠送达
   - 发送端（`LyricsListenerService`）为 `VIDEO_CHANGED`/`PLAYBACK_UPDATE` 显式设置包名，避免隐式广播被过滤。
   - 文件：`app/src/main/java/moe/chensi/volume/LyricsListenerService.kt:404, 476`
   - 接收端（`Service`）注册广播时使用 `Context.RECEIVER_NOT_EXPORTED`；`onServiceConnected` 打印注册完成与 `lastId`。
   - 文件：`app/src/main/java/moe/chensi/volume/Service.kt:590, 593-596, 597`

3. 启动/切换测试模式的兜底
   - 切换至测试模式（+,-,-）后，若 `MyApplication.lastYouTubeVideoId` 有值且不同于当前，立即加载字幕。
   - 文件：`app/src/main/java/moe/chensi/volume/Service.kt:180`
   - `MyApplication` 同时监听 `VIDEO_CHANGED` 与 `PLAYBACK_UPDATE` 以记录 `lastYouTubeVideoId`。
   - 文件：`app/src/main/java/moe/chensi/volume/MyApplication.kt:49`

4. 轨道选择顺序与翻译
   - 优先级：英文原生（`en*`）→ 可翻译到英文（自动附加 `tlang=en`）→ 中文（`zh*`）→ 兜底第一条。
   - 文件：`app/src/main/java/moe/chensi/volume/YouTubeSubtitleManager.kt:113, 150`
   - 补充解析 `isTranslatable/translationLanguages`：`app/src/main/java/moe/chensi/volume/YouTubeInnertubeApi.kt:32, 151`

5. 定时器与可见性
   - 每次启动计时器前，先取消并清空所有旧计时器，避免模式切换时旧计时器误触发 3 秒隐藏。
   - Sticky 模式（“不消失”）时不再启动任何计时器。
   - 文件：`app/src/main/java/moe/chensi/volume/Service.kt:211`

6. 日志链路
   - 关键节点均新增日志：接收广播、触发加载、轨道选择、下载/解析、队列滚动、计时器状态、Ticker eligibility。
   - 便于用 `logcat` 一眼看清是否满足“播放中 + 已有 videoId + 已有字幕 + 测试模式”。

## 可读性与设置改动
1. 字号微调与布局
   - 字幕字号由 `32sp` 调整为 `28sp`，整体更柔和。
   - 合并短片段：新字幕到达时优先与“最后一行”合并，合并后行宽不超过 80 字符，避免每行只有 1-2 个词。
   - 只保留最近 3 行，新增一行时从顶部丢弃最旧一行（非重复）。
   - 文件：`app/src/main/java/moe/chensi/volume/Service.kt:107, 678`

2. 设置生效
   - 支持“隐藏时间（秒）”与“不消失（点击空白处关闭）”两项：保存后立即重启计时器。
   - Sticky 默认关闭；可设置例如 60 秒隐藏时间。
   - 文件：`app/src/main/java/moe/chensi/volume/Service.kt`（计时器与设置弹窗）

## 结论
本次问题根因在于“仅等待 `VIDEO_CHANGED` 触发加载”与“旧计时器未取消”，辅以“只筛选英文轨道”。修复后，广播可靠送达且以 `PLAYBACK_UPDATE` 为触发条件；字幕轨道按照“en → en translated → zh → first”顺序选择；面板显示稳定且可配置；字幕按 3 行滚动展示并自动合并短片段，提高阅读性。

若后续仍遇到无 Service 日志的情况，可切换为显式 `ComponentName` 广播或引入 `LocalBroadcastManager` 双通道兜底。

