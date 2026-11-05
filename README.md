# TV Lock — 小米电视整机访问门禁

> **状态：代码已重写，真机未验证。**
> 待机唤醒锁定由「通知监听复活锚点」承担，架构上已经能覆盖厂商 force-stop；但这一条
> **只在本机以外同型号固件上被独立验证过**，本机（OS3.0.106）尚未上机。测试矩阵见
> [`docs/TEST-PLAN.md`](docs/TEST-PLAN.md)，T 系列全部 NOT RUN。

针对 **Xiaomi TV S55 Mini LED / MiTV-MFTR0 / HyperOS TV 3 / Android 14** 的整机访问门禁：
每次冷启动、每次待机唤醒都要输入 4 位 PIN，未解锁不能通过 HOME、返回、最近任务、语音助手
或任何应用绕过；正确解锁后恢复 PatchWall、视频应用、HDMI 的正常使用。

---

## 为什么需要自己做

这台电视没有系统级开机密码：

| 尝试 | 结果 |
|---|---|
| 系统设置里的开机密码 | 不存在，`lockscreen.disabled=1` |
| 儿童锁 / 家长中心 | 只管「退出儿童模式」，不拦开机 |
| 酒店模式 | 需要厂商激活码，零售机 SN 不在范围 |
| `cmd device_lock`（Android 14 设备锁框架） | `No shell command implementation` |
| `set-home-activity` 换第三方桌面 | 返回 Success，但 HOME 解析仍写死给 PatchWall |

## 核心障碍与应对

电视待机时，小米的 `MitvActivityManagerService` 对所有第三方应用执行 **`Force stopping`**
（不是 `stopService`）。这会连 AlarmManager 里的闹钟一起清掉，应用被标记为 stopped，无障碍
授权被移除。已实测无效的手段：前台服务 + `START_STICKY`、`PARTIAL_WAKE_LOCK`、Doze 白名单、
`RUN_IN_BACKGROUND` appops、Device Admin、5 秒 Alarm 自检。

真正能活下来的是**通知监听服务**：授权保留，进程死后系统会主动重绑（绑定由 system_server
持有）。本项目的架构就建立在这条路径上：

```
待机 -> SCREEN_OFF：状态落盘为 LOCKED，锁窗口保留不撤
     -> 厂商 force-stop：进程与窗口一起消失
     -> 系统重绑 NotificationListenerService（约 0.5s）
     -> 新进程读到 LOCKED -> 重新挂锁（且此时屏幕是黑的）
唤醒 -> 屏幕亮起时锁已在屏上，不给 PatchWall 露出窗口
```

这与「唤醒后再把锁盖上去」是两种方案。后者的代价是同型号实测记录里屏幕亮起后 4–10 秒的
PatchWall 可见期。

## 架构

| 组件 | 职责 |
|---|---|
| `RevivalListener` | 唯一常驻组件。NotificationListenerService，`directBootAware`，**不读任何通知内容**，只作为系统重绑的锚点 |
| `LockCoordinator` | 状态机与窗口的协调者：状态推导窗口、重试策略、屏幕事件 |
| `LockStateManager` | LOCKED / UNLOCKED / RECOVERY_REQUIRED 落盘于 device-protected storage，默认 LOCKED |
| `PinManager` / `PinCrypto` | PBKDF2 校验、salt、失败退避；PIN 明文不落盘 |
| `LockOverlay` | 全屏锁窗口（3×4 键盘，D-pad 可操作），承载解锁 / 首次设置 / 修改密码三个流程 |
| `BootReceiver` | 冷启动兜底：校验 action、`goAsync()` 工作线程、置 LOCKED |
| `DiagLog` | 环缓冲 + `dump()`，让 `dumpsys` 能读时间线（电视上取 logcat 困难） |

设计原则：**窗口由状态推导，不能反过来用「窗口在不在」推断「锁没锁」。**
进程一死，下一次启动读到的必须是 LOCKED。

## 安全边界

- 无默认 PIN，首启强制设置；只存 PBKDF2 verifier，迭代次数按设备实测自适应（60k–250k）。
- 连续 4 次错误后进入 30/60/120/240 秒退避，上限 300 秒，退避跨重启保留。
- 没有外部解锁入口：无 `UNLOCK` action，组件不接受外部启动，设置界面不显示 PIN、锁定状态下不显示任何动作。
- 不承诺抵御拥有 ADB 授权、设备管理权限、物理恢复模式或刷机能力的攻击者。

## 构建

不使用 Gradle，直接用 SDK 里的 `aapt2` + `javac` + `d8` + `apksigner` 手工打包。

**依赖：** JDK 17、Android SDK（`platforms;android-34` + `build-tools;34.0.0`）

