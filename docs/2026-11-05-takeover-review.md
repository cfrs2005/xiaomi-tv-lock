# 项目接管审查报告 — 2026-11-05

> 范围：仓库、源码、构建、测试现状、Aerial TV 机制对照。
> 性质：只读审查。未修改代码、未连接电视、未改系统设置、未注册 Device Owner、未重启、未恢复出厂。
> 审查者：接管 Agent。审查对象 commit：`95cb1d5`（main）。

---

## 1. 结论摘要

**当前工程是一个可构建、UI 可用的实验原型，但在目标设备上对「待机唤醒」这一半需求是 0% 生效，对「冷启动」这一半是「代码已写、真机从未验证」。同时存在三条不需要任何工具、只用遥控器就能绕过的路径。**

三条判定：

| 判定 | 依据 |
|---|---|
| **不满足需求，且不是「差一点」** | 待机时 `MitvActivityManagerService.killAllThirdApp` 对本应用执行 force-stop；进程死后 overlay 随窗口一起消失，唤醒后 PatchWall 直接可见。当前代码没有任何能在这条路径上存活的组件。 |
| **安全边界尚未成立** | 导出服务接受外部 `UNLOCK`；配置界面明文显示 PIN 并提供「解除锁定」按钮且有 LAUNCHER 入口。这两条意味着「锁」在威胁模型里还不存在。 |
| **失败方式目前是 fail-open** | 锁定状态 = 「overlay 对象是否为 null」，不落盘。服务一死就视为未锁。这与架构文档写的 fail-closed 相反。 |

必须纠正的一个认知偏差：**Device Owner 不是这个项目的救命稻草。** 待机 force-stop 是厂商行为，DO 不免疫（Aerial 与本地日志都指向同一结论）。DO 只能补「进程活着时 HOME/最近任务能不能被系统压住」的洞，补不了「进程已经死了」。**真正的硬依赖是复活锚点，不是 DO。**

---

## 2. 证据等级约定

本文所有结论标注等级，避免把推断当事实：

- **E1 代码事实**：读源码/产物可直接确认。
- **E2 构建事实**：本次实际执行构建得到。
- **E3 历史真机证据**：`docs/2026-11-04-*` 记录的用户实测（固件 `OS3.0.106.0.UFTMATV`）。
- **E4 同型机外部证据**：Aerial 在 `OS3.0.101.0.UFTMATV` 上的实测记录。
- **E5 推断**：尚未在本机验证。

---

## 3. 仓库与 Git 现状（E1）

| 项 | 实际值 |
|---|---|
| 当前分支 | `main` |
| HEAD | `95cb1d5` `docs(agent): introduce agent-first TV Lock workflow (#1)` |
| 与 origin/main | 一致（`95cb1d5`），工作区仅 `.workbuddy/` 未跟踪 |
| 提交总数 | 2 个（`51479ab` 首版代码 + `95cb1d5` Agent 工作体系） |
| 远端残留分支 | `origin/docs/agent-first-workflow`（7 个提交，与 main 内容无差异，squash 合并后未删） |
| CI | 无 `.github/workflows` |
| 单测 | 无任何测试文件、无 Gradle |
| 忽略规则 | `build/`、`*.apk`、`*.keystore` 已忽略，密钥未入库 |

**HANDOFF 里「PR #1 尚未合并」的说法已过期**——PR #1 已 squash 合并进 main。这是本次修正的第一个事实错误。

---

## 4. 构建与签名（E2）

本次实际执行 `./build.sh`：

```
>>> 1/6 编译资源 ... >>> 6/6 对齐并签名
=== 构建完成 ===
-rw-r--r--  21K  build/tvlock.apk
Signer #1 certificate DN: CN=TVLock, OU=Home, O=Home, L=Shanghai, C=CN
Signer #1 certificate SHA-256 digest: ae59ce09...38e1651
```

