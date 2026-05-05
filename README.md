# ntfy Android App
This is the Android app for [ntfy](https://github.com/binwiederhier/ntfy) ([ntfy.sh](https://ntfy.sh)). You can find the app in [F-Droid](https://f-droid.org/packages/io.heckel.ntfy/) or the [Play Store](https://play.google.com/store/apps/details?id=io.heckel.ntfy), 
or as .apk files on the [GitHub releases page](https://github.com/binwiederhier/ntfy-android/releases).

If you're downloading the APKs from GitHub, they are signed with a certificate with the following SHA-256 fingerprint: `6e145d7ae685eff75468e5067e03a6c3645453343e4e181dac8b6b17ff67489d`. You can also query the DNS TXT records for `ntfy.sh` to find this fingerprint.

## ✨ LiveUpdate 实时通知 (Android 16+)

本版本集成了 Android 16 的 **LiveUpdate** 实时通知功能。在支持的设备上（Android 16 原生系统、OPPO ColorOS 15+、vivo OriginOS 5 等），通知将自动以流体云/原子通知胶囊形态显示：

- 📱 锁屏实时预览
- ⌚ 常显屏幕 (AOD) 实时更新
- 💬 通知面板胶囊展开视图

**生效条件：**
- 系统：Android 16+（OPPO ColorOS 15+ / vivo OriginOS 5 / 华为鸿蒙等基于 Android 16 的厂商定制系统）
- 部分系统需要授予「显示动态通知」权限
- 附件下载进度通知和后台持续监听通知将优先展示为 LiveUpdate 形态

**无 LiveUpdate 的设备：** 自动降级为普通通知，不影响正常使用。


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
