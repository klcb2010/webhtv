# MPV 阶段 2：原生库瘦身

## 做什么
- 删除 `app/src/arm64_v8a/assets/mpv-libs`、`app/src/armeabi_v7a/assets/mpv-libs`（约 20 个 so，双 ABI）
- 将 `scripts/verify_mpv_native_assets.sh` 改为直接成功（避免 CI 因缺 so 失败）

## 不做什么
- **不删** Java（`MpvPlayerEngine`、`player/mpv/**`、`androidx.media3.mpvplayer/**`）
  - `PlayerManager` 等仍有大量类型引用，删类会编译失败
  - 阶段 1 已保证不会创建 MPV 引擎，Java 留着不进运行路径，几乎不占 APK（dex 相对 so 很小）

## CI
`Apply mod` → `Disable MPV` → **`Strip MPV native assets`** → 后续 Gradle  
原有的 `Verify bundled MPV native assets` 步骤可保留（脚本已 stub），或删掉该 step。

## 预期
单 ABI APK 可减少数十 MB（视 libmpv/ffmpeg 体积而定）。
