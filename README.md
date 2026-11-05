# TV Lock — 小米电视开机密码锁实验

> **状态：未完成（实验性）**
> 锁屏界面本身工作正常，但被小米电视待机时的系统级强杀阻断。根因与后续方向记录在 [`docs/`](docs/) 中。

一个针对 **Xiaomi TV / HyperOS TV 3** 的自制开机密码锁。目标是在电视开机或从待机唤醒时强制要求输入 4 位数字密码，否则无法进入桌面。

An experimental boot-time PIN lock for Xiaomi TVs running HyperOS TV 3 (Android 14). The lock overlay itself works; the blocker is Xiaomi's standby `killAllThirdApp` routine, documented below.

---

## 为什么做这个

小米电视（HyperOS TV 3 国行）**没有系统级的开机密码**：

| 尝试 | 结果 |
|---|---|
| 系统设置里的开机密码 | 不存在，`lockscreen.disabled=1` |
| 儿童锁 / 家长中心 | 只管「退出儿童模式」，不拦开机 |
| 酒店模式 | 需要厂商激活码，零售机 SN 不在范围 |
| `cmd device_lock`（Android 14 设备锁框架） | `No shell command implementation` |
| `set-home-activity` 换第三方桌面 | 返回 Success，但 HOME 解析仍写死给 PatchWall |

所以只能自己做。

## 已实现（实测可用）

- **全屏悬浮锁**：`TYPE_APPLICATION_OVERLAY` 覆盖整个屏幕，压住 PatchWall
- **Home 键压不掉**：按 Home 后 PatchWall 确实起在下面，但窗口栈 `Window #0` 仍是锁层，用户看不到
- **遥控器可操作**：D-pad 在 3×4 数字键盘间移动，OK 键确认，圆点计数正确
- **4 位密码校验**：错误提示、自动清空、正确解锁移除锁层
- **开机自启**：`BOOT_COMPLETED` 广播拉起服务

## 核心障碍

电视待机（按遥控器电源键）时，小米自家的 `MitvActivityManagerService` 会执行：

```
D MitvActivityManagerService: PowerManagerService killAllThirdApp forcestop packageName:<pkg>
I ActivityManager: Force stopping <pkg> appid=xxxx user=0: from pid 828
I ActivityManager: Killing xxxx:<pkg>/u0a(adj 200): stop <pkg>
```

注意是 **`Force stopping`**，不是 `stopService`。这意味着：

- AlarmManager 里注册的闹钟会被**一并清除**
- 应用被标记为 stopped 状态，无法自启
- 无障碍服务授权会被系统移除

已逐一验证**无效**的保活手段：

| 手段 | 结果 |
|---|---|
| 前台服务 + `START_STICKY` | 被杀 |
| `PARTIAL_WAKE_LOCK` | 无效，`stopService` 不看唤醒锁 |
| Doze 白名单 `deviceidle whitelist` | 无效 |
| `RUN_IN_BACKGROUND` appops | 无效 |
| 设备管理员（Device Admin） | 无效，激活成功照样被杀 |
| AlarmManager 每 5 秒自检 | 无效，force-stop 连闹钟一起清 |

## 已知的破解方向（尚未实现）

