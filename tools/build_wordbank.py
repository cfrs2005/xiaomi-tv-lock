#!/usr/bin/env python3
"""Build assets/words_gaokao.csv from a public 高考 3500 word list.

Source: https://github.com/pluto0x0/word3500 (MIT), file 3500.txt.
The upstream file is three lines per entry: word / [phonetic] / "pos. meaning".

Why parse instead of hand-writing: the point of this file is accuracy, not
authorship. Deriving it from one traceable source means a wrong entry can be
traced back and reported upstream, and the whole bank can be rebuilt.

Usage:
    python3 tools/build_wordbank.py            # download and rebuild
    python3 tools/build_wordbank.py --check    # only report stats on existing csv
"""

import csv
import io
import os
import re
import sys
import urllib.request

SOURCE_URL = "https://raw.githubusercontent.com/pluto0x0/word3500/master/3500.txt"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets", "words_gaokao.csv")

# Upstream abbreviations, mapped to what the quiz shows next to the Chinese hint.
POS_MAP = {
    "n": "n.", "v": "v.", "vt": "v.", "vi": "v.",
    "a": "adj.", "adj": "adj.",
    "ad": "adv.", "adv": "adv.",
    "prep": "prep.", "conj": "conj.", "pron": "pron.",
    "num": "num.", "art": "art.", "int": "int.", "aux": "aux.",
    "abbr": "abbr.",
    # Upstream typos and one-off abbreviations seen in the data.
    "t": "v.", "vn": "v./n.", "vnum": "num.", "num": "num.",
}

POS_RE = re.compile(r"^((?:[a-z]+\.\s*(?:[/&]\s*)?)+)\s*", re.IGNORECASE)


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read().decode("utf-8", errors="replace")


def parse(text):
    """Yield (word, meaning) from the three-line-per-entry layout."""
    lines = [ln.strip() for ln in text.splitlines()]
    for i, line in enumerate(lines):
        if not line.startswith("["):
            continue
        # The word is the nearest non-empty line above, the meaning the one below.
        word = ""
        for j in range(i - 1, -1, -1):
            if lines[j]:
                word = lines[j]
                break
        meaning = ""
        for j in range(i + 1, len(lines)):
            if lines[j]:
                meaning = lines[j]
                break
        if word and meaning and not meaning.startswith("["):
            yield word, meaning


def normalize_word(raw):
    # "a (an)" -> "a"; drop brackets that only carry pronunciation variants.
    w = re.sub(r"\s*\([^)]*\)\s*$", "", raw).strip()
    w = re.sub(r"\s*-\s*", "-", w)          # "zebra -crossing" -> "zebra-crossing"
    w = re.sub(r"\s+", " ", w)
    return w


# Any POS token, used both to find the first one and to cut at the second.
ANY_POS = re.compile(r"(?<![A-Za-z])((?:n|v|vt|vi|a|ad|adj|adv|prep|conj|pron|num|art|int|aux|abbr)\.)\s*")


def split_pos(meaning):
    """Returns (canonical_pos, chinese_part).

    Upstream is inconsistent: most entries start with the POS, some put a
    parenthetical first ("(复 Frenchmen) n. 法国人") and some carry a second POS
    inside the same line ("int. 哎呀 a. 亲爱的"). Left alone, the second POS ends up
    in the Chinese hint shown during 默写, which is unusable.
    """
    head = meaning[:24]
    m = ANY_POS.search(head)
    if not m:
        # A few entries spell the POS without the dot ("n 笼；鸟笼", "t. 用泵抽水").
        m2 = re.match(r"^(n|v|t|vt|vi|a|ad)\s+", meaning)
        if m2:
            return POS_MAP.get(m2.group(1), ""), meaning[m2.end():].strip()
        # Phrases ("mobile phone") legitimately have no POS: leave it empty and let the
        # UI drop the row rather than print "other".
        return "", meaning.strip()

    # Keep only the part after the first POS, and drop what came before it
    # (a parenthetical such as the plural form).
    rest = meaning[m.end():].strip()

    # Cut at a second POS marker inside the Chinese text.
    second = ANY_POS.search(rest)
    if second and second.start() > 0:
        rest = rest[:second.start()].strip()

    raw_pos = m.group(1)
    key = raw_pos.rstrip(".").lower()
    pos = POS_MAP.get(key, "other")
    if pos == "other":
        # "a. & ad." style compounds: take the first token that maps.
        for t in re.findall(r"([a-z]+)\.", raw_pos, re.IGNORECASE):
            if POS_MAP.get(t.lower()):
                pos = POS_MAP[t.lower()]
                break
    return pos, rest.rstrip("；; ")


def build():
    print(">>> 下载", SOURCE_URL)
    text = fetch(SOURCE_URL)
    entries, seen, skipped = [], set(), []

    for raw_word, meaning in parse(text):
        word = normalize_word(raw_word)
        pos, zh = split_pos(meaning)
        if not word or not zh:
            skipped.append((raw_word, meaning))
            continue
        if not re.fullmatch(r"[A-Za-z\u00C0-\u024F][A-Za-z\u00C0-\u024F \-'’.]*", word):
            skipped.append((raw_word, meaning))
            continue
        if word.lower() in seen:
            continue
        seen.add(word.lower())
        entries.append((word, zh, pos))

    # The upstream list is alphabetical, so using it as the display order would make every
    # session run A-Z. sort_key is a fixed seeded permutation instead: stable across rebuilds
    # (diff stays readable) but unrelated to spelling.
    import random
    random.Random(3500).shuffle(entries)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with io.open(OUT, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["en", "zh", "pos", "level", "sort_key"])
        for i, (word, zh, pos) in enumerate(entries, start=1):
            w.writerow([word, zh, pos, 1, i])

    print(">>> 解析出 %d 条，跳过 %d 条 -> %s" % (len(entries), len(skipped), OUT))
    for raw, meaning in skipped[:10]:
        print("    跳过:", raw, "|", meaning[:40])
    return entries


def check():
    if not os.path.exists(OUT):
        print("没有找到", OUT)
        return
    with io.open(OUT, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    print("总计 %d 条" % len(rows))
    from collections import Counter
    print("词性分布:", Counter(r["pos"] for r in rows).most_common())
    lens = [len(r["en"]) for r in rows]
    print("词长: 最短 %d, 最长 %d, 平均 %.1f" % (min(lens), max(lens), sum(lens) / len(lens)))
    print("释义长度: 平均 %.1f 字" % (sum(len(r["zh"]) for r in rows) / len(rows)))
    print("抽查 8 条:")
    step = max(1, len(rows) // 8)
    for r in rows[::step][:8]:
        print("   ", r["en"], "|", r["pos"], "|", r["zh"])


if __name__ == "__main__":
    if "--check" in sys.argv:
        check()
    else:
        build()
        check()
