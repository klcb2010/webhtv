# 魔改日志策略

- **默认**：不写 logcat 刷屏（楷体 apply / preferChinese 例行信息已静默）。
- **需要排障时**：`Prefers.put("mod_debug_log", true)`，日志写入：
  - `应用缓存目录/mod_debug/mod.log`（约 2MB 滚动）
  - **清除缓存**会删掉该目录。
- 关闭：`Prefers.put("mod_debug_log", false)`。
- 系统 logcat / 其他 App 的 Log 无法整体搬家，只能管魔改自己的输出。
