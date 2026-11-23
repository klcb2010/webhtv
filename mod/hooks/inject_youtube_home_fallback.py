#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1])
target = root / "app/src/main/java/com/fongmi/android/tv/api/SiteApi.java"
source = target.read_text(encoding="utf-8")

anchor = """            if (!list.isEmpty()) result.setList(list);
            setTypes(site, result);"""
if "youtube_home_fallback" in source:
    print("[mod] YouTube home fallback already injected")
    raise SystemExit(0)
if anchor not in source:
    raise SystemExit("[mod] ERROR: SiteApi homeContent injection point not found")

replacement = anchor + """
            // 仅 TV 端、仅肥猫油管：原生首页推荐为空时，从首个有内容的分类兜底。
            if (result.getList().isEmpty()
                    && "YouTube".equals(site.getKey())
                    && "csp_Youtube".equals(site.getApi())
                    && com.fongmi.android.tv.setting.Setting.isYoutubeHomeFallbackEnabled()) {
                for (com.fongmi.android.tv.bean.Class type : result.getTypes()) {
                    String typeId = type.getTypeId();
                    if (type.isHome() || type.isFolder() || typeId.isEmpty()) continue;
                    try {
                        Result category = categoryContent(site.getKey(), typeId, "1", false, new HashMap<>());
                        if (!category.getList().isEmpty()) {
                            result.setList(category.getList());
                            SpiderDebug.log("homeVideoFallback", "YouTube category=%s, count=%s",
                                    typeId, category.getList().size());
                            break;
                        }
                    } catch (Exception e) {
                        SpiderDebug.log("homeVideoFallback", e.getMessage());
                    }
                }
            }"""
target.write_text(source.replace(anchor, replacement, 1), encoding="utf-8")
print("[mod] injected YouTube home recommendations fallback")
