# 外挂字幕记忆（对齐 Silent SUB-EXT-HISTORY）

## 流程
1. 用户选外挂 → `PlayerManager.setSub` → `SubtitleRestoreCoordinator.onUserSetSub`
   - 文件复制到 `filesDir/sub_remember`
   - Prefers 存 JSON（historyKey + episodeUrl）
   - 同步 Assrt 文件缓存
2. 历史重进 → `VideoActivity.setPlayer`（**已烘焙进 mod 源**）
   - `prepareRestore` 登记 pending
   - `attachRememberedSub` 写入 `Result.subs`
   - `onPlayerReady` + 延迟 `selectPendingIfAny`
3. `PlayerManager.start` → `injectPendingIntoPlayerManager` 写入 `PlaySpec.subs`
4. 轨道就绪 → `onTracksChanged` → `AssrtSubtitleMatch.onTracksReady` → Media3 Override 强制选外挂

## 日志过滤
```bash
adb logcat -s AssrtSub:I SubRestore:I | tee sub.log
```
期望见到：`prepareRestore pending`、`injected into PlaySpec`、`forceSelect OK`、`attachRememberedSub`

## apply
`apply.sh` 会：复制 mod 源（含已烘焙的 VideoActivity）→ `inject_subtitle.py` → `inject_subtitle_restore.py`（挂 PlayerManager）
