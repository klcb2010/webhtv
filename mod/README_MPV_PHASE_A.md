# MPV 阶段 A（2026-11-02 东八区）

## 行为
- **默认内核仍为 EXO**（不改 getPlayer 默认值）
- **不再**运行 `inject_disable_mpv` / `inject_strip_mpv_assets`
- 恢复打入 `mpv-libs`，体积会变大
- 在 `MpvPlayer` 的 option 注入点写入 gpu/vulkan/hwdec/tone-mapping 等默认 conf
- `inject_silence_mpv_size` 仍可去掉 MPV_SIZE 刷屏日志

## 尚未包含（阶段 B/C）
- MPV 原画截图（screenshot-raw）
- 个性设置「MPV 原图截图」与亮度档
- MPV 字幕字体完整对接（此前仅颜色可用）

## 回退到「无 MPV」
1. `apply.sh` 恢复调用 `inject_disable_mpv` + `inject_strip_mpv_assets`
2. 去掉 `inject_restore_mpv_phase_a` 调用
3. 使用干净上游树（无 `MPV_DISABLED` 残留）或接受 disable 脚本重新打补丁

## 验收
1. 新装默认 EXO 播放正常
2. 设置里能选 MPV 并起播
3. APK 含 mpv 原生库
4. 不要求本阶段原画截图/字体完美
