# HDR转码为SDR（阶段 A）

## 行为
- 个性设置最后一项：**HDR转码为SDR**，默认**关闭**
- 关闭：与现网完全一致，不调用 `setRequestOpenGlToneMapping`
- 开启：EXO 在创建 `PlaybackVideoGraphWrapper` 时请求 Media3 OpenGL HDR→SDR tone mapping（真映射，非改标记）
- 无 LUT 时会挂一个近恒等 `ScaleAndRotateTransformation` 以拉起 VideoGraph 路径

## 回退（推荐顺序）
1. 设置里保持关闭（立即恢复观感）
2. 完整回退代码：从 `apply.sh` 删除 `inject_hdr_to_sdr` 段
3. 删除或还原：
   - `hooks/inject_hdr_to_sdr.py`
   - `app/.../HdrToSdrHelper.java`
   - `app/.../ExoRuntimeAwareVideoRenderer.java`（本 MOD 覆盖版）
   - Setting / 布局 / SettingPersonal 中的 hdrToSdr 相关项（可选，留开关无害）

## 限制
- API 29+ OpenGL 路径；DV 不保证
- 弱机/开隧道/部分路径可能无法走 VideoGraph，效果有限
- 开启后需**重新起播**（重建播放器）更稳妥
