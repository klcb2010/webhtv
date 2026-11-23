#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/fongmi/android/tv/api/SiteApi.java"
source = target.read_text(encoding="utf-8")

marker = "            if (!list.isEmpty()) result.setList(list);"
if "youtube_home_fallback" in source:
    print("[mod] YouTube home fallback already injected")
    raise SystemExit(0)
if marker not in source:
    raise SystemExit("[mod] ERROR: SiteApi home fallback injection point not found")

replacement = marker + """
            // TV-only fallback for the csp_Youtube spider, without modifying the Jar.
            if (list.isEmpty() && result.getList().isEmpty()
                    && "YouTube".equals(site.getKey())
                    && "csp_Youtube".equals(site.getApi())
                    && com.fongmi.android.tv.setting.Setting.isYoutubeHomeFallbackEnabled()) {
                for (Class type : result.getTypes()) {
                    String typeId = type.getTypeId();
                    if (type.isHome() || type.isFolder() || typeId.isEmpty()) continue;
                    try {
                        Result category = categoryContent(site.getKey(), typeId, "1", false, new HashMap<>());
                        if (!category.getList().isEmpty()) {
                            result.setList(category.getList());
                            SpiderDebug.log("homeVideoFallback", "YouTube category=%s, count=%s",
                                    typeId, category.getList().size());
                        }
                    } catch (Exception e) {
                        SpiderDebug.log("homeVideoFallback", e.getMessage());
                    }
                    break;
                }
            }"""
target.write_text(source.replace(marker, replacement, 1), encoding="utf-8")
print("[mod] injected YouTube home recommendations fallback")