- 退出码 0，构建链可复现。依赖 JDK 17 + `build-tools/34.0.0` + `platforms/android-34`，本机齐备。
- 产物 `minSdk 21 / targetSdk 34`，包名 `com.toy.tvlock`，`versionCode 1`。
- 签名：RSA 2048，密钥在项目根 `tvlock.keystore`（未入库，正确）。
- **问题**：`build.sh:48-53` 把 keystore 口令 `tvlock123` 硬编码在脚本里（脚本已入库）。密钥文件与口令分离保护等于不存在。
- **问题**：`javac -source 8 -target 8` 在 JDK 17 上已弃用，且目标设备是 Android 14，没有必要沿用 Java 8 语言级别。
- **问题**：无 `aapt2 dump badging` / 签名一致性 / 清单导出的自动校验，构建成功不等于清单正确。

---

## 5. 源码审查

### 5.1 组件与权限清单（E1，`AndroidManifest.xml`）

| 组件 | 导出 | 保护 | 判定 |
|---|---|---|---|
| `MainActivity` | `true` + LAUNCHER | 无 | **P0 漏洞**：任何能打开应用列表的人（遥控器用户）可进入配置界面 |
| `LockService` | `true` | 无 | **P0 漏洞**：外部可发 `UNLOCK` |
| `BootReceiver` | `true` | 无 | 低危（最多被第三方强制上锁，DoS） |
| `AlarmReceiver` | `false` | — | 机制已证伪，保留即负担 |
| `MyDeviceAdminReceiver` | `true` | `BIND_DEVICE_ADMIN` | 空实现，非 Device Owner |

缺少但需要的权限：`WRITE_SECURE_SETTINGS`（若采用无障碍复活路径）、`GET_USAGE_STATS`（若需要前台检测）。当前都没有，说明复活机制尚未动工。

### 5.2 `LockService.java` 的安全问题（E1）

按严重度排序，行号为实际源码行：

| # | 位置 | 问题 | 后果 |
|---|---|---|---|
| S1 | `:278-294` | `onStartCommand` 直接处理 `"UNLOCK"`，无鉴权；服务 `exported=true` | 任何已安装应用一行代码解除锁定；`adb` 也可 |
| S2 | `:283-284` + `:267-276` | 解锁不落盘、不产生会话 | 状态无法审计，进程重启即重新上锁但无一致性 |
| S3 | `:176-181` | `addView` 失败 → `stopSelf()` | 锁不上时不是重试，而是放弃（fail-open） |
| S4 | `:297-315` | `onDestroy` 主动 `removeOverlay()` | 服务被停 = 锁自动消失（fail-open） |
| S5 | `:145-148` | PIN 明文读 `SharedPreferences`，默认 `"2025"`；`Integer.parseInt` 无异常保护 | 数据损坏 → `NumberFormatException` → 服务崩溃 → 未锁 |
| S6 | `:242-265` | 4 位错即清空，无失败计数、无退避、无锁定 | 4 位空间可穷举；遥控器操作虽慢但无阻断 |
| S7 | `:74`、`:108-125` | 每 5 秒 AlarmManager 自检 | 已证伪（force-stop 清闹钟），只增加功耗与日志噪音 |
| S8 | `:77-79` | `PARTIAL_WAKE_LOCK` 无超时一直持有 | 待机功耗；且已证伪无效 |
| S9 | `:164-168` | `FLAG_KEEP_SCREEN_ON` | 锁定期间电视永不自动熄屏（烧屏/功耗），需产品决策 |
| S10 | 全文 | 无 `directBootAware`、无 device-protected 存储 | 现在没事；一旦加 `directBootAware`，direct boot 阶段读不到 PIN 会静默回落到 `2025`（潜在漏洞） |

### 5.3 PIN 存储与验证（E1）

