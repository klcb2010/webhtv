# SHELL_JAR_SETTINGS_REDO v4

## 策略
- **线路排序 / 下载线程**：壳子原生 Dialog（蓝框焦点），读写 `playLineOrder` 等 Prefers
- **账号登录**：不拦截，仍用 jar UI（依赖 `Init.activityForDialog`）
- **禁止** 打开弹窗后立刻 `finish()` VideoActivity（会拆掉 Dialog，表现为空窗/和 jar 抢 UI）

## 回退
删除 hook + JarPanSettingsShell + 恢复上游 Init 后重建。
