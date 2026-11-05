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

## 仓库当前已知实现（以源码为准，记录时间 2026-11-05）

- 包名：`com.toy.tvlock`
- Java 手工构建：`build.sh`
- 核心：`src/com/toy/tvlock/LockService.java`，使用 `TYPE_APPLICATION_OVERLAY` 全屏覆盖、4 位 PIN。
- `BootReceiver` 尝试开机启动服务。
- `AlarmReceiver` / AlarmManager 自检已被实测证明不能抵抗 force-stop。
- `MyDeviceAdminReceiver` 目前只是空的 DeviceAdminReceiver 回调；这不等于 Device Owner。
- Manifest 中 LockService 当前 exported=true，且通过外部可触发的 LOCK/UNLOCK action；必须先审查并收紧。
- MainActivity 当前存在直接解除锁定按钮和 PIN 显示逻辑；必须修复。
- PIN 当前保存在 SharedPreferences，默认值为 2025；属于不安全实验实现。
- 上述问题是源码审查发现，不代表已经修复。

## Aerial TV 已研究的借鉴点

- MFTR0 同型号设备笔记：https://github.com/pengchujin/aerial-tv/blob/main/docs/devices/xiaomi-mitv-mftr0.md
- AI 安装说明：https://aerial.qust.me/agent.md
- AI 设备适配说明：https://aerial.qust.me/adapt.md
- 项目 Agent 规范：https://github.com/pengchujin/aerial-tv/blob/main/AGENTS.md
- 核心恢复源码：`RevivalListener.kt`、`GuardSetup.kt`、`BootReceiver.kt`、`UsageFront.kt`。

可借鉴：NotificationListenerService 作为进程复活锚点；有限次数延迟检查；等待用户解锁；确认 Accessibility 服务真正绑定；恢复时保留其他服务；诊断日志；自检与验收工作流。

不可直接推导：Device Owner 能抵御小米 force-stop；普通悬浮窗等于安全边界；Aerial 桌面守护等于整机锁。

## 当前 Git / 交付状态

- 原始实验文档：`docs/2026-11-04-xiaomi-tv-lock-attempt.md`、`docs/2026-11-04-xiaomi-tv-lock-research.md`，应保留为历史记录。
- Agent-first 文档工作分支：`docs/agent-first-workflow`。
- 本交接文件应在每次任务结束时追加「最近工作记录」。
- 当前这轮只建立 Agent 工作规范和手册；没有修改 APK，也没有在电视上执行命令。

## 下一步建议

1. P0：修复外部 UNLOCK 入口、明文 PIN、默认 PIN 暴露和管理界面绕过。
2. P1：实现 LockStateManager / PIN 安全存储 / 解锁会话。
3. P2：实现 Device Owner 状态检测和 Kiosk 原型；先在当前电视上做只读检查及注册可行性探测。
4. P3：集成 Aerial 风格 RevivalListener 恢复机制（先验证授权与系统重绑，不直接复制整套桌面守护）。
5. P4：设备端验收和恢复演练。

## 最近工作记录

### 2026-11-05 — Agent-first 工作体系设计
- 读取当前 `xiaomi-tv-lock` README、Manifest、LockService、BootReceiver、AlarmReceiver、DeviceAdminReceiver、MainActivity、build.sh 和两份实验记录。
- 读取 Aerial 的 `AGENTS.md`、`site/agent.md`、`site/adapt.md`、MFTR0 设备笔记，以及 RevivalListener / GuardSetup / BootReceiver / UsageFront 关键源码。
- 发现当前 LockService 的外部 UNLOCK action、MainActivity 直接解锁、明文 PIN 等风险。
- 未修改 APK；未连接电视；未进行 Device Owner 注册或恢复出厂。

- 后续补充：增加 `CLAUDE.md`、`.github/copilot-instructions.md`、`.agents/skills/tv-lock-ops/SKILL.md`、`.claude/skills/tv-lock-ops/SKILL.md`，让 Codex/通用 AGENTS 入口、Claude Code 和 GitHub Copilot 能发现项目工作规范与任务流程。
- 已创建 Draft PR：https://github.com/cfrs2005/xiaomi-tv-lock/pull/1（目标 main；尚未合并）。