关键洞察来自 [Aerial TV](https://github.com/pengchujin/aerial-tv) 在同款机型上的实测：

**通知监听服务（`NotificationListenerService`）是唯一免疫 force-stop 的组件。**

| 组件 | 被强停后 |
|---|---|
| 无障碍服务 | 从启用列表永久移除，需人工重开 |
| 通知监听服务 | **授权保留，系统会主动重绑定** |

原因是 Android 把通知监听当作 system_server 的常驻绑定，设计目的是防止频繁冷启动（见 [官方文档](https://developer.android.com/topic/performance/memory/guide/service-bindings)）。强停后约 0.3–0.5 秒系统会自动重绑，此时 `onListenerConnected()` 回调即可用来复活进程。

**下一步要做的：**

1. 加 `RevivalListener extends NotificationListenerService`，声明 `directBootAware`，在 `onListenerConnected()` 里重新拉起 `LockService`
2. 删掉现有的 AlarmManager 逻辑（已证明无效）
3. 前台检测改用 `UsageStats` 轮询（无障碍会干扰 Android TV 的遥控器 OK 键）

另外两条可由 ADB 写入的小米豁免设置，尚未验证：

```bash
# 跳过「回桌面 + 清理后台」整套逻辑
adb shell settings put global disable_back_home_whitelist disable

# STR 深睡白名单（逗号分隔，只在开机时读取，需完整重启）
adb shell settings put system str_killbackgroud_pkg_white_list <package>
```

## 构建

不使用 Gradle，直接用 SDK 里的 `aapt2` + `javac` + `d8` + `apksigner` 手工打包，避免下载几百 MB 的构建插件。

**依赖：** JDK 17、Android SDK（`platforms;android-34` + `build-tools;34.0.0`）

```bash
export ANDROID_HOME="$HOME/android-sdk"   # 或改 build.sh 顶部的默认值
./build.sh
# 产物：build/tvlock.apk
```

首次构建会自动生成签名密钥 `tvlock.keystore`（已加入 `.gitignore`，不会提交）。

## 部署

```bash
adb connect <TV_IP>:5555
adb install -r build/tvlock.apk
adb shell appops set com.toy.tvlock SYSTEM_ALERT_WINDOW allow
adb shell am start-foreground-service -n com.toy.tvlock/.LockService -a LOCK
```

解锁：

```bash
adb shell am start-foreground-service -n com.toy.tvlock/.LockService -a UNLOCK
```

默认密码 `2025`，存在 `SharedPreferences` 的 `pin` 键，可在 `LockService.DEFAULT_PIN` 修改。

## 文件结构

```
AndroidManifest.xml          权限、服务、广播接收器注册
build.sh                     一键构建脚本（无 Gradle）
res/
  layout/lock_overlay.xml    全屏锁界面：标题 + 圆点 + 3×4 键盘
  drawable/btn_bg.xml        按钮焦点态样式
  values/styles.xml          按键样式
  xml/device_admin.xml       设备管理员策略
src/com/toy/tvlock/
  LockService.java           核心：悬浮窗管理、密码校验、闹钟自检
  BootReceiver.java          开机广播
  AlarmReceiver.java         闹钟触发（已被证明无效，保留备查）
  MyDeviceAdminReceiver.java 设备管理员（已被证明无效，保留备查）
  MainActivity.java          配置界面：立即锁定 / 解除锁定
docs/
  2026-11-04-xiaomi-tv-lock-attempt.md     实测记录与回滚清单
  2026-11-04-xiaomi-tv-lock-research.md    互联网方案深度检索（Aerial / TV App Lock / KidTVLock 等）
```

## 踩过的坑

| 坑 | 现象 | 解法 |
|---|---|---|
| 悬浮窗盖住了但没焦点 | 遥控器按键不生效 | 不要设 `FLAG_NOT_FOCUSABLE` |
| 输入法窗口升到最顶层 | 锁层被搜狗输入法盖住 | 加 `FLAG_ALT_FOCUSABLE_IM` + `SOFT_INPUT_STATE_ALWAYS_HIDDEN` |
| 触摸点击丢事件 | `adb shell input tap` 只切焦点不触发 click | 用遥控器按键，或加 `focusableInTouchMode` |
| Android shell 的 `sleep` 不支持小数 | 批量发按键命令中断 | 拆分执行或用整数秒 |
| 重建悬浮窗后输入没清空 | 圆点残留旧状态 | `showOverlay()` 开头 `input.setLength(0)` |
| keystore 放在 build 目录 | 每次重构建签名变化，无法覆盖安装 | 密钥移到项目根，`.gitignore` 排除 |

## 参考

- [Aerial TV](https://github.com/pengchujin/aerial-tv) — 同款机型的完整设备笔记，通知监听复活机制的来源
- [TV App Lock](https://github.com/oleksii-klots/tv-app-lock) — 用 UsageStats 轮询而非无障碍的另一种思路
- [KidTVLock](https://github.com/terranc/KidTVLock) — 同样主打「开机或唤醒时锁」的实现
- [FreeKiosk](https://github.com/RushB-fr/freekiosk) — Device Owner 真锁死方案

## License

MIT


## AI Agent 工作流

本项目面向 AI Agent 协作开发和设备运维。Agent 接手任务时必须先阅读：

1. [AGENTS.md](AGENTS.md) — Agent 全局规则、风险确认门和完成定义
2. [docs/HANDOFF.md](docs/HANDOFF.md) — 当前项目状态和下一步
3. [docs/DEVICE-MFTR0.md](docs/DEVICE-MFTR0.md) — 目标电视设备档案
4. [docs/ARCHITECTURE-v2.md](docs/ARCHITECTURE-v2.md) — TV Lock 2.0 架构
5. [docs/RECOVERY.md](docs/RECOVERY.md) — 分级恢复流程
6. [docs/TEST-PLAN.md](docs/TEST-PLAN.md) — 真机验收标准

任务执行手册位于 [agent/](agent/)：
- [INSTALL](agent/install.md)
- [DIAGNOSE](agent/diagnose.md)
- [ADAPT](agent/adapt.md)
- [RECOVERY](agent/recovery.md)
- [ACCEPTANCE](agent/acceptance.md)

**重要：** 这些文档是给 AI Agent 阅读并执行的工作指令，不是要求用户手工照做的教程。Agent 必须在有权限的电脑环境中亲自执行并核验真实结果；没有设备访问能力时必须明确说明。恢复出厂只允许作为最后的灾难恢复手段，绝不是正常部署步骤。
