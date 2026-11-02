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
if "PREFER_HANS_FIRST" not in t:
    m = re.search(
        r"public static String\[\] getPreferredTextLanguages\(\)\s*\{[\s\S]*?\n    \}",
        t,
    )
    if not m:
        print("[mod] FAIL getPreferredTextLanguages not found")
        sys.exit(1)
    new = """public static String[] getPreferredTextLanguages() {
        // PREFER_HANS_FIRST: Simplified before Traditional, ignore system region for Chinese
        Locale locale = Locale.getDefault();
        if (!isChinese(locale)) {
            String tag = locale.toLanguageTag();
            String language = locale.getLanguage();
            return tag.equals(language) ? new String[]{language} : unique(tag, language);
        }
        // zh-Hans / zh-CN first, then generic zh, then Hant
        return unique(TAG_HANS, "zh-CN", "zh-Hans", "chs", "sc", LANGUAGE_CHINESE, TAG_HANT, "zh-TW", "zh-Hant", "cht");
    }"""
    # TAG_HANS may not exist — fall back to string literals only
    if "TAG_HANS" not in t:
        new = """public static String[] getPreferredTextLanguages() {
        // PREFER_HANS_FIRST
        Locale locale = Locale.getDefault();
        if (!isChinese(locale)) {
            String tag = locale.toLanguageTag();
            String language = locale.getLanguage();
            return tag.equals(language) ? new String[]{language} : unique(tag, language);
        }
        return unique("zh-Hans", "zh-CN", "zh-Hans-CN", "chs", "sc", "zh", "zh-Hant", "zh-TW", "zh-HK", "cht");
    }"""
    t = t[: m.start()] + new + t[m.end() :]
    changed = True
    print("[mod] patched getPreferredTextLanguages")
else:
    print("[mod] getPreferredTextLanguages already PREFER_HANS_FIRST")

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
if "PREFER_HANS_FIRST" not in vt and "zh-Hans" not in vt:
    print("[mod] VERIFY FAIL languages")
    sys.exit(1)
print("[mod] inject_prefer_text_lang SUCCESS")
sys.exit(0)
