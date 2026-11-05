# Agent Skill：ACCEPTANCE

## 目标
证明 TV Lock 满足整机访问门禁需求。不能仅凭 UI 截图或 APK 安装成功判定通过。

## 前置条件
- 已阅读 `AGENTS.md`、`docs/TEST-PLAN.md`。
- 确认设备型号和固件。
- 确认用户批准待机/重启/实体按键测试。
- 记录 APK 版本、Git commit、Device Owner 状态。

## 必测
1. 冷启动进入 PIN。
2. 短待机唤醒进入 PIN。
3. 长待机唤醒进入 PIN。
4. HOME、返回、最近任务、语音助手不能绕过。
5. 未解锁时不能启动其他应用或进入设置。
6. 错误 PIN 不放行，失败退避有效。
7. 正确 PIN 后 PatchWall 和正常应用可用。
8. 再次待机、重启后重新锁定。
9. 断网、ADB 关闭、守护异常时 fail-closed。
10. 管理员恢复路径可用。

## 验收方法
- 遥控器操作必须由真实遥控器完成。
- 记录屏幕亮起、锁屏首帧、前台窗口变化和日志。
- 测试中任何一次短暂进入未授权界面都记为 FAIL。
- 设备端没有执行的项目标 NOT RUN；用户未批准的标 SKIP。
- 连续 20 次待机唤醒全部成功后，才能标记待机稳定性测试通过。

## 交付
更新 `docs/TEST-PLAN.md`，生成验收报告，列出 PASS/FAIL/SKIP/NOT RUN、原始证据、剩余风险。更新 `docs/HANDOFF.md`。
