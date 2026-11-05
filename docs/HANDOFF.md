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

## 当前实现（P0 已落地，2026-11-05，**未上机验证**）

> 审查结论见 [`2026-11-05-takeover-review.md`](2026-11-05-takeover-review.md)；
> 测试状态见 [`TEST-PLAN.md`](TEST-PLAN.md)（S 系列 PASS，T 系列全部 NOT RUN）。

- 包名 `com.toy.tvlock`，`versionCode 2` / `versionName 2.0-p0`，签名与历史 APK 相同（可覆盖升级）。
- **架构已从「Service + overlay + 闹钟」换成「通知监听宿主 + 落盘状态机 + overlay」**：
  - `RevivalListener`（NotificationListenerService，`directBootAware`，不读通知）是唯一常驻组件。
  - 待机 `SCREEN_OFF` 时状态落盘为 LOCKED 并**保留窗口**；唤醒首帧即为锁屏，不再有「先看到 PatchWall」的窗口期（设计意图，待 T29 验证）。
  - 删除 `LockService.java`、`AlarmReceiver.java`：前者是外部 `UNLOCK` 入口，后者机制已证伪。
- **状态 fail-closed**：`LockStateManager` 存 device-protected storage，默认值 LOCKED；进程在「已解锁」状态下死亡，新进程启动时强制回到 LOCKED。
- **PIN 无明文、无默认值**：PBKDF2（算法与迭代次数按设备实测自适应，存于记录中），salt 随机，首启强制设置；失败 4 次后 30/60/120/240s 退避，上限 300s，退避跨重启保留。
- **绕过通道已关闭**：无 `UNLOCK` action；`MainActivity` 不显示 PIN、无解锁按钮，锁定状态下不显示任何动作；权限面从 7 项收到 2 项。
- 诊断：`DiagLog` 环缓冲 + `RevivalListener.dump()`，`dumpsys activity service com.toy.tvlock/.RevivalListener` 直接可读状态与时间线，不含任何 PIN 材料。
- 恢复：`tools/tvlockctl.sh`（默认只读；写操作需 `--yes`，支持 `--dry-run`），见 `docs/RECOVERY.md`。
- **本轮未上机**。`adb devices` 为空，T 系列全部 NOT RUN。构建与 42 项离机单测通过，但这只证明代码自洽，不证明电视上可用。

### 已知未完成

- T29（唤醒首帧是否为锁屏）与 T30（overlay 是否招致强停）是架构的两个核心假设，**尚未验证**。
- `PinCrypto.ITERATIONS` 的自适应标定在电视上的实际解锁耗时尚无数据（目标 ≤220ms 派生 + 界面开销）。
- Device Owner / Kiosk 未动，仍是可选加固层。
- `MyDeviceAdminReceiver` 仍是空实现，保留供 P1-3 使用。

## 关键架构判断（2026-11-05）

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
- 无 CI（`.github/workflows` 不存在）、无 Gradle。离机测试用 `./test.sh`（纯 javac，42 项）。
- 历史实验记录保留：`docs/2026-11-04-xiaomi-tv-lock-attempt.md`、`docs/2026-11-04-xiaomi-tv-lock-research.md`。
- 测试矩阵 `docs/TEST-PLAN.md`：S01–S10 PASS，T01–T30 / R01–R04 全部 NOT RUN。

## 下一步（按此顺序）

### 立即：P0-6 真机验证（需要用户批准待机测试）

这是当前唯一能推动项目前进的事。代码已经改完，**但架构的两个核心假设还没被证实**：

