# TV Lock — AI Agent 工作规范

本仓库的主要操作者是 AI coding / computer-use agent。用户负责提出目标、批准高风险操作和确认结果；Agent 应在具备权限的环境中亲自检查、执行、测试、更新文档并汇报。不要把本文件变成要求用户手工执行的操作教程。

## 开始任何任务前

按顺序阅读：
1. `README.md`：项目目标、当前实现和构建方式。
2. `docs/HANDOFF.md`：当前状态、已知问题、下一步。
3. 与任务相关的 `docs/DEVICE-MFTR0.md`、`docs/ARCHITECTURE-v2.md`、`docs/RECOVERY.md`、`docs/TEST-PLAN.md`。
4. 对应的 `agent/*.md` 操作手册。
5. 当前源码和 Git 状态；不要仅凭旧交接文档推断代码现状。

## 项目目标

为 Xiaomi TV S55 Mini LED / MiTV-MFTR0 / HyperOS TV 3 / Android 14 构建整机访问门禁：每次冷启动和待机唤醒都必须输入 PIN；未授权时不得进入 PatchWall 或其他应用。正常解锁后，电视恢复原有正常使用体验。

目标不是第三方桌面替换，也不是普通儿童锁。悬浮窗仅可作为兼容/诊断手段，不能宣称为系统安全边界。

## Agent 工作原则

- 先检查，再行动；先读取真实设备状态，再决定下一步。
- 亲自运行可用的命令并检查退出码、stdout、stderr、日志和设备状态。不能访问用户电脑/ADB 时，明确说明能力边界并请求用户切换到有本机终端/设备连接能力的 Agent；不要假称执行过。
- 不要把计划、伪代码、示例命令说成已实现。
- 每项变更必须有测试证据；区分静态检查、构建通过、模拟器通过、MFTR0 真机通过。
- 记录命令原文、关键输出、时间、固件版本、前后设置值。
- 修复后重跑相关测试；失败则保留失败证据，不得把 FAIL 改写成成功。
- 任务结束时更新 `docs/HANDOFF.md`，必要时更新设备文档、测试矩阵和 Agent 手册。
- 默认在独立分支工作；提交前检查 diff、构建和敏感信息。未经用户要求不要直接合并到 main。

## 风险确认门

以下动作必须先向用户说明影响并取得明确同意：
- `adb reboot`、待机/唤醒测试、断电或任何会中断观看/连接的动作。
- 修改小米全局/系统设置、无障碍服务列表、通知监听授权、ADB 开关、开机行为。
- 注册或移除 Device Owner、启用/退出 Kiosk、修改设备管理策略。
- 禁用、卸载、清除数据或修改任何系统应用。
- 恢复出厂、刷机、解锁 bootloader、删除电视数据。

**恢复出厂不是正常部署步骤。** 它仅是最后的灾难恢复手段，必须在其他恢复路径失败、用户理解数据损失并再次明确批准后才可执行。不得因 Device Owner 注册失败而自动恢复出厂。

## Device Owner 特别规则

- 首先在当前已配置电视上做只读检查和无破坏性可行性验证。
- ADB 的 `dpm set-device-owner` 在已完成初始化的设备上通常会因 provisioning 状态/已有账号等条件失败；不要假定能在当前状态直接注册。
- 如果当前状态不满足注册条件，停止并报告实际错误、已验证事实和可选路径。不得自行清除账号或触发出厂重置。
- 注册成功也不等于 Kiosk 在 HyperOS 上有效；必须逐项真机验证 HOME、Recents、System UI、待机恢复和正常退出。
- Device Owner 不代表应用免疫小米的厂商级 force-stop。必须单独验证。

## Aerial TV 借鉴规则

参考项目：
- https://github.com/pengchujin/aerial-tv
- https://aerial.qust.me/agent.md
- https://aerial.qust.me/adapt.md
- https://github.com/pengchujin/aerial-tv/blob/main/AGENTS.md

重点借鉴其 MFTR0 生命周期诊断、NotificationListener 复活、GuardSetup 重绑、有限重试、真实输出验收、设备报告和 Agent 工作流。不要把 Aerial 的桌面接管逻辑误当成整机访问控制，也不要未经分析复制其 HomeGuardService。

Aerial 仓库使用 MIT License。若直接复制或改编代码，必须确认适用许可并保留版权/许可声明；优先先学习机制并独立实现，记录来源和差异。

## 代码与构建约定

- 当前工程为 Java + 手工 Android SDK 构建（`aapt2`, `javac`, `d8`, `apksigner`）。除非用户批准，不要擅自迁移 Gradle/Kotlin。
- 包名保持 `com.toy.tvlock`，除非用户明确要求变更。
- 不要提交 keystore、PIN、设备 IP、ADB 密钥、日志中的账号标识或其他个人数据。
- 不要在日志中打印 PIN、PIN hash、恢复凭证或完整敏感配置。
- 导出的 shell 脚本必须支持只读诊断模式；写操作提供 dry-run 或明确确认门。
- 不要覆盖其他无障碍/通知监听服务配置；修改列表时保留其他条目并保存原值。
- 新增权限必须说明用途、授权方式、撤销方式和失败行为。

## 完成定义（Definition of Done）

一个任务只有同时满足适用条件才可标记完成：
1. 代码/文档已提交到明确分支，给出 commit SHA 和链接。
2. 构建通过（若涉及代码），提供真实构建输出摘要。
3. 测试矩阵更新，逐项标注 PASS/FAIL/SKIP/NOT RUN。
4. MFTR0 真机未测的项目必须标注 NOT RUN，不能宣称生产可用。
5. 恢复路径经过验证，或明确标注尚未验证的风险。
6. `docs/HANDOFF.md` 已更新，下一位 Agent 能从中继续工作。