- 存储：`SharedPreferences("tvlock")`，键 `pin`，**明文**。
- 验证：`Integer.parseInt(entered) == getPin()`，整数比较。
- 默认值：`2025`，且**首次运行不强制设置**。
- 无 salt、无 KDF、无迭代、无锁定、无恢复凭证分离。
- 与 `ARCHITECTURE-v2.md §6` 的要求全部不符——架构文档写得对，代码一条没落地。

### 5.4 `MyDeviceAdminReceiver`（E1）

```java
public void onEnabled(Context context, Intent intent) { super.onEnabled(...); }
```

12 行，无策略、无 `onDisableRequested`、无 DO 检测。`device_admin.xml` 只声明 `force-lock`。**这是一个占位类，不构成任何设备管理能力。** 历史记录中也已实测「激活成功照样被杀」（E3）。

### 5.5 `MainActivity`（E1）

- `:52-57` 「解除锁定」按钮，点击即 `UNLOCK`，无 PIN。
- `:109` `status.setText("当前密码：" + pin + ...)` **明文显示 PIN**。
- 有 LAUNCHER 图标「电视锁」→ 出现在 PatchWall 应用列表 → **孩子拿遥控器打开这个 App 就能看到密码并解锁**。这是比 S1 更容易触发的实际绕过路径，优先级等同。

### 5.6 `BootReceiver`（E1）

不校验 `intent.getAction()`，任何显式 Intent 都会拉起 `LOCK`。功能上无害（只会更锁），但违反「校验 Intent action」的架构要求。真正的缺陷是：它把「开机必锁」押在 `startForegroundService` 上，而 Android 14 对后台启动 FGS 有额外限制，且该路径在真机从未验证（T01 NOT RUN）。

---

## 6. 为什么待机唤醒必然失败（E3 + E5）

历史日志证据（`docs/2026-11-04-xiaomi-tv-lock-attempt.md:49-55`）：

```
D MitvActivityManagerService: PowerManagerService killAllThirdApp forcestop packageName:com.toy.tvlock
I ActivityManager: Force stopping com.toy.tvlock appid=10007 user=0: from pid 828
I ActivityManager: Killing 2292:com.toy.tvlock/u0a(adj 200): stop com.toy.tvlock
```

关键不是「被杀死」，而是 **`Force stopping`**：应用被标记为 stopped 状态，AlarmManager 中的闹钟被一并清除，常规自启全部失效。已实测无效的手段（E3）：前台服务 + `START_STICKY`、`PARTIAL_WAKE_LOCK`、Doze 白名单、`RUN_IN_BACKGROUND`、Device Admin、5 秒 Alarm 自检。

由此推出当前代码的必然结果：**待机 → 进程死 → overlay 消失 → 唤醒后 PatchWall 直接可用。** 这一半需求当前生效率为 0，不是「不稳定」，是「不存在」。

补充一个更麻烦的时序事实（E4，Aerial 在 OS3.0.101 实测）：屏幕亮起后，小米约 4–5 秒才放开第三方进程启动，系统再花约 4–5 秒绑定服务。也就是说，**即使复活机制完美工作，也存在一个 4–10 秒的窗口**，此时前台是 PatchWall。

这个窗口决定了架构方向：不能接受「唤醒后再想办法把锁盖上去」，必须做到**唤醒时锁已经在屏上**——即进程在待机期间就活着，并在熄屏状态下把 overlay 挂好。这是本方案唯一能同时满足「无缝」与「force-stop 存活」的路径，也是复活锚点存在的真正理由。

---

## 7. Aerial TV 机制逐项判定

已读源码（`app/src/main/java/app/orchard/tv/guard/`）：`RevivalListener.kt`(152)、`GuardSetup.kt`(578)、`RevivalSync.kt`(94)、`BootReceiver.kt`(58)、`UsageFront.kt`(30)、`HomeGuardService.kt`(2327) 及 `AndroidManifest.xml`、设备笔记 `docs/devices/xiaomi-mitv-mftr0.md`。

### 7.1 值得独立借鉴（机制，非代码）

