# 下次怎么开始

复制下面任意一段发给 AI 就行。**不用补背景**——所有细节都在仓库里，提示词只负责说"从哪继续"。

选哪一段，看你想干什么：

---

## A. 继续写代码（推荐，不需要电视）

```
继续 xiaomi-tv-lock 项目，仓库在当前目录。

先按顺序读：AGENTS.md → docs/HANDOFF.md → docs/2026-11-05-word-unlock-tech.md。

现状：锁的 P0 已经在真机验证过（T02/T05/T06/T07/T11/T12/T15/T21/T23/T24a/T30 都 PASS）；
背单词解锁只出了设计和技术文档，一行代码没写；词库 assets/words_gaokao.csv 3796 条已就位。

这次做技术文档 §9 的阶段 1–2，不碰电视：
1. WordDb（SQLiteOpenHelper，表结构按文档 §3.2，首启从 assets 导入）
2. WordRepository（选词 SQL 按文档 §3.3，加标记 last_seen）
3. QuizRules + QuizSession（纯 Java，判分与 10 题会话状态机）
4. 把这三块的测试加进 ./test.sh，跑给我看真实输出

约束：不改 manifest 权限、不重启电视、不改系统设置；构建用 ./build.sh，测试用 ./test.sh。
任务结束更新 docs/HANDOFF.md。
```

---

## B. 上机补完验收（需要电视和你在场）

```
继续 xiaomi-tv-lock 项目，电视在 10.0.0.231:5555，ADB 已开。

先读 docs/HANDOFF.md 和 docs/TEST-PLAN.md。

这次只做真机验收，按这个顺序：
T29 唤醒首帧逐帧确认 → T04 连续 20 次待机唤醒 → T25 解锁后强停 → T24b/T01 重启类。

每一步采集 ./tools/tvlockctl.sh dump 和 logcat，把原始输出贴给我。
待机、重启这类会影响观看的动作，做之前先问我。

约束：不恢复出厂、不退出小米账号、不注册 Device Owner、不动 PatchWall。
```

---

## C. 继续做 UI（阶段 3–4，要在电视上看效果）

```
继续 xiaomi-tv-lock 项目，电视在 10.0.0.231:5555，ADB 已开。

先读 docs/HANDOFF.md、docs/2026-11-05-word-unlock-design.md（§4 界面部分）
和 docs/2026-11-05-word-unlock-tech.md（§1 架构约束）。

这次做背单词解锁的界面：学习页 / 默写页 / 结果页，全部在现有 overlay 窗口内切换，
不能做成 Activity（原因见技术文档 §1）。加上 15 分钟计时和到期自动上锁。

做完装到电视上截图给我看，我会用遥控器实际试。

约束：不动 PIN 那套逻辑；不自造新权限；改完更新 docs/HANDOFF.md。
```

---

## 通用收尾（想加到哪段都行）

```
结束前确认：./build.sh 通过、./test.sh 全绿、docs/HANDOFF.md 已更新、
不要声称没在真机测过的项已经通过。
```

---

## 为什么这些提示词这么短

因为它们不承载状态。状态在三个地方，且都是活的：

| 位置 | 内容 |
|---|---|
| `docs/HANDOFF.md` | 当前做到哪、哪项 PASS 哪项 NOT RUN、下一步优先级 |
| `docs/TEST-PLAN.md` | 每一条验收的编号、证据、状态 |
| `docs/2026-11-05-word-unlock-tech.md` §9 | 六阶段实施顺序 |

每次任务结束我都会更新这三个。**如果你发现 HANDOFF 和实际不符，以代码和真机为准**——
这条本身也写在 AGENTS.md 里。
