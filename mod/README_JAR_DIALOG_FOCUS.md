# JAR_DIALOG_FOCUS_FIX

## 作用
修复 TV 端综合设置里 jar 弹窗（线路排序 / 下载线程等）遥控器焦点异常。

壳子侧：`Init` 原先只用 Application Context；弹窗需要 Activity Context。

## 回退
1. 删除 `mod/hooks/inject_jar_dialog_focus.py`
2. 从 `mod/apply.sh` 去掉 `inject_jar_dialog_focus` 段（若仍在）
3. 重新跑 Actions 构建

代码标记：`JAR_DIALOG_FOCUS_FIX`

## 成功标准
- 线路排序能打开，上下有选中，可调序
- 下载线程上下能在选项间移动并 OK
- 网盘开关 / 画质 / 账号登录不回归
