# SHELL_JAR_SETTINGS_REDO v21

## TV only (`Util.isLeanback()` / Leanback)

- **线路排序 / 下载线程**：壳子 Dialog（蓝框）
- **账号登录**：壳子一级网盘列表 + 二级登录方式 → `JarLoginHostActivity` 透明宿主上触发 jar 登录（避免弹回综合设置）

## 手机

不拦截。

## 回退账号登录

去掉 `isShellPanel` 中 loginHub 判断，并删除 `JarLoginHostActivity` 注册即可。
