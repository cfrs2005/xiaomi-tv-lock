# 设备档案：Xiaomi TV S55 Mini LED / MiTV-MFTR0

## 用户设备记录

| 项目 | 值 |
|---|---|
| 产品 | Xiaomi TV S55 Mini LED |
| 设备代号 | MiTV-MFTR0 / freeguy |
| SoC | MediaTek MT9653 |
| Android | 14 / SDK 34 |
| HyperOS TV | 3 |
| 用户记录固件 | OS3.0.106.0.UFTMATV |
| Aerial 同型号公开记录 | OS3.0.101.0.UFTMATV（注意版本不同） |
| 原桌面 | com.mitv.tvhome/.MainActivityUserMode |
| 包名 | com.toy.tvlock |

## 已有用户实测

- 原生系统没有可用的整机开机 PIN；儿童锁不满足需求。
- `lockscreen.disabled=1`。
- `cmd device_lock` 没有 shell command implementation。
- `set-home-activity` / HOME role 返回成功不代表 HOME resolver 已切换。
- HOME 白名单受小米安全中心配置控制。
- 待机时 `MitvActivityManagerService.killAllThirdApp` 会对第三方应用 force-stop。
- 前台服务 + START_STICKY、PARTIAL_WAKE_LOCK、Doze 白名单、RUN_IN_BACKGROUND、Device Admin、AlarmManager 自检均未解决强杀。
- 唤醒后 ADB 可能被关闭。

原始证据见：
- `docs/2026-11-04-xiaomi-tv-lock-attempt.md`
- `docs/2026-11-04-xiaomi-tv-lock-research.md`

## Aerial 同型号研究

Aerial 的 MFTR0 设备笔记记录了通知监听服务被系统重新绑定的恢复路径：
https://github.com/pengchujin/aerial-tv/blob/main/docs/devices/xiaomi-mitv-mftr0.md

可借鉴：
- `NotificationListenerService` 的系统重绑定。
- `RevivalListener` 有限重试。
- `GuardSetup.ensureEnabled` 和 `rebindIfStalled` 的无障碍恢复。
- 开机时序、用户解锁等待、前台状态检查。
- 只读诊断、自检、报告工作流。

不可直接复制其桌面守护作为整机锁。Aerial 的目标是桌面回前台，不是阻止用户使用整个系统。

## 待验证设置

### disable_back_home_whitelist

只读：
```bash
adb shell settings get global disable_back_home_whitelist
```

候选写入：
```bash
adb shell settings put global disable_back_home_whitelist disable
```

写入前记录原值；写入后读回并做待机测试。测试失败则恢复原值。该设置对当前 OS3.0.106 是否有效尚未验证。

### STR whitelist

只读：
```bash
adb shell settings get system str_killbackgroud_pkg_white_list
adb shell getprop persist.sys.system_str
```

是否适用需依据当前固件实测；不要覆盖已有列表。

## 当前设备检查命令

只读检查：
```bash
adb devices
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.display.id
adb shell getprop ro.product.cpu.abi
adb shell pm path com.mitv.tvhome
adb shell settings get global disable_back_home_whitelist
adb shell settings get system str_killbackgroud_pkg_white_list
adb shell getprop persist.sys.system_str
adb shell dumpsys device_policy
adb shell dumpsys activity activities
adb shell pidof com.toy.tvlock
```

执行前确认 ADB 已连接到用户指定电视。不要把设备 IP、账号、序列号写入公共日志或提交。

## MFTR0 测试注意

- 必须用实体遥控器测试 HOME、返回、语音助手、电源键。
- `adb shell input keyevent` 不一定等价于真实遥控器路径。
- 待机唤醒前必须取得用户同意。
- 记录待机时长、唤醒时间、ADB 是否恢复、屏幕第一帧、锁屏出现时间和是否出现可操作窗口。
- 至少测试短待机、长待机、连续唤醒、冷启动。
