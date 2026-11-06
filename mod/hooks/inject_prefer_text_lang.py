#!/usr/bin/env python3
"""Force preferred text languages: zh-Hans > zh-Hant > zh."""
from __future__ import annotations
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
path = ROOT / "app/src/main/java/com/fongmi/android/tv/player/track/LangUtil.java"
if not path.is_file():
    print("[mod] WARN LangUtil.java missing")
    sys.exit(0)

t = path.read_text(encoding="utf-8")
if "PREFER_HANS_FIRST" in t:
    print("[mod] LangUtil already PREFER_HANS_FIRST")
    sys.exit(0)

m = re.search(
    r"public static String\[\] getPreferredTextLanguages\(\)\s*\{[\s\S]*?\n    \}",
    t,
)
if not m:
    print("[mod] FAIL getPreferredTextLanguages not found")
    sys.exit(1)

new = """public static String[] getPreferredTextLanguages() {
        // PREFER_HANS_FIRST: Simplified Chinese before Traditional, independent of system region
        Locale locale = Locale.getDefault();
        if (!isChinese(locale)) {
            String tag = locale.toLanguageTag();
            String language = locale.getLanguage();
            return tag.equals(language) ? new String[]{language} : unique(tag, language);
        }
        return unique(TAG_HANS, TAG_HANT, LANGUAGE_CHINESE);
    }"""
t = t[: m.start()] + new + t[m.end() :]
path.write_text(t, encoding="utf-8")
if "PREFER_HANS_FIRST" not in path.read_text(encoding="utf-8"):
    print("[mod] VERIFY FAIL")
    sys.exit(1)
print("[mod] LangUtil PREFER_HANS_FIRST OK")
