# 背单词解锁 — 技术设计文档

> 日期：2026-11-05 · 对应产品文档：[`2026-11-05-word-unlock-design.md`](2026-11-05-word-unlock-design.md)
> 目标代码库：`com.toy.tvlock`（Java + 手工 aapt2/javac/d8，无 Gradle）

---

## 1. 一条决定架构的约束：UI 必须活在 overlay 里

现有锁屏是 `WindowManager` 上的 `TYPE_APPLICATION_OVERLAY` 窗口，不是 Activity。这决定了背单词页**不能做成 Activity**：

- `TYPE_APPLICATION_OVERLAY` 永远在普通 Activity 之上。启动 Activity 只能出现在锁层**下面**，用户看不见。
- 若为了显示 Activity 先撤掉锁层，就出现了一段「无保护窗口」——按 HOME 或最近任务就能落进 PatchWall。这正好破坏项目最核心的不变量。

所以：**背单词的所有页面都在同一个 overlay 窗口内切换**，窗口本身全程不撤。

代价与做法：

| 代价 | 做法 |
|---|---|
| 一个窗口要装两套 UI | 根布局拆成 `page_pin` 与 `page_word` 两个容器，切 `visibility` |
| 焦点管理复杂 | 每页只允许一组可聚焦控件；切页时显式 `requestFocus()` 到该页首项 |
| 返回键要分流 | `LockRootLayout` 的 `BackHandler` 增加一个 `QuizController` 分支 |
| 布局代码变长 | 拆成独立 layout 文件，按阶段 inflate 进 `page_word` 容器 |

## 2. 状态模型

### 2.1 访问状态增加「带期限的已解锁」

现有 `LockStateManager` 只有 LOCKED / UNLOCKED / RECOVERY_REQUIRED，UNLOCKED 没有期限。新增两个字段（同库、同 device-protected 存储）：

```
unlock_until    long   // 到期时刻（wall clock, epoch ms）；0 = 无期限（兼容旧数据）
unlock_source   string // "pin" | "quiz"
```

规则：

- 任何 unlock 都写入 `unlock_until = now + 15min`。
- 读取时若 `state == UNLOCKED && unlock_until > 0 && now >= unlock_until` → 视作已过期，落盘为 LOCKED。
- 这条判断放在 `LockCoordinator.reconcile()` 的入口，**任何路径进来的 reconcile 都会先过一遍**：屏幕亮起、进程复活、手动操作。
- `SCREEN_OFF` 仍然立刻 LOCKED 并清 `unlock_until`（待机不续期，见产品文档 §2.1）。

### 2.2 计时器

两个计时源，各管一段：

| 计时源 | 用途 | 为什么 |
|---|---|---|
| `Handler.postDelayed` (elapsedRealtime 语义) | 进程活着时的到点上锁与 1 分钟 / 10 秒提示 | 不受用户改系统时间影响 |
| 落盘的 `unlock_until` (wall clock) | 进程被杀、重启后恢复判断 | 必须跨进程存活 |

进程被厂商强停是常态（待机即发生），所以**落盘那条是主判据，Handler 只是让它准点触发**，不能反过来依赖。

```
unlock(source):
    until = now + 15min
    LockStateManager.unlock(source, until)
    coordinator.scheduleRelock(until)      // Handler，秒级精度
    detach overlay

reconcile():
    if state == UNLOCKED and expired(until): lock("session_expired")
    ...
scheduleRelock(until):
    postDelayed(relockTask, until - now)
    postDelayed(warn1min, until - now - 60s)
    postDelayed(warn10s,  until - now - 10s)
```

提示用 overlay 顶部一条 40dp 高的细条（`TYPE_APPLICATION_OVERLAY`，`FLAG_NOT_FOCUSABLE`），**不能抢焦点**，否则正在看的视频会被打断操作。10 秒条消失后由主窗口的 relock 接管。

## 3. 数据层

### 3.1 存储位置

