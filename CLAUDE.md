# Claude Code 项目入口

开始任何任务前，必须先阅读仓库根目录的 `AGENTS.md` 和 `docs/HANDOFF.md`。

随后按任务类型阅读：
- 安装/升级：`agent/install.md`
- 故障诊断：`agent/diagnose.md`
- HyperOS / MFTR0 适配：`agent/adapt.md`
- 异常恢复：`agent/recovery.md`
- 真机验收：`agent/acceptance.md`

`AGENTS.md` 是本项目的权威规则来源。本文件只负责让 Claude Code 自动发现并加载该规范，不重复维护第二份规则。

特别注意：不要自动恢复出厂；不要在未取得用户批准时重启电视、修改系统设置、注册/移除 Device Owner 或变更 Kiosk 策略。