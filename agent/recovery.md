# Agent Skill：RECOVERY

## 目标
在 TV Lock 或 Kiosk 异常时恢复电视可用性，同时保留用户数据。

## 决策顺序
1. R0 应用自恢复：检查锁定状态、重新启动 LockActivity / RecoveryCoordinator。
2. R1 ADB 辅助恢复：只读检查 Device Owner、Lock Task、服务、进程；在用户批准后执行最小修复。
3. R2 Kiosk / Device Owner 恢复：使用经过验证的管理员恢复路径；先验证命令是否适用于当前 Owner 类型和 Android 版本。
4. R3 恢复出厂：仅当前三层失败、用户了解数据损失并再次明确批准时执行。

## 强制规则
- 恢复出厂绝不是安装、升级或 Device Owner 注册失败的自动步骤。
- 不自动退出小米账号、不清除电视数据。
- 不把 `dpm remove-active-admin` 当作通用 Device Owner 移除命令。
- 每次修改策略前读取并保存当前状态。
- 恢复后必须测试 PatchWall、HOME、设置、视频应用和 HDMI。
- 未验证过的恢复方法不得写成“可用”。

## 结束报告
说明故障原因、采取的恢复等级、执行命令和输出、数据影响、电视是否恢复正常、后续预防措施。
