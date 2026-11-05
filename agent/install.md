# Agent Skill：INSTALL

## 目标
在不破坏用户现有电视环境的前提下安装/升级 TV Lock，并确认应用具备运行条件。

## 必读
先读 `AGENTS.md`、`docs/HANDOFF.md`、`docs/DEVICE-MFTR0.md`、`docs/RECOVERY.md`。

## 执行流程
1. `adb version`；`adb connect <IP>:5555`；`adb devices` 必须是 `device`。
2. 只读识别：型号、固件、SDK、ABI、现有包版本、悬浮窗 appops、通知监听列表、Device Owner 状态。
   直接用 `./tools/tvlockctl.sh status`。
3. 检查本机构建环境与签名密钥；**不得生成新密钥覆盖已有密钥**（会导致无法覆盖安装）。
4. `./build.sh`，保留输出与 APK 的 SHA256。构建脚本内置清单断言：无 `LockService`、
   有 `RevivalListener`、权限面只有两项。断言失败即停止。
5. `./test.sh`，42 项应全部 PASS。有 FAIL 不装。
6. 安装前确认签名一致：`apksigner verify --print-certs` 与已装版本同一证书。
7. `adb install -r build/tvlock.apk`。失败时不要把「卸载重装」当默认解法——先看错误码。
8. 授权：`./tools/tvlockctl.sh arm --yes`（悬浮窗 + 通知监听）。
   **通知监听是整套机制的锚点，没授权等于没装。**
9. 复核：`status` 中 `listenerEnabled=true`、`overlayPermission=true`。
10. 冷启动/待机测试需单独取得用户同意（属风险确认门）。

## 首次使用的行为
- 第一次解锁会要求设置 4 位密码并二次确认。
- 没有默认密码，装完不能直接进入。

## Device Owner
安装应用不等于注册 Device Owner。当前版本不含 DO 逻辑，`MyDeviceAdminReceiver` 是占位类。
不要在安装流程里尝试注册；不要退出小米账号、清数据或恢复出厂。

## 通过标准
包已安装、签名一致、悬浮窗与通知监听均已授权、`status` 里状态可读。
**「APK 安装成功」不等于「整机锁定成功」。** 后者需要 `agent/acceptance.md`。
