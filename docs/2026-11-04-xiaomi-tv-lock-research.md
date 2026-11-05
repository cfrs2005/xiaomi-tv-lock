# 小米电视 HyperOS TV 3 开机密码锁 — 互联网方案深度检索

日期：2026-11-04
设备：MiTV-MFTR0（小米电视 S55 Mini LED，freeguy，mt9653），Android 14，`OS3.0.106.0.UFTMATV`
检索目的：软件方案被 `killAllThirdApp` 封死后，找可落地的绕过路径与现成实现

---

## 一、根因已由第三方项目独立确认

Aerial TV 项目（`pengchujin/aerial-tv`）的设备笔记 `docs/devices/xiaomi-mitv-mftr0.md` 记录了同款机器的同一现象，与本次实测完全吻合：

> 待机（遥控器关机，实际是待机；`/proc/uptime` 不会归零）时，`MitvActivityManagerService.killAllThirdApp` → `killAllThirdAppSkipFgPkg` 对所有非系统 App 执行 `forceStopPackage`。

**豁免条件只有三条：**

| 条件 | 说明 | MFTR0 可用性 |
|---|---|---|
| 系统应用 | 需要系统签名 | ❌ |
| `MitvConfigParser.isAppInWhiteList(0x8000)` | 云端规则，白名单在 `/data/system/mitv_security_center.xml`（0600，shell 读不了） | ❌ 只有 `com.tencent.qqmusictv`、`com.xiaomi.mitv.light` 带此位 |
| STR 模式下的 `strKillWhiteList` | `Settings.System.str_killbackgroud_pkg_white_list`，需 `persist.sys.system_str == 1` | ⚠️ 该机此属性为空，且只在开机时读取 |

另有一条**不在上述三条里、但社区实测有效**的设置：`disable_back_home_whitelist`。

---

## 二、破解核心：为什么通知监听能活下来

这是整份检索里最有价值的发现。Android 对两类系统绑定的态度完全不同：

| 组件类型 | 被 force-stop 后的下场 |
|---|---|
| 无障碍服务（AccessibilityService） | 从 `enabled_accessibility_services` 里**永久移除**，必须人工重新开启 |
| 通知监听服务（NotificationListenerService） | **授权保留**，进程死后系统会**主动重新绑定** |

原因在 Google 官方文档 `Service bindings and process states` 里写得很清楚：通知监听是 system_server 的 system-to-app binding，设计目的就是"防止频繁冷启动"——通知随时可能到来，如果 unbind 了每次都要冷启动 app，开销远大于保持绑定。

**Aerial 的复活链（已在 MFTR0 实测成功）：**

```
小米待机 killAllThirdApp 强杀
  ↓
系统移除无障碍授权
  ↓
约 0.3–0.5 秒，系统主动重绑 RevivalListener（进程复活）
  ↓
onListenerConnected() 回调 → GuardSetup.ensureEnabled()
  ↓
用 WRITE_SECURE_SETTINGS 把守护服务重新写回 enabled_accessibility_services
  ↓
HomeGuardService.onServiceConnected() → 等 1.5 秒确认前台 → 接管
```

**实现要点：**

- `RevivalListener` 继承 `NotificationListenerService`，**不读任何通知内容**，纯粹利用"系统会重绑我"这一行为
- manifest 里声明 `directBootAware`（用户解锁前就能被绑定）
- 复活后必须用 `WRITE_SECURE_SETTINGS` 重新写回无障碍服务（因为无障碍授权已被清除）
- 关键日志：`notification listener binding died` 后紧跟 `binding:`，间隔 0.3–0.5 秒

**这就是我之前失败的真正原因**：我用的是 AlarmManager，而 force-stop 会连闹钟一起清除；通知监听的绑定是系统侧维持的，不在 app 的闹钟表里。

---

## 三、可直接写入的两条小米豁免设置

### 1. `disable_back_home_whitelist`（优先试这个）

```bash
adb shell settings get global disable_back_home_whitelist          # 先记原值
adb shell settings put global disable_back_home_whitelist disable
```