| 优先级 | 测什么 | 失败意味着什么 |
|---|---|---|
| 1 | **T29**：待机时锁窗口是否保留，唤醒首帧是否为锁屏 | 若失败，改走 AccessibilityService 宿主 + `WRITE_SECURE_SETTINGS` 重绑 |
| 2 | **T30**：overlay 压 PatchWall 是否招致 `Force stopping` | 若频繁触发，overlay 方案本身要重新评估 |
| 3 | T02 / T03：短待机、长待机唤醒是否要求 PIN | 复活锚点在本机固件上不成立 |
| 4 | T21 / T22 / T25：绕过与 fail-closed 是否真的关死 | 收紧没生效，需回查 |
| 5 | T28：解锁耗时（KDF 自适应标定是否符合预期） | 下调 `PinCrypto.TARGET_UNLOCK_MS` |

每一步都要采集：`./tools/tvlockctl.sh status`、`dumpsys activity service com.toy.tvlock/.RevivalListener`、必要时 `logcat -d -s tvlock`。

### P1 — 达标级

- P1-1 连续 20 次待机唤醒时序量化（T04）
- P1-2 Device Owner **只读**可行性探测（`dumpsys device_policy`，不执行注册）
- P1-3 Kiosk 原型（仅在 P1-2 结论为正且用户批准后）
- P1-4 旁路补测：语音助手、HDMI 信号源、系统设置、屏保（T08/T09/T10）

### P2 — 工程化

- P2-1 构建卫生：把 `TVLOCK_KS_PASS` 真正用起来（当前只是支持，默认仍回落到历史口令）
- P2-2 补状态机单测（`LockStateManager` 的迁移规则目前只有静态审查）
- P2-3 锁屏 UI 打磨（错误提示、剩余次数已在，可再优化可读性）
- P2-4 打包 `.dm`（speed-profile），降低首次解锁与启动开销（Aerial 记录该机型需 `install-multiple`）

## 最近工作记录

### 2026-11-05 — P0 实现（未上机）
- 代码重写：新增 `PinCrypto`、`BackoffPolicy`、`PinManager`、`LockStateManager`、`LockCoordinator`、`LockOverlay`、`RevivalListener`、`DiagLog`；重写 `BootReceiver`、`MainActivity`、`AndroidManifest.xml`、`build.sh`；删除 `LockService.java`、`AlarmReceiver.java`。
- 权限从 7 项收到 2 项；`versionCode 2`，签名密钥未变（可覆盖升级）。
- 新增 `test.sh` + `test/com/toy/tvlock/RunTests.java`（42 项离机测试全 PASS）、`tools/tvlockctl.sh`（默认只读，写操作 `--yes` + `--dry-run`）。
- 文档：`docs/RECOVERY.md` 重写为可执行恢复流程；`docs/TEST-PLAN.md` 拆成 S/T/R 三类并补 T21–T30；本文件更新。
- **未连接电视，未上机验证任何一项 T 测试。**

### 2026-11-05 — 项目接管审查（只读）
- 读取：`AGENTS.md`、`README.md`、`CLAUDE.md`、`docs/` 全部 7 份、`agent/` 全部 5 份、5 个 Java 源文件、Manifest、`build.sh`、`res/`。
- 读取 Aerial 源码：`RevivalListener.kt`、`GuardSetup.kt`、`RevivalSync.kt`、`BootReceiver.kt`、`UsageFront.kt`、`HomeGuardService.kt`(2327 行)、Aerial `AndroidManifest.xml`、`site/agent.md`、`site/adapt.md`、`AGENTS.md`、设备笔记 `xiaomi-mitv-mftr0.md`。
- 实测：`git log/branch`、`adb devices`（**空，无电视连接**）、`./build.sh`（**通过**，签名 SHA-256 `ae59ce09…`）、`aapt2 dump badging/xmltree`。
- 产出：`docs/2026-11-05-takeover-review.md`；本文件重构为可交接状态。
- 修正：PR #1 已合并（原文写「尚未合并」）；测试矩阵全 NOT RUN（原文「已实现」易误读）。
- **未修改任何代码；未连接电视；未重启、未待机测试、未改系统设置、未注册/移除 Device Owner、未停用 PatchWall、未恢复出厂。**