| 机制 | 源码位置 | 为什么值得 | 与本项目的差异 |
|---|---|---|---|
| `NotificationListenerService` 作为系统重绑锚点 | `RevivalListener.kt:25-73` | 同型号 MFTR0 唯一被实测验证过的 force-stop 存活路径；系统在绑定断开后约 0.5s 主动重绑，日志可观测 | 我们不是用它拉桌面，是用它作为**常驻宿主**：拥有锁窗口、持有状态机 |
| `directBootAware` + `isUserUnlocked` 门控 | `RevivalListener.kt:63`、`GuardSetup.kt:269` | direct boot 阶段访问凭据存储会崩；Aerial 有三次真实崩溃记录（issue #684/#1213/#1205） | 我们必须在 direct boot 下就 fail-closed，因此 PIN verifier 需放 device-protected storage |
| 有界重试阶梯 `5/5/10/15/30/60s` 后停止 | `RevivalListener.kt:149` | 复活是「启动期恢复」不是永动 watchdog；避免功耗与日志洪水 | 直接照此数值起步，真机测后调整 |
| 两次写入间隔 1.5s 的 rebind 技巧 | `GuardSetup.kt:370-398` | 连续两次写 `enabled_accessibility_services` 会被系统合并，必须留间隙 | 仅在采用无障碍路径时需要；若只走通知监听则不适用 |
| `BootReceiver` 用 `goAsync()` + 工作线程 | `BootReceiver.kt:26-37` | 广播超时是真实踩过的坑（issue #614），主线程做设置写入会被判 ANR | 直接采纳 |
| 诊断环缓冲 + 覆写 `dump()` | `RevivalListener.kt:133-137` | 电视上取 logcat 困难，`dumpsys` 能直接读到状态与复活结果 | 强烈建议采纳，是本项目可维护性的关键 |
| 复活计数与「本次开机内」窗口 | `GuardSetup.kt:219-255` | 能区分「开机启动」与「被杀后复活」，是判断固件行为变化的唯一手段 | 采纳 |
| 报告/自检脚本工作流 | `site/agent.md`、`site/adapt.md` | 每个开关记录前后值、失败改回、原始输出交付 | 采纳为 `agent/` 手册的执行纪律，不照搬其脚本 |

### 7.2 不能移植

| 机制 | 原因 |
|---|---|
| `HomeGuardService` 整套桌面接管（2327 行） | 目标是「把自家桌面拉回前台」，大量逻辑是**主动让位**给前台 App（`revived && !diedInFront ... return`）。我们的目标是**永不让位**，逻辑方向完全相反 |
| `takeOverOnceSettled` 1.5s 观察期 | 它是为了不打断 HDMI/CEC 与正在看的视频；我们的锁必须立刻出现，等待即等于放行 |
| `RevivalSync`（账户 + `settings` 授权同步适配器）作为主复活手段 | 会创建系统账户、每次设置写入都触发同步（开机、ADB 被关各算一次），约 30s 延迟。它是为 Android 9 低内存设备（通知监听被禁）准备的兜底，MFTR0 是 Android 14，不需要。**记录为文档化兜底，不实现** |
| `disableSelf()` / `guard_off_in_standby` | 那是解决「守护导致唤不醒」的取舍；对锁而言等于关掉锁 |
| 无障碍按键过滤（`flagRequestFilterKeyEvents`） | Aerial 实测：过滤开启后按住音量键不连发，且早期版本干扰播放器交互。且 overlay 已能挡住 HOME 的视觉结果，收益低、副作用明确 |
| `TYPE_ACCESSIBILITY_OVERLAY` 在按 HOME 时遮罩 | 设备笔记明确记录：小米 system_server 在切到 PatchWall 时会 `Force stopping` 那个 App。这是一个**直接适用于我们的警告**：overlay 压 PatchWall 在某些触发路径上会招致强停 |
| `DeviceProfile` 按机型硬编码 | 违反 `agent/adapt.md` 自己定的「优先功能检测」原则 |

