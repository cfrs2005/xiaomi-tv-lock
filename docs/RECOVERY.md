# TV Lock — 故障恢复手册

本文件供 AI Agent 执行恢复。**恢复出厂是最后手段，不是正常部署步骤。**

## 恢复等级

### R0 — 应用自恢复
适用：LockActivity / LockService 异常、守护未绑定、UI 未显示。
- 检查包是否安装、进程、服务、崩溃日志。
- 检查锁定状态是否仍为 LOCKED。
- 通过受控内部流程重新启动 UI / coordinator。
- 不得因服务死亡将状态改为 UNLOCKED。

### R1 — ADB 辅助恢复
适用：设备仍可通过 ADB 连接。
- 先只读采集 `dumpsys device_policy`、Activity、进程、日志。
- 根据真实状态选择重启应用、恢复组件、修复授权。
- 所有写操作记录原值和回滚方法。
- ADB 重启电视前需用户明确同意。

### R2 — Device Owner / Kiosk 恢复
适用：Kiosk 配置错误、设备所有者策略导致无法正常退出。
- 优先通过应用内 PIN 管理员恢复流程退出 Lock Task。
- 若应用 UI 不可用，先确认 ADB 是否可用及当前 DO 状态。
- `dpm remove-active-admin` 不是对 Device Owner 通用的移除命令；不得盲目执行。
- Device Owner 移除可能受 Android provisioning / owner 限制；先查当前系统状态和官方 API 约束。
- 只有明确知道移除命令适用且用户批准时才执行。
- 验证 PatchWall、HOME、系统设置和正常应用恢复。

### R3 — 电视系统恢复出厂（灾难恢复）
仅当 R0–R2 失败，且用户理解电视设置、账号状态和本地数据可能丢失，并再次明确批准时使用。
- 不自动触发。
- 不将其写入默认安装脚本。
- 执行前导出所有可导出的诊断信息。
- 明确告知需要重新配置电视。
- 完成后确认原生 PatchWall 可用，TV Lock 不再阻断系统。

## 不允许的行为

- 不得在 Device Owner 注册失败时自动恢复出厂。
- 不得为了注册 Device Owner 自动退出用户小米账号。
- 不得停用/卸载 PatchWall 作为默认解决方案。
- 不得把未知状态当成已解锁。
- 不得执行未经用户批准的 reboot、factory reset、清数据、卸载系统应用或修改系统安全设置。