```bash
export ANDROID_HOME="$HOME/android-sdk"
export TVLOCK_KS_PASS="<签名口令>"     # 未设置会回落到历史口令并告警
./build.sh                            # 产物 build/tvlock.apk，内置清单断言
./test.sh                             # 离机测试：PIN 派生、退避、编码（42 项）
```

签名密钥 `tvlock.keystore` 在项目根且不入库。**换密钥会导致无法覆盖安装**。

## 部署与恢复

先指定设备（只有一台设备时可省略）：`export TV=电视IP:5555`

```bash
./tools/tvlockctl.sh status      # 只读诊断，默认动作
./tools/tvlockctl.sh arm --yes   # 授予悬浮窗 + 通知监听
```

解除保护（电视恢复不受限，应用仍安装）：

```bash
./tools/tvlockctl.sh disable --yes
```

完整恢复分级、场景速查与「不允许的动作」见 [`docs/RECOVERY.md`](docs/RECOVERY.md)。
**恢复出厂不在任何脚本里，也不是正常部署步骤。**

## 文件结构

```
AndroidManifest.xml              组件与权限（2 项权限，通知监听受系统级权限保护）
build.sh                         手工构建 + 产物断言
test.sh                          离机测试
tools/tvlockctl.sh               部署 / 诊断 / 解除保护（默认只读）
res/layout/lock_overlay.xml      锁屏界面：标题 + 圆点 + 3×4 键盘
src/com/toy/tvlock/
  RevivalListener.java           复活锚点（通知监听）
  LockCoordinator.java           状态 + 窗口协调
  LockStateManager.java          状态落盘（device-protected）
  LockOverlay.java               锁窗口与输入流程
  PinManager.java                PIN 存储与校验
  PinCrypto.java                 PBKDF2（无 Android 依赖，可离机测试）
  BackoffPolicy.java             失败退避（无 Android 依赖，可离机测试）
  BootReceiver.java              冷启动兜底
  DiagLog.java                   诊断环缓冲
  MainActivity.java              设置与自检（无解锁入口）
  MyDeviceAdminReceiver.java     占位，供后续 Device Owner 实验
test/com/toy/tvlock/RunTests.java 离机测试入口
docs/                            交接、架构、设备档案、恢复、测试矩阵、审查报告
agent/                           Agent 任务手册（install / diagnose / adapt / recovery / acceptance）
```

## 踩过的坑（保留备查）

| 坑 | 现象 | 解法 |
|---|---|---|
| 悬浮窗盖住了但没焦点 | 遥控器按键不生效 | 不要设 `FLAG_NOT_FOCUSABLE` |
| 输入法窗口升到最顶层 | 锁层被输入法盖住 | `FLAG_ALT_FOCUSABLE_IM` + `SOFT_INPUT_STATE_ALWAYS_HIDDEN` |
| 锁层加 `FLAG_KEEP_SCREEN_ON` | 锁定期间电视永不熄屏 | 不加该 flag |
| 用「overlay 是否存在」判断锁定 | 进程一死就变成未锁 | 状态落盘，窗口由状态推导 |
| keystore 放在 build 目录 | 每次重构建签名变化，无法覆盖安装 | 密钥移到项目根 |

## 参考

- [Aerial TV](https://github.com/pengchujin/aerial-tv) — 同款机型的设备笔记，通知监听复活机制的来源（MIT）
- [TV App Lock](https://github.com/oleksii-klots/tv-app-lock) — 用 UsageStats 轮询而非无障碍的思路
- [KidTVLock](https://github.com/terranc/KidTVLock) — 同样主打「开机或唤醒时锁」
- [FreeKiosk](https://github.com/RushB-fr/freekiosk) — Device Owner 锁死方案

借鉴范围与不借鉴的理由见 [`docs/2026-11-05-takeover-review.md §7`](docs/2026-11-05-takeover-review.md)。

## License

MIT

## AI Agent 工作流

本项目面向 AI Agent 协作开发和设备运维。Agent 接手任务时必须先读：

1. [AGENTS.md](AGENTS.md) — 全局规则、风险确认门、完成定义
2. [docs/HANDOFF.md](docs/HANDOFF.md) — 当前状态和下一步
3. [docs/DEVICE-MFTR0.md](docs/DEVICE-MFTR0.md) — 设备档案
4. [docs/ARCHITECTURE-v2.md](docs/ARCHITECTURE-v2.md) — 架构设计
5. [docs/RECOVERY.md](docs/RECOVERY.md) — 分级恢复
6. [docs/TEST-PLAN.md](docs/TEST-PLAN.md) — 验收标准（S / T / R 三类）

任务手册位于 [agent/](agent/)：INSTALL / DIAGNOSE / ADAPT / RECOVERY / ACCEPTANCE。

**这些文档是给 AI Agent 执行的工作指令，不是要求用户手工照做的教程。** Agent 必须在有权限的
环境里亲自执行并核验真实结果；没有设备访问能力时必须明确说明。
