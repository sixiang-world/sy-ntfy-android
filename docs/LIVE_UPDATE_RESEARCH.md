# Android LiveUpdate / ColorOS 流体云 研究报告

时间：2026-05-06
目标：为 ntfy-android 的 ColorOS LiveUpdate（流体云）支持提供完整的技术调研

---

## 一、核心发现摘要

### 1. ColorOS 流体云的双通道架构（最重要发现）

来自 2025-10-17 的官方信息：

> "OPPO ColorOS 16 的流体云完整接入了原生 Android 16 的 Live Updates API，**只要应用遵循谷歌实时活动 API 开发设计规范，就能直接打通适配 OPPO 的流体云**。"

OPPO ColorOS 设计总监陈希补充：

> "流体云从一开始的设计就是开放生态，有完整的开发文档和 API 接口，去年到今年 OPPO 的架构师和谷歌方面多次共创讨论，最后选择了**成本高但最完善的双兼容方案**，大大拓宽了流体云的接入总量。"

**结论**：ColorOS 16（Android 16 底層）采用双兼容方案：
- **通道 A（标准）**：原生 Android 16 Live Updates API → 遵循谷歌规范的应用直接适配
- **通道 B（私有）**：ColorOS 私有 `android.requestPromotedOngoing` extra → 向后兼容

### 2. Android 16 官方 Live Updates 规范（谷歌文档）

根据官方文档，Live Update 通知必须满足以下**全部条件**：

| # | 条件 | 说明 |
|---|------|------|
| 1 | 标准 Style | 必须是 Standard / BigTextStyle / CallStyle / ProgressStyle / MetricStyle 之一 |
| 2 | POST_PROMOTED_NOTIFICATIONS 权限 | 必须在 manifest 声明 |
| 3 | requestPromotedOngoing | 调用 `setRequestPromotedOngoing(APROMOTED)` 或设置 `EXTRA_REQUEST_PROMOTED_ONGOING` |
| 4 | FLAG_ONGOING_EVENT | `notification.flags \|= 2` |
| 5 | contentTitle | 必须设置内容标题 |
| 6 | **禁止 customContentView** | **不能使用 RemoteViews** |
| 7 | 非 group summary | 不能是组摘要通知 |
| 8 | 不设置 colorized(true) | 不能对通知着色 |

**关键冲突**：ntfy-android 当前使用 `setCustomBigContentView(expandedViews)` 和 `setCustomContentView(collapsedViews)` — 这**违反条件 6**。

### 3. `setLiveUpdateEnabled` 反射调用分析

ntfy NotificationService.kt 第 705-713 行：

```kotlin
// 通过反射调用 NotificationChannel.setLiveUpdateAllowed(true)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    try {
        val method = NotificationChannel::class.java.getMethod(
            "setLiveUpdateEnabled", Boolean::class.java
        )
        method.invoke(channel, true)
    } catch (e: Exception) {
        // ignore
    }
}
```

**结论**：`setLiveUpdateEnabled` 是 `NotificationChannel` 的框架 API（Android 8.0+）。ntfy 用反射调用，这个方法**本身没问题**，可以保留。但注意这只是让 channel 允许 LiveUpdate，实际触发还需要满足其他条件。

---

## 二、Cmd2Gui 实现分析（base.apk 反编译）

### 关键源码（svc.java，FluidCloud action 分支）

```java
// Action: "FluidCloud"，SDK 版本检查
if (Build.VERSION.SDK_INT < 36) {
    Toast.makeText(this, "流体云功能仅支持 Android 16 (ColorOS 16) 及以上版本", 1).show();
    stopSelf();
    return;
}

// 构建 NotificationChannel
NotificationChannel notificationChannel = new NotificationChannel(
    "fluid_cloud_channel", "实时状态通知", 4);
notificationChannel.setDescription("用于显示流体云动态信息");
// ... channel 配置 ...
notificationManager.createNotificationChannel(notificationChannel);

// 设置 android.requestPromotedOngoing
if (cVar.f100k == null) {
    cVar.f100k = new Bundle();
}
cVar.f100k.putBoolean("android.requestPromotedOngoing", true);

// 使用 BigTextStyle
cVar.c(bVar2);  // → builder.setStyle(new Notification.BigTextStyle().setBigContentTitle().bigText())
```

### Cmd2Gui 通知构建关键步骤