```java
Context dp = context.createDeviceProtectedStorageContext();
SQLiteDatabase db = dp.openOrCreateDatabase("words.db", MODE_PRIVATE, null);
```

放 device-protected 存储的原因和 `LockStateManager` 一样：锁屏可能在 direct boot 阶段就要用，而凭据加密存储此时不可读。

### 3.2 表结构

```sql
CREATE TABLE words (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    en            TEXT NOT NULL UNIQUE,      -- 小写规范
    zh            TEXT NOT NULL,             -- 中文释义，多义用 ；
    pos           TEXT NOT NULL,             -- n. / v. / adj. / adv. / prep. / conj. / pron.
    level         INTEGER NOT NULL DEFAULT 1,-- 1 高频 / 2 常考 / 3 拓展
    sort_key      INTEGER,                   -- 固定打散顺序，避免每次从 A 开始
    enabled       INTEGER NOT NULL DEFAULT 1,-- 预留：单个词可停用
    seen_count    INTEGER NOT NULL DEFAULT 0,
    right_count   INTEGER NOT NULL DEFAULT 0,
    wrong_count   INTEGER NOT NULL DEFAULT 0,
    last_seen_at  INTEGER,                   -- epoch ms；NULL = 从未出现
    last_result   INTEGER                    -- 1 对 / 0 错
);
CREATE INDEX idx_words_due ON words(enabled, seen_count, last_seen_at);

CREATE TABLE sessions (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at    INTEGER NOT NULL,
    finished_at   INTEGER,
    rounds        INTEGER NOT NULL DEFAULT 1,
    correct_last  INTEGER,                   -- 最后一轮答对数
    passed        INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE answers (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    INTEGER NOT NULL,
    round         INTEGER NOT NULL,
    word_id       INTEGER NOT NULL,
    typed         TEXT,                      -- 拼错的答案，用于错题本
    correct       INTEGER NOT NULL,
    at            INTEGER NOT NULL
);
CREATE INDEX idx_answers_session ON answers(session_id, round);
```

`answers` 不是必需表，但它让「错题本导出」和「以后做记忆曲线」不必改 schema。3500 词规模下体积可忽略。

### 3.3 选词算法

每次取 10 个，一条 SQL，不做内存 shuffle：

```sql
SELECT id, en, zh, pos
FROM words
WHERE enabled = 1
  AND (last_seen_at IS NULL OR last_seen_at <= ?)   -- ? = now - 7*86400_000
ORDER BY
  seen_count ASC,                                   -- 没做过的优先
  COALESCE(last_seen_at, 0) ASC,                    -- 同次数里最久没见的先来
  level ASC,                                        -- 高频词优先
  COALESCE(sort_key, 0) ASC
LIMIT 10;
```

设计说明：

- **不做 7 新 + 3 复习的配额**。配额的参数需要长期调，且会把逻辑散到两处 SQL。单条排序天然满足：新词 `seen_count=0` 永远排在前，复习词按"最少见 + 最久没见"自然回补。
- 7 天冷却靠 `WHERE` 硬过滤，**不是**靠权重压制。规则是硬的，实现也该是硬的。
- 取完立刻 `UPDATE ... SET last_seen_at = now, seen_count = seen_count + 1 WHERE id IN (...)`——**在用户看到学习页时就写**，不是等答完。这样中途退出也算"做过"，符合产品文档里"做过的词 7 天不再出现"。
- 数量不足时返回实际条数，由上层判断是否够开一局（≥5）。

### 3.4 判分

```
correct = 10 题里 compare(typed, expected) 的命中数
pass    = correct >= 7          // 严格 >60%
```

常量集中在 `QuizRules`，与产品文档 §7.1 的方案 A 对应。比较时两侧 `trim().toLowerCase()`，不忽略拼写错误。

### 3.5 词库导入

```
/assets/words_gaokao.csv         3796 条，首启建库时事务插入
/sdcard/Download/tvlock_words.csv   家长手动导入，覆盖式合并（按 en 去重）
```

