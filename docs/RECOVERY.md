# TV Lock — 故障恢复手册

本文件供 AI Agent 与用户执行恢复。

**这不是部署文档。** 恢复出厂是最后手段，且本项目的任何代码路径都不会触发它。

## 0. 为什么恢复通道要先于功能落地

P0 的改动把「谁能解锁」收紧到只剩一条路：在锁屏上输入正确 PIN。收紧之后，一旦出现
「锁屏无法输入」「PIN 记错」「授权丢失」中的任何一种，电视就会变成一块砖——除非事先有一条
不依赖那把锁的退出路径。

因此本项目的顺序是：**先有本题第 3 节的解除手段，再收紧权限。**

## 1. 分级

| 等级 | 场景 | 手段 | 数据影响 |
|---|---|---|---|
| R0 | 应用自身异常（窗口没画出来、复活后没接管） | 应用自动重试 + 自愈；无需人工 | 无 |
| R1 | 需要判断真实状态 | `tools/tvlockctl.sh status` 只读诊断 | 无 |
| R2 | 锁屏挡住了电视，且无法在锁屏上操作 | `tvlockctl.sh disable`（**放弃保护**） | 保留应用与 PIN |
| R3 | 不再使用该应用 | `tvlockctl.sh uninstall` | 删除应用数据（含 PIN） |
| R4 | 电视系统本身故障 | 恢复出厂 | 全部数据 |

R2/R3 需要在电脑上执行 ADB 命令。它们不属于「绕过」——拥有 ADB 的人本来就在威胁模型之外
（见 `ARCHITECTURE-v2.md §7`）。

## 2. R0：应用自身的恢复正常路径

这些是代码行为，不需要人工介入，列在这里是为了让诊断时能区分「设计如此」与「坏了」。

| 情况 | 应用行为 |
|---|---|
| overlay 窗口 attach 失败 | 记 `RECOVERY_REQUIRED`，按 2s / 5s / 30s 重试，连续 3 次失败才保持该状态 |
| 悬浮窗权限被撤销 | 立即进入 `RECOVERY_REQUIRED`，每 10s 重试；用户重新授权后自动恢复为 LOCKED |
| 进程被固件强停 | 通知监听重绑 → 新进程读到的状态是 LOCKED → 重新挂锁 |
| 进程在「已解锁」状态下死亡 | 新进程启动时强制回到 LOCKED（宁多一次输入，不留未守状态） |
| 状态文件损坏 / 读失败 | 按 LOCKED 处理（fail-closed） |
| PIN 记录损坏 | 校验返回 ERROR，不放行；锁屏提示重新设置（设置流程需要连续两次输入） |

关键不变量：**没有任何代码路径会把「读不到状态」解释成「已解锁」。**

## 3. R2：解除保护（电视仍可用，但不再受保护）

### 3.1 只读确认当前状态

先指定设备（只有一台设备时可省略）：`export TV=电视IP:5555`

```bash
./tools/tvlockctl.sh status
```

输出包含：型号/固件、安装版本、进程 pid、悬浮窗 appops、通知监听是否在列、
应用自身诊断（`state=` / `windowAttached=` / `overlayPermission=` / 最近事件时间线）。

`state=RECOVERY_REQUIRED` 说明防护已经失效，不是「锁住了」。

### 3.2 解除

```bash
./tools/tvlockctl.sh disable --yes
```

实际执行三件事：

```bash
adb shell appops set com.toy.tvlock SYSTEM_ALERT_WINDOW ignore   # 锁窗口画不出来
adb shell cmd notification disallow_listener com.toy.tvlock/com.toy.tvlock.RevivalListener
adb shell am force-stop com.toy.tvlock                          # 结束进程
```

**这是「放弃保护」，不是「解锁」。** 执行后电视完全不受限。想恢复保护，重新执行
`arm --yes`，并在电视上输入 PIN。

### 3.3 只做诊断，不改状态

```bash
./tools/tvlockctl.sh dump
```

等同于 `dumpsys activity service com.toy.tvlock/.RevivalListener`，只读。

## 4. R3：卸载

```bash
./tools/tvlockctl.sh uninstall --yes
```

先解除保护再卸载，避免卸载过程中锁窗口残留。应用数据（含已设置的 PIN）一并删除。

## 5. R4：恢复出厂

仅当电视系统本身无法恢复（无法进入系统、ADB 不可用、反复崩溃）且用户明确要求时。

- 执行前尽可能导出诊断：`tvlockctl.sh status > tvlock-status.txt`。
- 需要用户理解：小米账号、已装应用、设置、本地数据将全部丢失。
- **禁止**因为 Device Owner 注册失败、PIN 忘记、授权丢失而恢复出厂——这三种都有 R2/R3 路径。
- 本项目任何脚本都不包含恢复出厂命令，也不允许加入。

## 6. 场景速查

| 现象 | 先查 | 结论与动作 |
|---|---|---|
| 待机唤醒后直接进 PatchWall | `status` 的 `listenerEnabled`、日志里有无 `listener connected` | 通知监听未授权 → `arm --yes`；已授权但仍无 → 记录时间线，属未解决缺陷（T02/T03） |
| 锁屏从没出现过 | `overlayPermission`、`state` | 未授权 → 授权；`state=RECOVERY_REQUIRED` → 看时间线里 attach 失败原因 |
| 锁屏出现但遥控器按键无反应 | `windowAttached`、是否有其他悬浮层（输入法/系统弹窗） | 记录机型与固件，属未验证缺陷 |
| PIN 输错太多次，提示等待 | `status` 里的 `waitMs` | 等待到期即可；等待是持久化的，重启不清零 |
| 忘记 PIN | — | **没有后门**。只能 R2 解除保护后重新部署（R3 重装或重新设置） |
| 电视被锁屏卡死、遥控器无效 | — | 在电脑上 R2；不要拔电反复重启，状态不会因此改变 |
| 装在电视上的其他 App 被怀疑能解锁 | `.MainActivity` 是否有解锁入口 | 已移除；现在 MainActivity 在锁定状态下不显示任何动作 |

## 7. 不允许的行为

- 不得在 Device Owner 注册失败时自动恢复出厂。
- 不得为了注册 Device Owner 自动退出用户小米账号。
- 不得停用/卸载 `com.mitv.tvhome` 作为默认解决方案。
- 不得把未知状态当成已解锁。
- 不得把 PIN、verifier、salt 写进日志、截图、诊断报告或提交记录。
- 不得执行未经用户批准的 reboot、factory reset、清数据、卸载系统应用或修改系统安全设置。
- 不得用隐藏按键组合、隐藏 Activity、专用广播等「暗门」充当恢复路径——那等于给绕过者留门。