| 步骤 | 说明 |
|------|------|
| SDK 检查 | `Build.VERSION.SDK_INT < 36` → 不支持 |
| channel | 独立 channel `"fluid_cloud_channel"` |
| requestPromotedOngoing | `cVar.f100k.putBoolean("android.requestPromotedOngoing", true)` — **通过 Notification extras bundle** |
| 样式 | `Notification.BigTextStyle` — **不使用 RemoteViews** |
| 样式代码 | `new Notification.BigTextStyle().setBigContentTitle().bigText()` |
| 通知构造 | 使用自定义通知类 `f.c`，内部使用 `Notification.Builder`（框架类，**非** NotificationCompat） |

### Cmd2Gui 的通知类 `f.c`（f/c.java，反编译）

关键构造逻辑：
```java
// 构建 Notification.Builder（框架原生类，非 Compat）
Notification.Builder builder = new Notification.Builder(svcVar, str3);

// ... 各种 set* 配置 ...

// SDK >= 29: setAllowSystemGeneratedContextualActions
if (i17 >= 29) {
    e.a((Notification.Builder) bVar.f88b, this.f102m);
    e.b((Notification.Builder) bVar.f88b);
}

// SDK >= 36: g.a — setShortCriticalText (LiveUpdate 相关)
if (i17 >= 36) {
    g.a((Notification.Builder) bVar.f88b);
}

// BigTextStyle
if (bVar2 != null) {
    new Notification.BigTextStyle(builder3)
        .setBigContentTitle((CharSequence) bVar2.f88b)
        .bigText((CharSequence) bVar2.f89c);
}

// extras bundle（含 android.requestPromotedOngoing）
((Notification.Builder) bVar.f88b).setExtras(this.f100k);
```

### Cmd2Gui 的 `g.a()` 方法（f/g.java）

```java
// SDK >= 36 时调用
public abstract class g {
    public static void a(Notification.Builder builder) {
        builder.setShortCriticalText(null);  // Android 16 新 API
    }
}
```

**重要**：`setShortCriticalText(null)` 是 Android 16 framework Notification.Builder 的 API，与 LiveUpdate 直接相关。

---

## 三、ntfy-android 当前实现与问题清单

### ntfy NotificationService.kt 关键代码段

**文件**：`app/src/main/java/io/heckel/ntfy/msg/NotificationService.kt`

#### 3.1 POST_PROMOTED_NOTIFICATIONS 权限缺失
- **状态**：AndroidManifest.xml 只有 `POST_NOTIFICATIONS`
- **缺失**：`<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS"/>`
- **来源**：第 219/229 行注释已注明需要此权限，但 manifest 未声明

#### 3.2 重复代码块 bug（已识别，未修复）
- **位置**：第 276-285 行 vs 第 287-296 行
- **第一个块**：
  ```kotlin
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      builder.javaClass.getMethod(
          "setRequestPromotedOngoing", Boolean::class.javaPrimitiveType
      )?.invoke(builder, true)
  }
  ```
- **第二个块**：
  ```kotlin
  builder.javaClass.getMethod(
      "setRequestPromotedOngoing", Boolean::class.javaPrimitiveType
  )?.invoke(builder, true)
  ```
- **问题**：第二个块**没有** `if (Build.VERSION.SDK_INT >= S)` 保护，缺失 `Build.VERSION_CODES.S` (`SDK_INT`)
- **影响**：在 SDK < S 的设备上可能抛出 NoSuchMethodException

#### 3.3 RemoteViews 与官方规范冲突（最关键）
- **位置**：第 357 行 `builder.setCustomBigContentView(expandedViews)`、第 374 行 `builder.setCustomContentView(collapsedViews)`
- **问题**：使用 `setCustomBigContentView` / `setCustomContentView`（RemoteViews）违反 Android 16 LiveUpdate 规范条件 6
- **说明**：Cmd2Gui 使用 `Notification.BigTextStyle`（无 RemoteViews），而 ntfy 使用自定义 RemoteViews
- **影响**：在原生 Android 16（Pixel 等）上不会被提升为 LiveUpdate

#### 3.4 setLiveUpdateEnabled 反射调用
- **位置**：第 705-713 行（createNotificationChannel 内）
- **状态**：保留，无害（`setLiveUpdateEnabled` 是 NotificationChannel 的框架 API）
- **评估**：可以保留，不影响

