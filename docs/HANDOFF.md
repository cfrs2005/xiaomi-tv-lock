# TV Lock — Agent 交接文档

> 本文件是下一位 AI Agent 的第一份工作交接。每次完成有意义的代码、设备测试或架构变更后更新。

## 项目目标

在 Xiaomi TV S55 Mini LED（MiTV-MFTR0，MT9653，Android 14，HyperOS TV 3）上实现整机访问门禁：
- 每次真正开机必须输入 PIN。
- 每次遥控器电源键待机后唤醒必须输入 PIN。
- 未解锁时不能通过 HOME、返回、最近任务、语音助手或启动其他应用绕过。
- 正常解锁后保留 PatchWall、视频应用、HDMI 等正常电视体验。
- 异常时有分级恢复路径；恢复出厂只作为最后手段。

## 设备事实（来自用户记录，尚需当前设备复核）

- 设备代号：`MiTV-MFTR0` / `freeguy`
- SoC：MediaTek MT9653；4GB；32-bit ARM（Aerial 设备笔记记载）
- Android 14 / SDK 34
- 用户固件：`OS3.0.106.0.UFTMATV`
- Aerial 同型号公开笔记曾记录 `OS3.0.101.0.UFTMATV`，不是同一固件版本。
- 原桌面：`com.mitv.tvhome/.MainActivityUserMode`
- HOME 白名单由小米控制；`set-home-activity` / HOME role 成功返回不代表解析结果改变。
- 待机时 `MitvActivityManagerService.killAllThirdApp` 对第三方应用执行 force-stop；已有用户日志证据。
- 被 force-stop 后 AlarmManager、普通前台服务、WakeLock、Doze 白名单和 Device Admin 均未能解决。
- 唤醒后 ADB 可能被小米关闭。

## 仓库当前已知实现（以源码为准，2026-11-05 接管审查复核）

> 完整审查报告见 [`2026-11-05-takeover-review.md`](2026-11-05-takeover-review.md)。以下为结论摘要。

- 包名：`com.toy.tvlock`；commit `95cb1d5`；`versionCode 1`。
- Java 手工构建：`build.sh`。**本次实测构建通过**（退出码 0，产物 21K，签名 SHA-256 `ae59ce09…38e1651`）。
- 核心：`LockService.java`（316 行），`TYPE_APPLICATION_OVERLAY` 全屏覆盖 + 4 位 PIN。
- **待机路径生效率为 0**：进程被 force-stop 后 overlay 随窗口消失，唤醒后 PatchWall 直接可用。当前代码没有任何组件能在这条路径上存活。
- **冷启动路径：代码已写、真机从未验证**（T01 NOT RUN）。`BootReceiver` 押在 `startForegroundService` 上，Android 14 下未测。
- **三条仅用遥控器即可绕过的路径**（详见报告 §5）：
  1. `MainActivity` 有 LAUNCHER 入口，界面上明文显示 PIN 且带「解除锁定」按钮（`:52-57`、`:109`）。
  2. `LockService` `exported=true`，`onStartCommand` 接受外部 `UNLOCK`（`:283`）。
  3. 默认 PIN `2025`，首启不强制设置。
- PIN 明文存 SharedPreferences，无 salt / KDF / 失败退避（`ARCHITECTURE-v2.md §6` 的要求一条未落地）。
- 状态不落盘：锁定状态 = 「overlay 对象是否为空」，服务一死即视为未锁 —— **与 fail-closed 相反**。
- `MyDeviceAdminReceiver` 是 12 行空实现，不构成任何设备管理能力。
- `AlarmReceiver` + 每 5 秒 AlarmManager 自检 + 无超时 `PARTIAL_WAKE_LOCK`：机制已证伪，只增加功耗。
- 上述问题均为源码审查发现，**本轮未修改，均未修复**。

## 关键架构判断（2026-11-05 新增）

- **Device Owner 不能解决待机问题。** 厂商 force-stop 不受 DO 约束；DO 只能补「进程活着时 HOME/最近任务被系统压住」这个洞。复活锚点才是硬依赖，DO 是可选加固层，两者不是二选一。
- **必须做到「唤醒时锁已在屏上」**，而不是唤醒后再把锁盖上去。依据：Aerial 在同型号记录，屏幕亮起后约 4–5 秒才放开第三方进程，系统再花约 4–5 秒绑定服务，合计 4–10 秒 PatchWall 可见窗口。唯一可行路径是进程在待机期间存活，并在熄屏状态下把 overlay 挂好。
- Aerial 的 MFTR0 数据来自 `OS3.0.101`，本机是 `OS3.0.106`，所有移植机制**一律视为待本机重测**。

## Aerial TV 已研究的借鉴点

- MFTR0 同型号设备笔记：https://github.com/pengchujin/aerial-tv/blob/main/docs/devices/xiaomi-mitv-mftr0.md
- AI 安装说明：https://aerial.qust.me/agent.md
- AI 设备适配说明：https://aerial.qust.me/adapt.md
- 项目 Agent 规范：https://github.com/pengchujin/aerial-tv/blob/main/AGENTS.md
- 核心恢复源码：`RevivalListener.kt`、`GuardSetup.kt`、`BootReceiver.kt`、`UsageFront.kt`。

可借鉴：NotificationListenerService 作为进程复活锚点；有界重试阶梯（5/5/10/15/30/60s 后停止）；`directBootAware` + `isUserUnlocked` 门控；两次写入间隔 1.5s 的 rebind 技巧；`BootReceiver` 用 `goAsync()` 卸载主线程；诊断环缓冲 + 覆写 `dump()`；复活计数；自检与验收工作流。

