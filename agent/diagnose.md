# Agent Skill：DIAGNOSE

## 目标
当用户说电视锁失效、待机后不弹 PIN、遥控器可绕过、黑屏或无法退出时，自主收集证据并定位层级。

## 流程
1. 读设备档案与最近 HANDOFF。
2. `adb connect`；失败就记录连接状态，不要猜测。
3. **一条命令拿全貌**：
   ```
   ./tools/tvlockctl.sh status
   ```
   输出含型号/固件、安装版本、进程 pid、悬浮窗 appops、通知监听是否在列、应用自诊断。
4. 需要更细的时间线时：
   ```
   ./tools/tvlockctl.sh dump
   ```
   即 `dumpsys activity service com.toy.tvlock/.RevivalListener`，包含：
   `state=` `reason=` `pinSet=` `fails=` `waitMs=` `overlayPermission=` `listenerEnabled=`
   `windowAttached=` `interactive=`，以及最近 200 条事件（attach/detach、屏幕事件、复活、失败）。

## 判断表

| 观察 | 结论 |
|---|---|
| `listenerEnabled=false` | 复活锚点没授权，待机后必然不锁。执行 `arm --yes` |
| `overlayPermission=false` | 锁窗口画不出来，`state` 会是 `RECOVERY_REQUIRED` |
| `state=RECOVERY_REQUIRED` | 防护失效，不是「锁住了」。看时间线里 attach 失败的原因 |
| `state=LOCKED` 但 `windowAttached=false` | 状态正确、UI 没上去，属窗口层问题 |
| `waitMs>0` | 用户输错太多次，正在退避；属设计行为，等即可 |
| 日志有 `process restart found UNLOCKED` | 进程曾在解锁状态死亡，已自动重新上锁；属设计行为 |
| 日志里反复 `overlay attach failed` | 权限被撤或窗口被系统拒，需看异常类型 |
| 日志里频繁 `Force stopping` | 厂商强停，评估 overlay 是否触发了它（T30） |

5. 区分事实与假设；不要把 Aerial 在 OS3.0.101 的结果当成用户 OS3.0.106 已验证。
6. 对照 `docs/TEST-PLAN.md` 选最小复现步骤。
7. 修改前保存原值；每次只改一个变量。

## 安全要求
只读诊断可直接进行。写入系统设置、待机、重启、改变 Device Owner/Kiosk 前必须取得用户同意。
不得执行恢复出厂。不得打印 PIN、verifier、salt。

## 输出
设备诊断报告：设备信息（脱敏）、命令、真实输出、判断、证据、建议动作、风险和回滚方案。
