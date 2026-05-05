# ntfy Android App
This is the Android app for [ntfy](https://github.com/binwiederhier/ntfy) ([ntfy.sh](https://ntfy.sh)). You can find the app in [F-Droid](https://f-droid.org/packages/io.heckel.ntfy/) or the [Play Store](https://play.google.com/store/apps/details?id=io.heckel.ntfy), 
or as .apk files on the [GitHub releases page](https://github.com/binwiederhier/ntfy-android/releases).

If you're downloading the APKs from GitHub, they are signed with a certificate with the following SHA-256 fingerprint: `6e145d7ae685eff75468e5067e03a6c3645453343e4e181dac8b6b17ff67489d`. You can also query the DNS TXT records for `ntfy.sh` to find this fingerprint.

## ✨ LiveUpdate 实时通知 (Android 16+)

本版本集成了 Android 16 的 **LiveUpdate** 实时通知功能。在支持的设备上（Android 16 原生系统、OPPO ColorOS 15+、vivo OriginOS 5 等），通知将自动以流体云/原子通知胶囊形态显示：

- 📱 锁屏实时预览
- ⌚ 常显屏幕 (AOD) 实时更新
- 💬 通知面板胶囊展开视图

---

### LiveUpdate 触发机制

LiveUpdate 是纯客户端功能，**ntfy 服务器无需任何特殊配置**。通知能否触发 LiveUpdate 完全由客户端根据以下条件自行判断：

#### 客户端触发条件（NotificationService.kt 自动判断）

| 条件 | 说明 |
|------|------|
| Android 16+ | `Build.VERSION.SDK_INT >= 35 (VANILLA_ICE_CREAM)` |
| 通知权限 | 已授予 `POST_NOTIFICATIONS`（Android 13+） |
| 进度通知 | 附件下载进度 `attachment.progress in 0..99` |
| 持续通知 | `insistent=true`（后台轮询模式） |

当以上条件满足时，客户端自动设置：
- `setCategory(CATEGORY_PROGRESS)`
- `setOngoing(true/false)`
- `setProgress(100, progress, false)`
- `setLiveUpdateEnabled(true)`（反射调用）
- `requestPromotedOngoing()`（反射调用）
- `channel.setLiveUpdateAllowed(true)`（反射调用）

#### ntfy 服务器端：无需特殊请求头

LiveUpdate 触发**不需要服务器发送特殊 HTTP 头**。ntfy 现有的标准机制已足够：

```
# 普通文本消息 → LiveUpdate 胶囊（insistent 模式下）
# 附件下载进度 → LiveUpdate 进度条胶囊（通过 attachment.progress 字段）
```

- `X-Title`、`X-Message` 等标准 ntfy 头仍然生效
- LiveUpdate 的"实时更新"依赖客户端**主动刷新**通知（同一 notificationId 的 `notify()` 调用），而非服务器推送
- 进度信息通过 ntfy 的 `attachment.progress` 字段传递（客户端 `DownloadAttachmentWorker` 更新）

#### 客户端通知更新流程（进度条 LiveUpdate）

```
1. 服务器推送带 attachment 的消息（含 progress=0）
2. 客户端 NotificationService 构建通知：
   - 检测到 progress in 0..99 → 触发 LiveUpdate 条件
   - 设置 setProgress(100, 0, false) + setOngoing(true)
3. DownloadAttachmentWorker 下载过程中：
   - 每次进度变化 → 同一 notificationId 调用 notify()
   - 系统自动刷新 LiveUpdate 胶囊的进度条（无需重建通知）
4. 下载完成 → progress=100 → setOngoing(false) → LiveUpdate 结束
```

---

### 生效条件

| 项目 | 要求 |
|------|------|
| 系统版本 | Android 16+（ColorOS 15+ / OriginOS 5 / 华为鸿蒙等） |
| 通知权限 | 首次启动需授予「显示动态通知」权限 |
| 进度通知 | ntfy 消息携带附件（attachment）并包含 progress 字段 |
| 持续通知 | insistent 后台轮询模式（max priority + 定时拉取） |

**无 LiveUpdate 的设备：** 自动降级为普通通知，不影响正常使用。

**服务器要求：** 无特殊要求，现有任何 ntfy 服务器（自建或 ntfy.sh）均支持。


## Build
For up-to-date building instructions, please see the [official docs](https://docs.ntfy.sh/develop/#android-app).

## Translations
We're using [Weblate](https://hosted.weblate.org/projects/ntfy/) to translate the ntfy Android app. We'd love your participation.

<a href="https://hosted.weblate.org/engage/ntfy/">
<img src="https://hosted.weblate.org/widgets/ntfy/-/multi-blue.svg" alt="Translation status" />
</a>

## License
Made with ❤️ by [Philipp C. Heckel](https://heckel.io), distributed under the [Apache License 2.0](LICENSE).

Thank you to these fantastic resources:
* [RecyclerViewKotlin](https://github.com/android/views-widgets-samples/tree/main/RecyclerViewKotlin) (Apache 2.0)
* [Just another Hacker News Android client](https://github.com/manoamaro/another-hacker-news-client) (MIT)
* [Android Room with a View](https://github.com/googlecodelabs/android-room-with-a-view/tree/kotlin) (Apache 2.0)
* [Firebase Messaging Example](https://github.com/firebase/quickstart-android/blob/7147f60451b3eeaaa05fc31208ffb67e2df73c3c/messaging/app/src/main/java/com/google/firebase/quickstart/fcm/kotlin/MyFirebaseMessagingService.kt) (Apache 2.0)
* [Designing a logo with Inkscape](https://www.youtube.com/watch?v=r2Kv61cd2P4)
* [Foreground service](https://robertohuertas.com/2019/06/29/android_foreground_services/)
* [github/gemoji](https://github.com/github/gemoji) (MIT) for as data source for an up-to-date [emoji.json](https://raw.githubusercontent.com/github/gemoji/master/db/emoji.json) file
* [emoji-java](https://github.com/vdurmont/emoji-java) (MIT) has been stripped and inlined to use the emoji.json file