### 7.3 一条必须记录的差异

Aerial 的设备笔记里 `MiTV-MFTR0` 记录固件为 `OS3.0.101.0.UFTMATV`，本机是 `OS3.0.106.0.UFTMATV`（E3）。**Aerial 的所有 MFTR0 结论对我们都只是 E4 级证据**，不能直接当作已在本机验证。所有移植机制必须在 OS3.0.106 上重测。

---

## 8. 架构判断：三条路线的真实代价

| 路线 | 能否挡住待机 force-stop | 能否挡住 HOME/最近任务 | 代价 |
|---|---|---|---|
| A. 现状：Service + overlay + 闹钟 | ❌ 已被杀 | ⚠️ 进程活着时能（已实测 Window #0） | 已证伪 |
| B. 通知监听宿主 + overlay + 持久化状态 | ✅ 系统主动重绑（E4，需本机复测） | ⚠️ overlay 在顶层，HOME 压不掉锁的视觉；但「唤醒窗口期」无法消除到 0 | 需要通知监听授权（ADB 一次），需要实测时序 |
| C. Device Owner + Lock Task | ❌ 不免疫 force-stop | ✅ 系统级禁用 HOME/Recents | 注册前置条件被账号/已初始化阻断；注册失败风险高；仍需 B 才能扛过待机 |

**结论：B 是必经之路，C 是可选的加固层，两者不是二选一。** 先做 B 把「每次唤醒必锁」变成事实，再评估 C 是否值得为「消除唤醒窗口期的旁路」付代价。这也意味着 P0 里不应出现 Device Owner 注册动作。

---

## 9. 未验证 / 未完成清单

### 9.1 测试矩阵实况（`docs/TEST-PLAN.md`）

T01–T20 **全部 NOT RUN**。没有一条 PASS。需要特别指出的是：README 宣称「开机自启已实现」，但 T01 从未执行——**「已实现」与「已验证」是两件事，当前只有前者。**

### 9.2 环境事实（本次实测）

- `adb devices` → 空列表。**本机当前无电视连接**，因此所有设备端动作（复活验证、时序测量、DO 探测）本轮均无法执行，一律 NOT RUN。
- 未执行 `adb reboot`、未改任何 `settings`、未注册/移除 Device Owner、未停用 PatchWall、未恢复出厂、未退出小米账号。

### 9.3 需要真机才能闭合的假设（E5）

1. 通知监听重绑在 **OS3.0.106** 上是否仍然发生，间隔多少。
2. 重绑后的进程能否在**熄屏状态下**成功 `addView` overlay（SYSTEM_ALERT_WINDOW 在熄屏时是否受限）。
3. 唤醒时 overlay 是否已经覆盖首帧，还是仍有 PatchWall 可见窗口（这个数字决定产品是否达标）。
4. `FLAG_KEEP_SCREEN_ON` 在待机流程中的行为（是否阻止系统进入 STR）。
5. 语音助手（小爱）拉起窗口时是否会盖到 `TYPE_APPLICATION_OVERLAY` 之上。
6. HDMI 信号源切换（`ExternalSourceActivity`）是否在 overlay 之上可见。

---

## 10. 执行计划

### P0 — 阻断级：不做完，后面所有工作都建立在漏洞上

