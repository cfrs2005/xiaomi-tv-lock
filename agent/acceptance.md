# Agent Skill：ACCEPTANCE

## 目标
证明 TV Lock 满足整机访问门禁需求。不能仅凭 UI 截图或 APK 安装成功判定通过。

## 前置条件
- 已阅读 `AGENTS.md`、`docs/TEST-PLAN.md`、`docs/RECOVERY.md`。
- 确认设备型号和固件（本机目标 `OS3.0.106.0.UFTMATV`）。
- 确认用户批准待机/重启/实体按键测试。
- 记录 APK 版本、Git commit、`tvlockctl.sh status` 的原始输出。
- **先跑通 S 系列**（`./build.sh`、`./test.sh`），有一项 FAIL 就不上机。

## 必测（对应 TEST-PLAN 编号）

**架构假设，先测这两个：**

1. **T29** 待机时锁窗口是否保留：唤醒首帧即为锁屏，不出现 PatchWall。
2. **T30** overlay 压 PatchWall 是否招致 `Force stopping com.toy.tvlock`。

**核心需求：**

3. T01 冷启动进入 PIN。
4. T02 / T03 短待机、长待机唤醒进入 PIN。
5. T05–T08 HOME、返回、最近任务、语音助手不能绕过（实体遥控器）。
6. T09 / T10 信号源、系统设置不能绕过。
7. T11 错误 PIN 不放行，退避生效且跨重启保留。
8. T12 正确 PIN 后 PatchWall 与常用应用可用。
9. T13 / T14 再次待机、冷启动后重新锁定。
10. T23 首次安装后要求设置密码，无默认密码。

**安全边界：**

11. T21 第三方应用发 `UNLOCK` Intent 无效。
12. T22 锁定状态下设置页不显示 PIN、不显示任何动作按钮。
13. T25 解锁后 force-stop 再拉起，回到 LOCKED。
14. T28 解锁耗时 ≤500ms（KDF 自适应标定是否符合预期）。

**恢复：**

15. R01–R04：`status` / `disable` / `arm` / `uninstall` 各自可用，且 `disable` 后电视恢复正常。

## 验收方法
- 遥控器操作必须由真实遥控器完成；`input keyevent` 不经过系统按键路径，不能替代。
- 每次待机/唤醒记录：屏幕亮起时间、锁屏首帧时间、前台窗口、ADB 是否恢复、
  `tvlockctl.sh dump` 输出。
- 测试中任何一次短暂进入未授权界面都记为 FAIL。
- 设备端没有执行的项目标 NOT RUN；用户未批准的标 SKIP。
- 连续 20 次待机唤醒全部成功后，才能标记待机稳定性测试通过（T04）。
- 不得把 Aerial 在 OS3.0.101 的结论当作本机证据。

## 交付
更新 `docs/TEST-PLAN.md`，生成验收报告，列出 PASS/FAIL/SKIP/NOT RUN、原始证据、剩余风险。
更新 `docs/HANDOFF.md`。若 T29 失败，必须在 HANDOFF 中写明架构需要改道，而不是继续修补。
