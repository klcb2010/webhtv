#!/usr/bin/env python3
"""
简体中文优先（EXO LangUtil + 可选 score + 注入标记）。
失败必须非 0 退出（除非文件不存在）。
"""
from __future__ import annotations
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
path = ROOT / "app/src/main/java/com/fongmi/android/tv/player/track/LangUtil.java"
print(f"[mod] inject_prefer_text_lang ROOT={ROOT}")
if not path.is_file():
    print("[mod] WARN LangUtil.java missing — skip")
    sys.exit(0)

t = path.read_text(encoding="utf-8")
changed = False

# --- getPreferredTextLanguages ---
# EXO and MPV consume the same fixed Simplified-Chinese-first order.
m = re.search(
    r"public static String\\[\\] getPreferredTextLanguages\\(\\)\\s*\\{[\\s\\S]*?\\n    \\}",
    t,
)
if not m:
    print("[mod] FAIL getPreferredTextLanguages not found")
    sys.exit(1)
new = """public static String[] getPreferredTextLanguages() {
        // PREFER_HANS_FIRST: fixed order shared with MPV; do not follow device region.
        return unique("zh-Hans", "zh-CN", "chs", "sc", "zh", "zh-Hant", "zh-TW", "cht");
    }"""
t = t[:m.start()] + new + t[m.end():]
changed = True
print("[mod] patched fixed Simplified-Chinese-first language order")

# --- getPreferredTextLanguageScore(String language) if present ---
if "PREFER_HANS_SCORE" not in t:
    m2 = re.search(
        r"public static int getPreferredTextLanguageScore\s*\(\s*String\s+\w+\s*\)\s*\{[\s\S]*?\n    \}",
        t,
    )
    if m2:
        score_fn = """public static int getPreferredTextLanguageScore(String language) {
        // PREFER_HANS_SCORE: higher = better; Simplified always beats Traditional
        if (language == null || language.isEmpty()) return 0;
        String l = language.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        if (l.startsWith("zh-hans") || l.equals("zh-cn") || l.equals("zh-sg") || l.equals("chs") || l.equals("sc") || l.equals("cc"))
            return 100;
        if (l.equals("zh") || l.startsWith("zh-")) {
            if (l.startsWith("zh-hant") || l.equals("zh-tw") || l.equals("zh-hk") || l.equals("zh-mo") || l.equals("cht"))
                return 40;
            return 80; // plain zh
        }
        if (l.contains("hans") || l.contains("simplified") || l.contains("简") || l.contains("簡体") || l.contains("简体"))
            return 95;
        if (l.contains("hant") || l.contains("traditional") || l.contains("繁"))
            return 35;
        return 0;
    }"""
        t = t[: m2.start()] + score_fn + t[m2.end() :]
        changed = True
        print("[mod] patched getPreferredTextLanguageScore")
    else:
        print("[mod] getPreferredTextLanguageScore not in upstream (ok)")

# --- isChinese helpers stay ---
if changed:
    path.write_text(t, encoding="utf-8")
# verify languages
vt = path.read_text(encoding="utf-8")
vm = re.search(r"public static String\\[\\] getPreferredTextLanguages\\(\\)\\s*\\{([\\s\\S]*?)\\n    \\}", vt)
expected = ['"zh-Hans"', '"zh-CN"', '"chs"', '"sc"', '"zh"', '"zh-Hant"', '"zh-TW"', '"cht"']
if not vm or any(x not in vm.group(1) for x in expected) or [vm.group(1).find(x) for x in expected] != sorted(vm.group(1).find(x) for x in expected):
    print("[mod] VERIFY FAIL fixed Hans-first order")
    sys.exit(1)
print("[mod] inject_prefer_text_lang SUCCESS")
sys.exit(0)
