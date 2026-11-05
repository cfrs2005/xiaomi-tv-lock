# 词库来源与许可

`words_gaokao.csv` 不是手写的，由 `tools/build_wordbank.py` 从公开数据源解析生成。
这样做的目的很直接：**错了能追溯，整批能重建**。

| 项 | 值 |
|---|---|
| 上游仓库 | https://github.com/pluto0x0/word3500 |
| 上游文件 | `3500.txt` |
| 抓取时 commit | `7af7d3170cf4`（2019-10-24） |
| 上游许可 | MIT |
| 本仓库产物 | `assets/words_gaokao.csv`，3796 条 |
| 生成方式 | `python3 tools/build_wordbank.py` |

## 做了什么转换

上游是三行一条：`单词 / [音标] / 词性. 释义`，且不规范。生成时做了这些归一化：

| 处理 | 例子 |
|---|---|
| 去掉音标 | `[əˈbændən]` 整行丢弃 |
| 词性归一 | `a.`→`adj.`、`ad.`→`adv.`、`vt.`/`vi.`→`v.`、`t.`→`v.`、`vn.`→`v./n.` |
| 括号内的变形信息丢弃 | `(复 Frenchmen) n. 法国人` → `n.` + `法国人` |
| 截掉第二个词性 | `int. 哎呀！ a. 亲爱的` → `int.` + `哎呀！` |
| 连字符闭合 | `zebra -crossing` → `zebra-crossing` |
| 短语保留、词性留空 | `mobile phone` → `""` + `手机` |
| 大小写保留、去重按小写 | `I` 保持大写，不会变成 `i` |
| 顺序打散 | 用固定种子 3500 做排列，避免每次都是 A-Z |

词性无法识别的 57 条都是短语（`junk food`、`Christmas tree` 等），UI 会隐藏词性那一栏。

## 已知的上游瑕疵（未修，如实记录）

- `skipping | rope （跳绳用）绳`：上游把 `skipping rope` 断在了两行，单词被截成 `skipping`。
- `awesome | 令人惊叹,很困难的`：`很困难的` 疑为 `awesome` 的旧义，与当代用法不符。
- 少量释义用英文逗号分隔而不是中文分号，不影响阅读，未批量替换。

要修这些，应当改上游或在本仓库的 CSV 里手工覆盖，不要改解析器去特判个例。

## 许可全文

```
MIT License

Copyright (c) 2019 pluto0x0

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