- Aerial 文档：「`disable_back_home_whitelist` 只认字面量 `disable`（写包名无效），设成 disable 后所有第三方应用待机都保活」
- 中关村在线同款教程：「该设置可跳过系统默认的回桌面+清理后台整套逻辑，不仅保障 Projectivy 常驻，其他已打开应用亦可能保留在后台；但待机功耗暂未实测」
- **作用于普通待机的 `killAllThirdApp`**，正是本次实测撞到的那条路径
- 副作用：待机功耗可能上升（未实测）；可随时写回原值

### 2. `str_killbackgroud_pkg_white_list`（STR 深睡路径）

```bash
adb shell settings get system str_killbackgroud_pkg_white_list     # 先记原值
adb shell settings put system str_killbackgroud_pkg_white_list com.example.app
```

- 逗号分隔，ADB 可写，不用 root
- **只在开机时读取**，改完必须完整重启
- 前提是走 `killAllThirdAppForStr()`（深睡 STR），MFTR0 的 `persist.sys.system_str` 为空，可能不适用
- 原值非 null 时要手动合并，不要覆盖

### 3. 长按电源键 = 真关机

小米社区官方说明：「轻按 power 键休眠，**长按 power 出现『小米电视正在关机』进入关机状态**」。

真关机后开机会走 `BOOT_COMPLETED`，与待机唤醒是完全不同的路径，`killAllThirdApp` 不参与。

---

## 四、现成开源/免费项目对比

| 项目 | 语言/许可 | 锁的方式 | 复活机制 | 小米适配 | 备注 |
|---|---|---|---|---|---|
| **KidTVLock** (`terranc/KidTVLock`) | 闭源免费 | 6 位方向键密码，全屏锁 | 未公开 | 仅在海信、Sony 实测 | **主打「开机或唤醒时锁」**，OTP 临时码，DO 防卸载，最贴近需求 |
| **TV App Lock** (`oleksii-klots/tv-app-lock`) | Kotlin / MIT | 4 位 PIN + 悬浮层，**按 app 逐个锁** | watchdog + alarm keep-alive | 测试过 Android 14 | 用 UsageStats 250ms 轮询**而非无障碍**（避免干扰遥控器 OK 键），自带告警：alarm 复活对强停固件可能无效 |
| **KidLock TV** (`yanmasharski/kidlock-tv`) | Kotlin / AGPL v3 | 6 位 PIN + 使用时长限制 | BootReceiver | 未标注 | 无障碍 + UsageStats，被强停后无障碍授权必丢 |
| **LemaTV** (`lemacarl/LemaTV`) | Kotlin / — | 方向键 4 方向图案解锁 | 无 | 未标注 | 无障碍 + 悬浮窗，Jetpack Compose TV |
| **FreeKiosk** (`RushB-fr/freekiosk`) | RN+Kotlin / MIT | Device Owner 真锁死，PIN 4-6 位 | DO 不受清理影响 | 未标注 | 需恢复出厂 + 无账号；Home/最近任务/通知全禁 |
| **Aerial TV** (`pengchujin/aerial-tv`) | Kotlin / MIT | 不锁屏，是换桌面 | **通知监听 RevivalListener（同款机型实测）** | ✅ MFTR0 已验证 | 复活机制的参考实现，源码在 `guard/` 目录 |
| **Kiosk Wall Panel** | 闭源付费 $9.99 | PIN 锁定单一页面 | 自动恢复 | 未标注 | 不用 ADB/root，但只是锁一个网页 |

---

## 五、针对本机的推荐路径（按代价排序）

### 路径 1：先试一行设置（成本 1 分钟，可逆）

```bash
adb shell settings put global disable_back_home_whitelist disable
```

重装之前的锁屏 APK（`tv-lock/`，代码还在），然后测待机唤醒。
如果服务不再被杀，`SCREEN_ON` 广播就能收到，锁屏会在亮屏瞬间弹出，**体验最好**。

