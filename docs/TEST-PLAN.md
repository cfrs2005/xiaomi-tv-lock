# TV Lock — 测试与验收计划

## 状态标记

- **PASS**：有真实输出或真机观察证据，证据位置写在「证据」列。
- **FAIL**：测试执行且未达到预期。
- **SKIP**：因前置条件或用户未批准未执行。
- **NOT RUN**：尚未执行。
- 不得将 NOT RUN / SKIP 描述为通过。

分三类，不要混为一谈：

| 类别 | 含义 | 是否需要电视 |
|---|---|---|
| S 系列 | 静态检查、构建、离机单元测试 | 否 |
| T 系列 | MFTR0 真机行为验收 | 是 |
| R 系列 | 恢复路径演练 | 是（`disable` 可在无锁死风险时先演练） |

## 一、S 系列：构建与离机测试（可重复执行）

| ID | 测试 | 命令 | 预期 | 状态 | 证据 |
|---|---|---|---|---|---|
| S01 | 构建通过 | `./build.sh` | 退出码 0，产出 APK | PASS | 2026-11-05，33K，签名 SHA-256 `ae59ce09…38e1651` |
| S02 | 签名密钥未变 | `apksigner verify --print-certs` | 与历史 APK 同一证书 | PASS | 同一 SHA-256，可覆盖升级 |
| S03 | 清单无遗留绕过组件 | `build.sh` 内断言 | 无 `LockService` / `AlarmReceiver` | PASS | 断言通过 |
| S04 | 通知监听受系统权限保护 | `aapt2 dump xmltree` | `BIND_NOTIFICATION_LISTENER_SERVICE` + `directBootAware=true` | PASS | 2026-11-05 清单转储 |
| S05 | 权限面收窄 | `aapt2 dump badging` | 仅 `SYSTEM_ALERT_WINDOW` + `RECEIVE_BOOT_COMPLETED` | PASS | 2026-11-05 |
| S06 | PIN 派生正确性 | `./test.sh` | 同 salt 稳定、异 salt 不同、错 PIN 拒绝、异常输入拒绝 | PASS | 42 项全 PASS |
| S07 | 失败退避策略 | `./test.sh` | 前 4 次免费，之后 30/60/120/240s，上限 300s | PASS | 42 项全 PASS |
| S08 | KDF 迭代自适应 | `./test.sh` | 落在 60k–250k 区间，且用该值生成的 verifier 可验证 | PASS | 42 项全 PASS |
| S09 | 恢复脚本安全门 | `tvlockctl.sh disable`（无 --yes） | 拒绝执行，退出码 2 | PASS | 2026-11-05 |
| S10 | 恢复脚本 dry-run | `tvlockctl.sh disable --yes --dry-run` | 只打印命令，不改设备 | PASS | 2026-11-05 |

`./test.sh` 在本地 JVM 运行，不依赖 Android SDK、不连接电视。**它不能替代真机验证。**

## 二、T 系列：MFTR0 真机验收

> 全部为 NOT RUN。2026-11-05 接管时 `adb devices` 为空，未执行任何设备端测试。
> 源码重写后，下列每一项都必须在 OS3.0.106 上重新测，Aerial 在 OS3.0.101 的结论不能替代。