不可直接推导：Device Owner 能抵御小米 force-stop；普通悬浮窗等于安全边界；Aerial 桌面守护等于整机锁。

不可移植：`HomeGuardService` 整套桌面接管（2327 行，逻辑方向是「主动让位给前台 App」，与本项目的「永不让位」相反）；`takeOverOnceSettled` 的 1.5s 观察期（对锁而言等待即放行）；`RevivalSync` 账户 + settings 同步适配器作为主复活手段（副作用大、延迟约 30s，仅记为文档化兜底）；无障碍按键过滤（Aerial 实测会破坏音量键连发）；`TYPE_ACCESSIBILITY_OVERLAY` 遮罩（Aerial 记录该路径会招致小米 system_server 对 App 执行 `Force stopping`，**这是对本项目最直接的警告**）。

逐项判定表（含源码行号与理由）见 [`2026-11-05-takeover-review.md §7`](2026-11-05-takeover-review.md)。

## 当前 Git / 交付状态

- 当前分支 `main`，HEAD `95cb1d5`，与 `origin/main` 一致，工作区干净（仅 `.workbuddy/` 未跟踪）。
- **PR #1 已 squash 合并进 main**（原文档「尚未合并」的说法已过期）。远端残留 `origin/docs/agent-first-workflow`，内容与 main 无差异，可删。
- 无 CI（`.github/workflows` 不存在）、无单元测试、无 Gradle。
- 历史实验记录保留：`docs/2026-11-04-xiaomi-tv-lock-attempt.md`、`docs/2026-11-04-xiaomi-tv-lock-research.md`。
- 测试矩阵 `docs/TEST-PLAN.md`：T01–T20 **全部 NOT RUN**，无一条 PASS。README 中「已实现」的表述指的是代码存在，不代表真机验证。

## 下一步建议（P0 / P1 / P2，2026-11-05 重排）

顺序不可颠倒：**P0-5 恢复通道必须先于 P0-1 落地**，否则收紧权限后一旦出错就无法解锁。

### P0 — 阻断级

| ID | 任务 | 文件 |
|---|---|---|
| P0-1 | 关闭外部绕过：`LockService` 改 `exported=false`、删除外部 `UNLOCK` 分支、`MainActivity` 移除解锁按钮与 PIN 显示 | `AndroidManifest.xml`、`LockService.java`、`MainActivity.java` |
| P0-2 | PIN 安全化：PBKDF2-HMAC-SHA256 + 随机 salt，移除默认 PIN，首启强制设置，失败计数与退避持久化 | 新增 `PinManager.java` |
| P0-3 | 状态机落盘：LOCKED / UNLOCKED / RECOVERY_REQUIRED 存 device-protected storage，overlay 由状态推导 | 新增 `LockStateManager.java` |
| P0-4 | 复活锚点：`RevivalListener`（NotificationListenerService，directBootAware，不读通知内容）作为常驻宿主；删除 AlarmManager、WakeLock、FGS 依赖 | 新增 `RevivalListener.java`，停用 `AlarmReceiver.java` |
| P0-5 | 恢复通道 R0/R1 先落地并演练（独立管理员凭证，不用隐藏按键） | `docs/RECOVERY.md`、恢复脚本 |
| P0-6 | 真机验证复活与时序（需用户批准待机测试） | 无代码，产出测量记录 |

### P1 — 达标级

- P1-1 连续 20 次待机唤醒时序量化（T04）
- P1-2 Device Owner **只读**可行性探测（不执行注册）
- P1-3 Kiosk 原型（仅在 P1-2 结论为正且用户批准后）
- P1-4 诊断能力：`DiagLog` + 覆写 `dump()`，让 `dumpsys` 可读状态（电视上取 logcat 困难）
- P1-5 旁路补测：语音助手、HDMI 信号源、系统设置、屏保

### P2 — 工程化

- P2-1 构建卫生：keystore 口令移出 `build.sh`（当前硬编码 `tvlock123`）、Java 8 语言级别、构建后清单断言
- P2-2 纯逻辑单测（PIN / 状态机抽成无 Android 依赖类，不引入 Gradle）
- P2-3 锁屏 UI 打磨
- P2-4 `README.md` 区分「代码存在」与「真机验证」

## 最近工作记录

### 2026-11-05 — 项目接管审查（只读）
- 读取：`AGENTS.md`、`README.md`、`CLAUDE.md`、`docs/` 全部 7 份、`agent/` 全部 5 份、5 个 Java 源文件、Manifest、`build.sh`、`res/`。
- 读取 Aerial 源码：`RevivalListener.kt`、`GuardSetup.kt`、`RevivalSync.kt`、`BootReceiver.kt`、`UsageFront.kt`、`HomeGuardService.kt`(2327 行)、Aerial `AndroidManifest.xml`、`site/agent.md`、`site/adapt.md`、`AGENTS.md`、设备笔记 `xiaomi-mitv-mftr0.md`。
- 实测：`git log/branch`、`adb devices`（**空，无电视连接**）、`./build.sh`（**通过**，产物 21K，签名 SHA-256 `ae59ce09…`）、`aapt2 dump badging/xmltree`。
- 产出：`docs/2026-11-05-takeover-review.md`；本文件重构为可交接状态。
- 修正：PR #1 已合并（原文写「尚未合并」）；测试矩阵全 NOT RUN（原文「已实现」易误读）。
- **未修改任何代码；未连接电视；未重启、未待机测试、未改系统设置、未注册/移除 Device Owner、未停用 PatchWall、未恢复出厂。**

