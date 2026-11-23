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
            // 仅 TV 端、仅肥猫油管：首页推荐为空时，只拉取配置 JSON 中的第一个分类。
            if (result.getList().isEmpty()
                    && "YouTube".equals(site.getKey())
                    && "csp_Youtube".equals(site.getApi())
                    && com.fongmi.android.tv.setting.Setting.isYoutubeHomeFallbackEnabled()
                    && !result.getTypes().isEmpty()) {
                com.fongmi.android.tv.bean.Class firstType = result.getTypes().get(0);
                String typeId = firstType.getTypeId();
                if (!firstType.isHome() && !firstType.isFolder() && !typeId.isEmpty()) {
                    try {
                        Result category = categoryContent(site.getKey(), typeId, "1", false, new HashMap<>());
                        if (!category.getList().isEmpty()) {
                            result.setList(category.getList());
                            SpiderDebug.log("homeVideoFallback", "YouTube first category=%s, count=%s",
                                    typeId, category.getList().size());
                        }
                    } catch (Exception e) {
                        SpiderDebug.log("homeVideoFallback", e.getMessage());
                    }
                }
            }"""
target.write_text(source.replace(anchor, replacement, 1), encoding="utf-8")
print("[mod] injected YouTube first-category home fallback")