CSV 格式（首行表头必须存在）：

```csv
en,zh,pos,level,sort_key
well,很好地；水井,adv.,1,120
environment,环境,n.,1,340
```

导入实现要点：

- 逐行解析，**整批一个事务**；任何一行格式错则整体回滚并返回行号，不留半份数据。
- 已存在的 `en` 只更新 `zh/pos/level`，**不动学习进度**（`seen_count` 等）。
- 导入完成后 `VACUUM` 一次，避免 3500 行的页碎片。

## 4. 会话状态机

`QuizSession` 是纯 Java 类（不 import android），可离机单测：

```
状态: STUDY -> TEST(i=0..9) -> RESULT(correct) -> { UNLOCK | NEXT_ROUND }
```

| 迁移 | 触发 | 副作用 |
|---|---|---|
| LOCKED → STUDY | 点到"背单词解锁" | 取 10 词、写 `last_seen_at`、建 session 行 |
| STUDY → TEST(0) | 点"开始默写" | 隐藏学习页，渲染第 1 题 |
| TEST(i) → TEST(i+1) | 提交或答错后 1.5s | 记 `answers` 行 |
| TEST(9) → RESULT | 第 10 题结束 | 统计 correct、写 `sessions` |
| RESULT → UNLOCK | correct ≥ 7 | `unlock("quiz", 15min)` |
| RESULT → STUDY | correct ≤ 6 | 新取 10 词、`rounds++` |
| 任意 → LOCKED(取消) | 返回键 | 丢弃本会话，已写过的 `last_seen_at` 不回收 |

最后一条要特别说明：**已经写进 `last_seen_at` 的词不会因为中途退出而放回池子**。这是有意的——否则孩子可以反复进出刷同一批熟词。代价是中退会浪费掉这 10 个词 7 天的学习机会。

## 5. 代码改动清单

### 5.1 新增

| 文件 | 职责 | 约行数 |
|---|---|---|
| `WordDb.java` | SQLiteOpenHelper：建表、种子导入、CSV 导入、统计 | ~220 |
| `WordRepository.java` | 选词、标记、错题导出（SQL 都在这里） | ~150 |
| `QuizRules.java` | 纯逻辑：判分、通过线、字母砖块生成 | ~120 |
| `QuizSession.java` | 纯逻辑：10 题会话状态机 | ~150 |
| `QuizController.java` | 把 QuizSession 渲染到 overlay 容器、处理焦点与按键 | ~300 |
| `res/layout/quiz_study.xml` | 学习页（10 词列表 + 开始按钮） | — |
| `res/layout/quiz_test.xml` | 默写页（提示 + 下划线 + 字母砖块 + 退格/提交） | — |
| `res/layout/quiz_result.xml` | 结果页（正确率 + 错题回看 + 主按钮） | — |
| `res/drawable/tile_bg.xml` | 字母砖块两态 | — |

### 5.2 修改

| 文件 | 改动 |
|---|---|
| `LockStateManager` | 增加 `unlockUntil` / `unlockSource`；`unlock()` 带期限参数；`isExpired()` |
| `LockCoordinator` | 到期判断进 `reconcile()` 入口；新增 `scheduleRelock()` 与两条提醒；`SCREEN_OFF` 清期限 |
| `LockOverlay` | 根布局加载 `page_pin` + `page_word`；新增"我不知道密码"入口；页面切换与焦点移交 |
| `LockRootLayout` | `BackHandler` 增加 quiz 分支：有会话时取消会话，无会话时留在 PIN 页 |
| `MainActivity` | 词库统计、导入、重置进度、导出错题本（均为锁定后可用的父级维护） |
| `test/RunTests.java` | 新增判分、通过线、砖块生成的单测 |

### 5.3 不改

manifest 权限、`RevivalListener`、`BootReceiver`、构建脚本。这个特性不引入任何新权限——**这点很重要**，一个学习功能没有理由扩大攻击面。

## 6. 失败模式