| ID | 测试 | 预期 | 状态 |
|---|---|---|---|
| T01 | 冷启动 | 进入 PIN 锁 | NOT RUN |
| T02 | 待机 30 秒后唤醒 | 进入 PIN 锁 | NOT RUN |
| T03 | 待机 10 分钟后唤醒 | 进入 PIN 锁 | NOT RUN |
| T04 | 连续待机唤醒 20 次 | 每次锁定 | NOT RUN |
| T05 | HOME | 未解锁不能绕过 | NOT RUN |
| T06 | 返回键 | 未解锁不能绕过 | NOT RUN |
| T07 | 最近任务 | 未解锁不能绕过 | NOT RUN |
| T08 | 语音助手 | 未解锁不能绕过 | NOT RUN |
| T09 | HDMI / 信号源 | 未解锁不能绕过或明确记录限制 | NOT RUN |
| T10 | 系统设置 | 未解锁不能进入受保护设置 | NOT RUN |
| T11 | 错误 PIN | 不解锁，退避计数生效 | NOT RUN |
| T12 | 正确 PIN | 允许正常使用电视 | NOT RUN |
| T13 | 解锁后再次待机 | 重新锁定 | NOT RUN |
| T14 | 解锁后冷启动 | 重新锁定 | NOT RUN |
| T15 | 强制停止 TV Lock | 保持 fail-closed 并可恢复 | NOT RUN |
| T16 | ADB 断开后唤醒 | 仍保持锁定或记录已知限制 | NOT RUN |
| T17 | Device Owner 注册 | 真实 owner 状态正确 | NOT RUN |
| T18 | Lock Task HOME / Recents | 系统策略生效 | NOT RUN |
| T19 | Kiosk 正常退出 | PIN 后恢复 PatchWall | NOT RUN |
| T20 | 恢复演练 | R2/R3 可用；R4 仅作最后兜底 | NOT RUN |

### 2.1 P0 新增验收项（本轮代码引入，尚未上机）

| ID | 测试 | 预期 | 状态 |
|---|---|---|---|
| T21 | 第三方应用发 `UNLOCK` Intent | 失败（组件未导出 / 无该 action） | NOT RUN |
| T22 | 打开「电视锁」设置页（锁定状态） | 不显示任何动作按钮，不显示 PIN | NOT RUN |
| T23 | 首次安装后的第一次解锁 | 要求设置 4 位密码并二次确认 | NOT RUN |
| T24 | 连续输错 5 次 | 第 5 次起出现等待倒计时，重启电视后等待仍有效 | NOT RUN |
| T25 | 解锁后 `am force-stop` 再拉起 | 回到 LOCKED，要求重新输入 PIN | NOT RUN |
| T26 | 解锁后撤销通知监听授权 | 下次待机唤醒不再锁定（记录为缺陷，不是通过） | NOT RUN |
| T27 | 修改密码流程 | 需先输当前密码，再输两次新密码 | NOT RUN |
| T28 | 解锁耗时 | 从第 4 位按键到锁屏消失 ≤ 500ms | NOT RUN |
| T29 | 待机时锁窗口是否保留 | 唤醒首帧即为锁屏，不出现 PatchWall | NOT RUN |
| T30 | overlay 压 PatchWall 是否招致强停 | logcat 中无新增 `Force stopping com.toy.tvlock` | NOT RUN |

T29 / T30 是本架构的两个核心假设，必须最先测。若 T29 失败，说明「熄屏期间保窗」不成立，
需要改走 AccessibilityService 宿主；若 T30 失败且频繁，说明 overlay 会被系统针对，需要重新评估。

## 三、R 系列：恢复演练

| ID | 演练 | 预期 | 状态 |
|---|---|---|---|
| R01 | `status` 只读诊断 | 输出完整状态，不改变任何设置 | NOT RUN |
| R02 | `disable --yes` 解除保护 | 电视恢复可用，应用仍安装 | NOT RUN |
| R03 | `disable` 后 `arm --yes` 恢复保护 | 锁屏重新生效 | NOT RUN |
| R04 | `uninstall --yes` | 应用与数据移除，电视正常 | NOT RUN |

## 四、执行要求

1. 先跑 S 系列（无需电视），再上机。
2. 每项破坏性/中断性测试前取得用户同意（待机、重启、改系统设置）。
3. HOME、返回、语音助手和电源键必须用实体遥控器，`input keyevent` 不能替代。
4. 每次待机/重启记录：屏幕亮起时间、锁屏首帧时间、前台窗口、ADB 状态、`dumpsys activity service com.toy.tvlock/.RevivalListener` 输出。
5. 测试结束确认电视能回到正常 PatchWall 和常用应用。
6. 更新本表：日期、固件、APK 版本、commit、命令、证据位置。
7. 任何一次短暂进入未授权界面都记为 FAIL，不改写成「基本可用」。