| ID | 任务 | 涉及文件 | 验收方法 | 风险 |
|---|---|---|---|---|
| P0-1 | 关闭外部绕过通道：`LockService` 改 `exported=false`；`onStartCommand` 删除 `UNLOCK` 分支；`MainActivity` 移除解锁按钮与 PIN 显示；所有状态迁移只接受内部鉴权令牌 | `AndroidManifest.xml`、`LockService.java`、`MainActivity.java` | 静态审查 + `aapt2 dump xmltree` 确认 `exported=false`；第三方 App 发 Intent 应失败 | 改错会导致无法用 ADB 解锁 → 必须同时交付 P0-5 恢复通道 |
| P0-2 | PIN 安全化：PBKDF2-HMAC-SHA256 + 随机 salt，迭代次数在 MT9653 实测后定；移除默认 PIN，首启强制设置；失败计数 + 指数退避持久化；`getPin()` 的解析异常改为 fail-closed | 新增 `PinManager.java`，改 `LockService.java`、`MainActivity.java` | 编译 + 状态机单测（纯逻辑可脱离设备测）；真机解锁耗时不超过 300ms | 迭代次数过高会导致每次解锁卡顿（MT9653 是 32 位 ARM） |
| P0-3 | 状态机落盘：`LockStateManager`（LOCKED / UNLOCKED / RECOVERY_REQUIRED）存 device-protected storage；overlay 是否显示由状态推导，**不允许反向推断**；任何守卫失败 → RECOVERY_REQUIRED | 新增 `LockStateManager.java`，改 `LockService.java`、`BootReceiver.java` | 进程被杀后重启，状态仍为 LOCKED；断电重启后仍未解锁 | device-protected 存储写入需 `directBootAware`，与 P0-2 的存储位置耦合 |
| P0-4 | 复活锚点：新增 `RevivalListener`（`NotificationListenerService`，`directBootAware`，不读通知内容）作为常驻宿主；删除 `AlarmReceiver`、`ALARM` 权限、`PARTIAL_WAKE_LOCK`、FGS 依赖 | 新增 `RevivalListener.java`，改 `AndroidManifest.xml`，停用 `AlarmReceiver.java`、`BootReceiver.java` | 强制停止应用后 3 秒内 `pidof com.toy.tvlock` 有输出；锁状态恢复 | 需要 ADB 授权通知监听；授权丢失即整体失效，需要自检告警 |
| P0-5 | 恢复通道先行：设计并验证 R0/R1，确保 P0-1 之后用户仍能解锁与回滚（独立管理员凭证，不是隐藏按键组合） | `docs/RECOVERY.md`、新增恢复用 shell 脚本（只读模式默认） | 演练一次：模拟锁死，用文档路径恢复 | 恢复凭证过强 = 新旁路；过弱 = 锁死风险 |
| P0-6 | 真机验证复活：待机 30s / 10min 唤醒，记录时序日志（screen_on → rebind → addView → 首帧） | 无代码改动，产出 `docs/` 测量记录 | T02/T03 从 NOT RUN 变为 PASS 或 FAIL | **需要用户批准待机测试**；唤醒后 ADB 可能被关闭 |

### P1 — 达标级：让「不可绕过」成立

| ID | 任务 | 涉及文件 | 验收方法 | 风险 |
|---|---|---|---|---|
| P1-1 | 唤醒时序量化：连续 20 次待机唤醒，统计 PatchWall 可见帧数 | 测量脚本 + `docs/` 记录 | T04 通过（20/20） | 单次测试耗时长；每次唤醒需人工按遥控器 |
| P1-2 | Device Owner 只读可行性探测：`dumpsys device_policy`、provisioning 状态、账号存在性 | `agent/diagnose.md` 扩展 | 产出可行性结论，**不执行注册** | 误触注册会导致难以移除 |
| P1-3 | Kiosk 原型（仅在 P1-2 结论为正且用户批准后）：`setLockTaskPackages` + `LOCK_TASK_FEATURE_NONE` | 新增 `KioskController.java` | T17/T18/T19 | DO 注册可能要求清除账号 → 需用户明确决策，不由 Agent 自行决定 |
| P1-4 | 诊断能力：`DiagLog` 环缓冲 + 覆写 `dump()`，让 `dumpsys` 直接可读；只读诊断脚本 | 新增 `DiagLog.java`、`tools/diagnose.sh` | 电视上 `dumpsys activity service com.toy.tvlock/.RevivalListener` 输出可读状态 | 日志中不得出现 PIN 或 verifier |
| P1-5 | 旁路补测：语音助手、HDMI 信号源、系统设置、屏保 | 无代码 | T08/T09/T10 有结论 | 语音助手行为不可控，可能无法完全阻断 |