| 失败 | 检测 | 行为 |
|---|---|---|
| 数据库损坏 | `SQLiteDatabase` 打开抛异常 | 背单词入口隐藏，只留 PIN；写 DiagLog |
| 词库为空 | `count(*) == 0` | 同上 |
| 到期词 < 5 | 选词返回 < 5 | 提示"词库不够了，先用密码"，入口保留但不可用 |
| 导入中途格式错 | 解析异常 | 整个事务回滚，返回出错行号 |
| 解锁后计时器被系统延迟 | `reconcile()` 每次校验 | 迟到的 relock 仍然执行，最多多放行一次 Handler 间隔 |
| 锁层被强停时正在默写 | 新进程状态 LOCKED | 会话丢失，重新从 PIN 开始（符合 fail-closed） |

## 7. 性能

| 指标 | 目标 | 依据 |
|---|---|---|
| 建库 + 3796 条种子 | < 800ms | 单事务批量插入，索引延后到插入后建 |
| 导入 3500 条 | < 3s | 单事务 + 索引延后到导入后建 |
| 单次选词查询 | < 10ms | `idx_words_due` 覆盖排序前两列 |
| 学习页渲染 10 项 | < 50ms | 10 个 TextView |
| 默写页切题 | < 16ms（1 帧） | 复用同一批 View，只改文本与砖块 |
| 数据库体积（3500 词 + 1 年答题记录） | < 2MB | 每行约 150 字节 |

MT9653 是 32 位 ARM，以上按台式机实测 ×4 估算仍有富余。真机必须实测，不能拿估算当结论。

## 8. 测试

### 8.1 离机（加入 `./test.sh`）

- 判分：9/10 通过、7/10 通过、6/10 不通过、0/10 不通过
- 大小写与空格归一化
- 字母砖块：只含答案字母 + 恰好 3 个干扰字母；长度正确；不含答案顺序泄漏
- 会话状态机：10 题推进、中途取消、第二轮重置

### 8.2 真机（加入 `docs/TEST-PLAN.md` W 系列）

见产品文档 §8 的 W01–W12。其中 W01/W02/W03 涉及计时，必须用真实时钟走完 15 分钟，不能用缩短常量冒充。

## 9. 实施顺序

| 阶段 | 内容 | 可验证产出 |
|---|---|---|
| 1 | `WordDb` + 种子 + `WordRepository` + 选词单测 | 离机测试能取到 10 个不重复、7 天内不重样的词 |
| 2 | `QuizRules` + `QuizSession` + 判分单测 | 离机测试全绿，不碰 UI |
| 3 | overlay 内页面切换 + 学习页 + 默写页 | 真机可完整做完一组，不出 overlay |
| 4 | 15 分钟计时 + 提醒条 + 到期上锁 | 真机 W01/W02/W03 |
| 5 | 家长维护页（统计/导入/重置/导出） | 真机 W11 |
| 6 | 词库从 300 扩到 3500 | 导入脚本 + 抽查 30 条人工核对 |

阶段 1–2 不依赖电视，可以立即开始；3 之后每一步都要上机。

## 10. 遗留风险

1. **词库数据质量**是本功能最大的风险，不是代码。现有 3796 条来自公开 MIT 词表并做了归一化，
   但上游本身有瑕疵（`assets/NOTICE.md` 列了三处已知问题）。**上线前必须人工抽查至少 50 条**，
   尤其是高频词——错一个释义，孩子就记错一个词。
2. **遥控器拼写体验**：字母砖块是折中方案。若孩子反馈仍然难按，退路是"选择题"模式（4 选 1），但那已经不是默写。
3. **孩子可能背答案**：学习页停留时间不限，理论上可以拍照或死记 10 个词再默写。这是产品取向问题，不是缺陷——真要防，得改成"不预先展示、直接默写"。
4. **15 分钟未必够看完一集动画**，中途上锁可能导致反复。可以考虑"到点提示，家长 PIN 可续 15 分钟"，暂未纳入。
