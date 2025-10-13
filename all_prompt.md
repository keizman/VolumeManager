此版本未解决加减音量后音量条变动有延迟问题

尝试解决长安音量键持续增加/减少音量智慧在长安结束后才更新单次的音量 而不是持续增加/减少,音量在系统层和额 UI层都未持续变化
需要重新检查, 目前持续长安不会一直显示日志, 直到停止长按后显示最后一条日志 10-13 23:39:55.752  5093  5093 I VolumeManager.Service: animate out
10-13 23:39:56.068  5093  5093 I VolumeManager.Service: remove view
10-13 23:39:56.252  5093  5093 I VolumeManager.Service: onKeyEvent action = 0, key code = 24, shizuku permission = true
10-13 23:39:56.252  5093  5093 I VolumeManager.Service: VOLUME_UP repeat count = 0
10-13 23:39:56.254  5093  5093 I VolumeManager.Service: add view
10-13 23:39:56.266  5093  5093 I VolumeManager.Service: animate in
10-13 23:39:56.276  5093  5093 I VolumeManager.Service: onAttachedToWindow
10-13 23:39:58.458  5093  5093 I VolumeManager.Service: onKeyEvent action = 1, key code = 24, shizuku permission = true

发现问题了！从日志看，系统并没有持续发送 ACTION_DOWN 事件。长按期间只有：首次按下：action = 0 (ACTION_DOWN), repeat count = 0 松开时：action = 1 (ACTION_UP)
中间没有重复事件！这意味着我需要在按下和松开之间持续轮询音量变化。让我使用协程来实现









我目前有个想法, 我希望在息屏状态展示目前播放的歌词, 这可以在我通过某个键后触发, 在使用这个项目过程中无突然发现其支持在黑屏状态下展示状态栏, 意味着只要我能打通歌词到 APK 的中间环节, 我就可以实现让其在黑屏状态下按指定键后展示歌词, 调研一下是这样吗. 比如我目前使用的酷狗播放器, 其能成功在正常状态下在屏幕上方展示歌词, 我们可以先尝试一下在黑屏状态下展示指定滚动的字符, 以此验证我们的想法是否可行, 对了, 记得调整展示持续时间长一些, 这能让我看到更长时间的内容, 此后悬浮面板的