### P2 — 工程化

| ID | 任务 | 涉及文件 |
|---|---|---|
| P2-1 | 构建卫生：keystore 口令移出脚本（环境变量/本地文件），`-source 8` 升级，构建后自动 `aapt2 dump badging` + 清单断言 | `build.sh` |
| P2-2 | 纯逻辑单测：把 PIN、状态机抽成无 Android 依赖的类，用 `javac` + 简单 runner 跑（不引入 Gradle） | 新增 `test/` |
| P2-3 | 锁屏 UI 打磨：错误提示、剩余尝试次数、恢复态提示 | `res/layout/lock_overlay.xml`、`LockService.java` |
| P2-4 | 文档同步：`README.md` 中「已实现」段落必须区分「代码存在」与「真机验证」 | `README.md` |

---

## 11. 风险登记

| 风险 | 影响 | 缓解 |
|---|---|---|
| 通知监听授权被系统清除（小米策略变化） | 复活失效 → 待机后完全不锁 | 启动自检 + 明显告警（电视上不可见则靠下次 ADB 诊断） |
| 熄屏时无法 `addView` | 唤醒窗口期依然存在，方案降级为「延迟锁」 | P0-6 实测；若失败，评估 AccessibilityService 宿主（代价：系统删除无障碍授权，需 `WRITE_SECURE_SETTINGS` 写回） |
| overlay 压 PatchWall 触发强停（Aerial 记录过同型号现象） | 复活循环 → 功耗与闪烁 | P0-6 中专门观察 logcat 的 `Force stopping` 频率 |
| `FLAG_KEEP_SCREEN_ON` 阻碍待机 | 电视不睡 | 改为不加该 flag，依赖系统熄屏 |
| P0-1 改错导致无法解锁 | 需 ADB 恢复，最坏恢复出厂 | 先做 P0-5 恢复通道；恢复凭证独立于 PIN |
| 单 keystore + 硬编码口令 | 密钥泄露 = 可发布任意更新 | P2-1；本轮不处理，标注为已知风险 |
| 32 位 ARM + 弱 GPU | 高迭代 KDF 解锁卡顿 | P0-2 实测解锁耗时，迭代次数取 200–400ms 上限 |

---

## 12. 预计交付物

本轮（审查）：
- 本文件：`docs/2026-11-05-takeover-review.md`
- 更新：`docs/HANDOFF.md`

后续 P0 完成时预计新增/修改：
- 新增：`src/com/toy/tvlock/LockStateManager.java`、`PinManager.java`、`RevivalListener.java`、`DiagLog.java`
- 修改：`AndroidManifest.xml`、`LockService.java`、`MainActivity.java`、`BootReceiver.java`、`build.sh`、`README.md`
- 停用：`AlarmReceiver.java`、`MyDeviceAdminReceiver.java`（保留占位，不参与逻辑）
- 文档：`docs/TEST-PLAN.md`（逐项填 PASS/FAIL/NOT RUN + 证据）、`docs/DEVICE-MFTR0.md`（补 OS3.0.106 实测时序）

---

## 13. 本次未执行的动作（明确声明）

- 未修改任何 `.java` / `AndroidManifest.xml` / `build.sh`。
- 未连接电视（`adb devices` 为空）。
- 未执行重启、待机、唤醒测试。
- 未修改任何系统设置（含 `disable_back_home_whitelist`、`str_killbackgroud_pkg_white_list`、`low_power_standby_enabled`）。
- 未注册或移除 Device Owner，未启用 Kiosk。
- 未停用、卸载或修改 PatchWall 及任何系统应用。
- 未恢复出厂，未退出小米账号。
