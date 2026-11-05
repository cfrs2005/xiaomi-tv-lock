# Agent Skill：DIAGNOSE

## 目标
当用户说电视锁失效、待机后不弹 PIN、遥控器可绕过、黑屏或无法退出时，Agent 自主收集证据并定位层级。

## 流程
1. 读取设备档案和最近 HANDOFF。
2. 连接 ADB；若不可用，记录连接状态，不要猜测。
3. 采集只读信息：
   - `getprop ro.product.model`
   - `getprop ro.build.display.id`
   - `getprop ro.build.version.sdk`
   - `dumpsys device_policy`
   - `dumpsys activity activities`
   - `dumpsys activity services com.toy.tvlock`
   - `pidof com.toy.tvlock`
   - `settings get global disable_back_home_whitelist`
   - `settings get secure enabled_notification_listeners`
   - `settings get secure enabled_accessibility_services`
   - `logcat -d -s MitvActivityManagerService ActivityManager ActivityTaskManager AerialGuard TVLock`
4. 分类问题：PIN/auth、Kiosk/Device Owner、进程生命周期、HyperOS force-stop、ADB 连接、UI/遥控器输入。
5. 将观察到的事实与假设分开；不要把 Aerial 在 OS3.0.101 的结果当成用户 OS3.0.106 已验证。
6. 对照测试矩阵选择最小复现步骤。
7. 修改前保存系统设置原值；每次只改变一个变量。
8. 修复后重复原测试并保存前后对比。

## 安全要求
只读诊断可直接进行。写入系统设置、待机、重启、改变 Device Owner/Kiosk 前必须取得用户同意。不得执行恢复出厂。

## 输出
生成设备诊断报告，包含设备信息（敏感信息脱敏）、命令、真实输出、判断、证据、建议操作、风险和回滚方案。
