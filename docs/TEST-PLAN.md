# TV Lock — 测试与验收计划

## 状态标记

- PASS：有真实输出或真机观察证据。
- FAIL：测试执行且未达到预期。
- SKIP：因前置条件或用户未批准未执行。
- NOT RUN：尚未执行。
- 不得将 NOT RUN / SKIP 描述为通过。

## 当前测试矩阵

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
| T20 | 恢复演练 | R0/R1/R2 可恢复；R3 仅作为最后兜底 | NOT RUN |

## 测试执行要求

1. 先运行只读设备诊断，记录型号、固件、SDK、包状态、Device Owner 状态。
2. 每项破坏性/中断性测试前取得用户同意。
3. HOME、返回、语音助手和电源键使用实体遥控器。
4. 每次待机/重启记录前台窗口、锁屏首帧时间、ADB 状态、相关 logcat。
5. 测试结束确认电视能回到正常 PatchWall 和常用应用。
6. 更新本表，记录日期、固件、APK 版本、命令和证据位置。
