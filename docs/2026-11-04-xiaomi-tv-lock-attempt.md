# 小米电视 S55 Mini LED 开机密码锁尝试记录

日期：2026-11-04
设备：MiTV-MFTR0（小米电视 S55 Mini LED，代号 `freeguy`，联发科 mt9653，4GB）
系统：Android 14 / SDK 34，HyperOS TV 3 国行 `OS3.0.106.0.UFTMATV`
局域网 IP：`192.168.1.100`

## 需求

不输入 4 位密码就不能看电视，连 1 分钟都不行。不是儿童模式那种「换成儿童内容」，是硬拦。

## 一句话结论

**这台电视没有系统级开机锁；而任何第三方应用方案，都会被待机流程里的 `killAllThirdApp` 强制终止，软件层面无解。**

## 原生能力排查（全部不存在）

| 尝试 | 结果 |
|---|---|
| 系统设置里的开机密码 | 不存在，`lockscreen.disabled=1`（锁屏被固件关死） |
| 儿童锁 | 只管「退出儿童模式」，不拦开机 |
| 酒店模式 `EnableHotelModeActivity` | 需要厂商激活码，提示「该设备 SN 不在酒店 SN 范围内」 |
| Android 14 设备锁框架 `cmd device_lock` | `No shell command implementation`，未开放 |
| `set-home-activity` 换桌面 | 返回 Success，但 `resolve-activity` 仍返回 PatchWall，HOME 解析被固件写死 |

## 自制锁屏 APK 的尝试结果

代码资产：`tv-lock/`（构建脚本 `build.sh`，产物 `build/tvlock.apk`）

**已验证可行的部分：**

- 悬浮窗（`TYPE_APPLICATION_OVERLAY`）能压住 PatchWall，**按 Home 键压不掉**（窗口栈 `Window #0 com.toy.tvlock`，PatchWall 在 `#4`，截图零变化）
- 遥控器可操作，焦点能在 9 个数字键间移动，圆点计数正常
- 密码校验、解锁、错误提示均正常

**已验证无效的保活手段（关键部分）：**

| 手段 | 结果 |
|---|---|
| 前台服务 + `START_STICKY` | 待机时被 stop |
| `PARTIAL_WAKE_LOCK` | 无效，`stopService` 不看唤醒锁 |
| Doze 白名单 `deviceidle whitelist` | 无效 |
| `RUN_IN_BACKGROUND` appops | 无效 |
| 设备管理员（Device Admin） | 无效，激活成功但照样被杀 |
| AlarmManager 每 5 秒自检 | 无效，`Force stopping` 会连闹钟一起清掉 |

## 根因（决定性证据）

待机时刻系统日志：

```
D MitvActivityManagerService: PowerManagerService killAllThirdApp forcestop packageName:com.toy.tvlock
I ActivityManager: Force stopping com.toy.tvlock appid=10007 user=0: from pid 828
I ActivityManager: Killing 2292:com.toy.tvlock/u0a(adj 200): stop com.toy.tvlock
```

要点：

- 执行者是小米自家的 `MitvActivityManagerService`，不是标准 Android 内存回收
- 动作是 `Force stopping`，不是 `stopService` —— **会连 AlarmManager 里注册的闹钟一并清除**，应用被标记为 stopped 状态无法自启
- 触发时机是 STR 待机（`last_str_time`、`low_power_standby_enabled=1`）

## 明天从这里继续（两条待验证的路）

### 路线 A：让电源键走真关机，而不是 STR 待机

冷启动走 `BOOT_COMPLETED`，和待机唤醒是两条完全不同的路径，`killAllThirdApp` 不一定拦截。

已尝试但**未完成验证**：把 `settings put global low_power_standby_enabled` 改为 `0`（已回滚为 `1`）。

下次要做的：

1. 重新设为 `0`
2. 按电源键观察：是瞬间黑屏（还是 STR）还是有明显关机过程
3. 若能变成真关机，再验证开机后 APK 是否自启锁屏
4. 相关设置项还有 `mitv.short.power.action=2`（电源键动作枚举，值含义未查清）

### 路线 B：智能插座物理断电

几十块钱，断电 = 真关机，开机必锁，100% 有效。缺点是不是「密码」而是「没电」，且每次要重新冷启动。

## 部署方式（重新启用时）

```bash
cd tv-lock && ./build.sh
adb connect 192.168.1.100:5555
adb install -r build/tvlock.apk
adb shell appops set com.toy.tvlock SYSTEM_ALERT_WINDOW allow
adb shell am start-foreground-service -n com.toy.tvlock/.LockService -a LOCK
```

密码存在 `SharedPreferences`，键名 `pin`，默认值 `2025`。

## 已回滚项（2026-11-04 收尾）

- `low_power_standby_enabled` → 恢复为 `1`
- 设备管理员已移除
- 应用已卸载，省电白名单与 appops 无残留
- 儿童锁（4 位数字密码，用户自定）**保留未动**，是用户自己设的原生功能

## 顺带确认的可用信息

- 家长控制所有页面都在 PatchWall（`com.mitv.tvhome`）里，不在系统设置里：
  - `.business.kidcontrols.ParentsControlsActivity` 家长中心
  - `.business.kidcontrols.KidLockOptionActivity` 解锁方式（4 种：加减/乘除/方程/自定义数字密码）
  - `.business.kiddesk.KidsHomeActivity` 儿童桌面
- 这些页面可以用 `am start -n` 直接跳转，绕开一层层菜单
- 该机型的数字密码键盘在电视上用遥控器聚焦困难，用 `adb shell input tap` 按坐标点更可靠