---

## 四、技术路线分析

### 两条实现路径对比

| 特性 | Cmd2Gui 方式（框架 Builder） | ntfy 方式（Compat Builder） |
|------|---------------------------|---------------------------|
| 使用的 Builder | `Notification.Builder`（框架） | `NotificationCompat.Builder`（AndroidX） |
| requestPromotedOngoing | `extras.putBoolean("android.requestPromotedOngoing", true)` | 反射调用 `setRequestPromotedOngoing()` |
| 样式 | `Notification.BigTextStyle` | `setCustomBigContentView(RemoteViews)` |
| RemoteViews | ❌ 不使用 | ✅ 使用（违规） |
| 标准 API 兼容性 | ✅ 符合规范 | ❌ 违反条件 6 |
| ColorOS 私有 API 兼容 | ✅ 支持 | ✅ 支持（via a997dc82） |

### 为什么 Cmd2Gui 能工作

1. **ColorOS 私有通道**：`android.requestPromotedOngoing` extra 是 ColorOS 私有机制，不走标准 LiveUpdate 路径，所以 RemoteViews 不影响
2. **BigTextStyle**：`Notification.BigTextStyle` 是标准样式，ColorOS 系统可以渲染
3. **独立 channel**：`"fluid_cloud_channel"` 有独立配置

### 为什么 ntfy 需要改变（如果要支持原生 Android 16）

ntfy 的 `setCustomBigContentView(RemoteViews)` 会导致：
- 在原生 Android 16（Pixel 6/7/8 等）上：**不会**被提升为 LiveUpdate，只能显示为普通通知
- 在 ColorOS 15/16 上：可能通过私有 `android.requestPromotedOngoing` 显示为流体云，但不一定保证

**但注意**：ntfy 主要用户是 ColorOS/OriginOS 等国产 ROM 用户。如果 ColorOS 的私有通道已经能正常工作，是否需要投入大量工作去支持原生 Android 16 的标准路径，取决于产品策略。

---

## 五、修复优先级建议

### P0（必须）

1. **修复重复代码块 bug**：第 287-296 行添加 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` 保护
2. **添加 POST_PROMOTED_NOTIFICATIONS 权限**：AndroidManifest.xml 声明

### P1（强烈建议）

3. **评估是否需要移除 RemoteViews**：如果要支持原生 Android 16 的标准 LiveUpdate，需要将 `setCustomBigContentView(expandedViews)` 和 `setCustomContentView(collapsedViews)` 替换为标准 BigTextStyle

### P2（可选）

4. **移除无效的 setLiveUpdateEnabled 反射调用**：如果确认 setLiveUpdateEnabled 没有实际作用，可以移除以简化代码（但保留也无害）

---

## 六、关键参考资料

| 来源 | 链接 | 关键内容 |
|------|------|---------|
| OPPO 官方（2025-10-17）| ithome/qq | ColorOS 16 流体云双兼容方案，标准 API 直接适配 |
| IT之家（2025-07-03）| new.qq.com | Android 16 QPR1 Beta 2 引入 POST_PROMOTED_NOTIFICATIONS + requestPromotedOngoing API |
| Android Authority（2025-07-03）| so.html5.qq.com | Live Updates 功能详解，类似 iOS Live Activities |
| Android 16 官方文档 | developer.android.google.cn/preview | Android 16 新特性介绍 |
| Android 16 官方 LiveUpdate 规范 | developer.android.google.cn/develop/ui/compose/notifications/live-update | 8 条必须满足的条件 |

---

## 七、附录：Cmd2Gui 通知类关键字段

```java
public final class c {
    public final Notification f103n;         // 底层 Notification 对象
    public Bundle f100k;                      // extras bundle（含 requestPromotedOngoing）
    public CharSequence f94e;                // contentTitle
    public CharSequence f95f;                // contentText
    public int f96g;                         // priority
    public String f99j;                      // category
    public b f98i;                          // BigTextStyle holder
    public final ArrayList f91b;             // action 列表
    // ...
}
```

通知通过 `cVar.f100k.putBoolean("android.requestPromotedOngoing", true)` 设置私有 API flag，通过 `cVar.c(bVar2)` 设置 BigTextStyle（bVar2 持有 BigTextStyle 数据），最终通过 `Notification.Builder.setExtras()` 将 extras bundle 传入框架。
