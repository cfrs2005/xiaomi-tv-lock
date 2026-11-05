# TV Lock 2.0 — 架构设计

## 1. 设计目标

提供每次开机/待机唤醒都要求 PIN 的电视整机访问门禁。未解锁状态必须 fail-closed；解锁后恢复正常 PatchWall / App / HDMI 使用。

## 2. 核心原则

- **状态优先于进程**：锁定状态不能由 LockService 是否存活推断。
- **策略优先于覆盖层**：Device Owner + Lock Task 是候选系统控制路径；Overlay 仅为 UI/兼容辅助。
- **恢复独立**：恢复机制不能依赖被恢复的主 Service 自己存活。
- **厂商行为实测**：标准 Android API 不能代替 HyperOS 真机验证。
- **不破坏原桌面**：不得默认停用/卸载 PatchWall；HOME 白名单不可控时，评估其他方案并记录风险。
- **正常使用优先**：成功 PIN 后允许用户使用原有电视功能；下一次待机或重启再锁。

## 3. 逻辑组件

### Access state
- `LockStateManager`：持久化 LOCKED / UNLOCKED / RECOVERY_REQUIRED。
- `AuthSession`：记录当前解锁会话及生命周期边界。
- `PinManager`：PIN 设置、校验、失败退避；不存明文。
- 规则：BOOT、SCREEN_OFF / 进入待机事件、检测到重启均使状态进入 LOCKED。仅内部已验证授权路径可变为 UNLOCKED。

### UI
- `LockOverlay`：PIN 输入、首次设置、修改密码三个流程共用一套键盘窗口。
- 锁定窗口不提供公开 UNLOCK action，也不通过 Intent 控制；开关只由状态推导。
- 锁屏 UI 不应是唯一的安全控制；若 Kiosk 未生效，产品状态必须标为兼容模式而非“整机不可绕过”。

### Device policy
- `MyDeviceAdminReceiver`：DeviceAdminReceiver。
- `DeviceOwnerManager`：只读检查 isDeviceOwnerApp、当前 owner、已配置策略。
- `KioskController`：管理 lock task package allowlist、lock task features、进入/退出 lock task。
- 退出 Kiosk 必须依赖 PIN 成功后生成的一次性内部授权，不接受外部广播或无认证 Intent。

### Lifecycle / recovery
- `BootReceiver`：处理真实重启的兜底；校验 Intent action。
- `WakeObserver`：监听可用的屏幕/电源生命周期信号；不能假定 SCREEN_ON 在 force-stop 后仍可到达。
- `RevivalListener`：NotificationListenerService 复活锚点；不读取通知内容。
- `RecoveryCoordinator`：有限次数检查进程/服务/锁定状态，等待 USER_UNLOCKED，记录结果。
- UsageStats 可用于辅助诊断前台应用；轮询不是安全边界，且存在延迟。

### Xiaomi adapter
- `XiaomiAdapter`：检测设备属性、PatchWall 包、固件版本和已知设置项。
- 所有小米专用设置写操作都必须记录原值、变更值、验证结果和回滚命令。
- 不得硬编码假设某一设置在所有 HyperOS TV 版本有效。

## 4. 状态机

```
BOOT / WAKE / SCREEN_OFF
        |
        v
      LOCKED
        |
   PIN verified
        |
        v
     UNLOCKED
        |
   next standby / reboot
        |
        v
      LOCKED

Any guardian / policy failure -> RECOVERY_REQUIRED (fail closed)
```

待机事件的具体时序必须以 MFTR0 实测为准。不能仅在屏幕亮起后异步显示覆盖层并宣称“没有绕过窗口”。

## 5. Device Owner 与 Kiosk 边界

Device Owner 注册与 Lock Task 是两个不同步骤：
1. 先确认应用是否真的是 Device Owner。
2. 再配置允许的 Lock Task packages。
3. 配置禁用 Home / Recents / 通知栏等所需功能。
4. 进入 Lock Task。
5. 在 HyperOS 上用实体遥控器验证。
6. 解锁后退出 Kiosk 并恢复 PatchWall 的正常使用。
7. 待机唤醒后再次锁定。

Device Owner 不自动保证应用免疫厂商 force-stop，也不自动解决小米 HOME 白名单问题。Device Owner provisioning 对已初始化设备有前置条件；本项目不得将恢复出厂作为默认部署步骤。

## 6. PIN 安全

- 首次运行强制设置 PIN；不得使用 2025 等默认密码。
- 使用 PBKDF2-HMAC-SHA256（独立随机 salt、足够迭代次数）保存 verifier；迭代次数在目标设备性能上测量后确定。
- 失败次数和退避状态持久化，防止重启清零。
- 不在日志、诊断报告、通知、屏幕截图中输出 PIN 或 verifier。
- 管理员恢复凭证与日常 PIN 分离；恢复路径不能成为遥控器可猜测的隐藏按键组合。
- Keystore 可保护密钥材料，但不替代 PIN KDF。

## 7. 安全边界声明

本项目目标是阻止普通电视遥控器用户绕过。它不承诺抵御拥有 ADB 授权、设备管理权限、物理恢复模式、刷机能力或系统漏洞的攻击者。

任何未通过实体遥控器真机测试的限制都不得标记为强制生效。

## 8. 落地映射（2026-11-05，代码已实现，真机未验证）

| 设计组件 | 实现 | 状态 |
|---|---|---|
| Access state | `LockStateManager`（device-protected，默认 LOCKED） | 代码完成 |
| AuthSession | 无独立对象；`changeInProgress` + 状态迁移表达 | 简化实现 |
| PinManager | `PinManager` + `PinCrypto`（PBKDF2，迭代自适应 60k–250k） | 代码完成，42 项离机测试 PASS |
| Lock UI | `LockOverlay`（解锁 / 首次设置 / 修改密码） | 代码完成，未上机 |
| 不提供公开 UNLOCK | 删除 `LockService`；无该 action；组件均不导出解锁能力 | 代码完成 |
| Device policy | `MyDeviceAdminReceiver` 占位，无 DO 逻辑 | 未开始 |
| BootReceiver | 校验 action + `goAsync()` 工作线程 + 置 LOCKED | 代码完成，未上机 |
| WakeObserver | 屏幕广播 + `PowerManager.isInteractive` 双判据 | 代码完成，未上机 |
| RevivalListener | `RevivalListener`（NotificationListener，directBootAware） | 代码完成，未上机 |
| RecoveryCoordinator | `LockCoordinator` 的有界重试 + `RECOVERY_REQUIRED` 状态 | 代码完成，未上机 |
| UsageStats | 未使用（轮询不是安全边界，且 Aerial 实测有 100–250ms 延迟） | 不实现 |
| XiaomiAdapter | 未实现；当前无需写任何小米设置项 | 待 P1-2 决定 |

两个尚未被证实的假设（决定架构是否成立）：

1. 熄屏期间保留的 `TYPE_APPLICATION_OVERLAY` 窗口能否存活到唤醒，使首帧即为锁屏（T29）。
2. 该窗口压在 PatchWall 上是否会反过来招致 `Force stopping`（T30，Aerial 记录过同类现象）。

若第 1 条不成立，备选是 AccessibilityService 宿主 + `WRITE_SECURE_SETTINGS` 重绑；代价是
系统会在强停后永久移除无障碍授权，需要每次复活都写回，且 Aerial 实测按键过滤会破坏音量连发。
