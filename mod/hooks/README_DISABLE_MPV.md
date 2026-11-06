# 禁用 / 移除 MPV（阶段说明）

## 阶段 1（本 hook，已交付）

`inject_disable_mpv.py` 在源码树根目录执行：

```bash
python3 mod/hooks/inject_disable_mpv.py "$ROOT"
```

效果：

1. **PlayerSetting**
   - `KERNEL_ORDER` 去掉 MPV（循环内核不再出现）
   - `isPlayer()` 不再认 MPV
   - `getPlayer()` 若读到历史 MPV 偏好，写回并返回 EXO
2. **PlayerManager.buildEngine**
   - 任何 `type == MPV` 在进 switch 前改成 EXO
   - switch 里 MPV 分支也改为 `ExoPlayerEngine`（双保险）
3. **设置页**（若存在）MPV 相关行强制 `GONE`

运行时不会再创建 `MpvPlayerEngine`，旧用户选过 MPV 也会落到 EXO。

**不删** native so / 类文件，避免大范围编译依赖断裂；包体仍偏大，见阶段 2。

## 阶段 2（包体瘦身，需在完整鱼壳树操作）

确认阶段 1 稳定后：

```bash
# 示例：从打包资源中拿掉 MPV 原生库（路径以仓库为准）
rm -rf app/src/main/assets/mpv-libs
rm -rf app/src/mobile/assets/mpv-libs
rm -rf app/src/leanback/assets/mpv-libs
# 以及 gradle 中对 mpv / libplayer 的 packaging 配置
```

再择机删除：

- `player/engine/MpvPlayerEngine.java`
- `player/mpv/**`
- `androidx.media3.mpvplayer` / `is.xyz.mpv` 依赖与 JNI
- 设置字符串、诊断面板中的 MPV 文案

## 回归建议

- 设置里内核循环：仅 EXO / IJK / 系统（IJK 不可用时更少）
- 旧安装「曾选 MPV」冷启动播放 → 应为 EXO
- 起播失败回退不应再进 MPV
- 字幕样式、音轨仍走 EXO 路径