验证方法：待机唤醒后立刻 `dumpsys activity services com.toy.tvlock`，有 `ServiceRecord` 就是没被杀。

### 路径 2：直接换现成 APK（成本 10 分钟）

装 `KidTVLock`（它主打的就是「开机或唤醒时锁」）。缺点：闭源、只验证过海信/Sony，小米上大概率撞同一个强停问题；但它的锁屏 UI 和密码交互是现成的，可以先跑一遍看看。

### 路径 3：重做锁屏，换成通知监听复活锚点（成本 1–2 小时）

在现有 `tv-lock` 基础上改三处：

1. 加 `RevivalListener extends NotificationListenerService`，声明 `directBootAware`，`onListenerConnected()` 里重新唤起 `LockService`
2. 前台检测从 `SCREEN_ON` 广播改为 **UsageStats 轮询**（每 250–500ms），理由：无障碍会干扰遥控器 OK 键，广播在强停后不可靠
3. 删除 AlarmManager 那套（force-stop 会清掉，已证明无效）

需要授权：
```bash
adb shell cmd notification allow_listener com.toy.tvlock/.RevivalListener
adb shell appops set com.toy.tvlock GET_USAGE_STATS allow
adb shell appops set com.toy.tvlock SYSTEM_ALERT_WINDOW allow
```

这是**成功率最高、体验最好**的软件方案，因为它是同款机型上唯一被实测验证过的复活路径。

### 路径 4：Device Owner 真锁死（成本 30 分钟，代价大）

```bash
adb shell dpm set-device-owner com.toy.tvlock/.MyDeviceAdminReceiver
```

- 前提：设备无已登录账号（当前有小米账号 已登录的小米账号，需先退出）
- 成功后可用 `setLockTaskPackages` + `startLockTask` 进 kiosk，Home 键失效、无法卸载
- 风险：DO 一旦设错，移除需要恢复出厂或 `dpm remove-active-device-owner`
- 这是"1 分钟都不行"的最硬实现，但会改变整台电视的使用方式

### 路径 5：物理兜底

智能插座断电。断电 = 真关机 = 走 `BOOT_COMPLETED`，每次开机必锁，100% 有效。

---

## 六、其他有用的事实

- **HOME 键接管失败的原因**（已有定论）：`com.android.server.pm.MitvSettings.isThirdpartyHomeAllowed(pkg)` → `isAppInWhitelistWithFlag(pkg, 4)`，白名单由云控下发到 `/data/system/mitv_security_center.xml`，只有系统进程能写。所以 `set-home-activity` 返回 Success 但无效。
- 白名单内容开机时会打到日志：`logcat -d -s MitvConfigParser`，能看 `[White] 包名=标志`。带 HOME 位（4）的 42 个包名里没有第三方桌面；带待机免杀位（`0x8000`）的只有 `com.tencent.qqmusictv` 和 `com.xiaomi.mitv.light`。
- 唤醒后系统会把 `global adb_enabled` 改成 0，需要重新开。
- `disable_back_home_whitelist` 只认字面量 `disable`，写包名无效。

## 参考来源

- Aerial TV 设备笔记（MFTR0 / MFFU0）：`github.com/pengchujin/aerial-tv/docs/devices/`
- 通知监听复活机制分析：`blog.gitcode.com/25e33bc5e8c2489da6623e6470da9d5f.html`
- Google 官方 `Service bindings and process states`：`developer.android.com/topic/performance/memory/guide/service-bindings`
- TV App Lock：`github.com/oleksii-klots/tv-app-lock`
- KidTVLock：`github.com/terranc/KidTVLock`
- KidLock TV：`github.com/yanmasharski/kidlock-tv`
- LemaTV：`github.com/lemacarl/LemaTV`
- FreeKiosk：`github.com/RushB-fr/freekiosk`
- 小米电视免 Root 换桌面（含两条豁免设置）：`wap.zol.com.cn/ask/x_35897442.html`
- Kiosk Mode 实战（Device Owner + startLockTask）：`juejin.cn/post/7698636164253089843`
