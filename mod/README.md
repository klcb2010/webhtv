# MOD_FAST_KERNEL_SWITCH_V1 — 播放器内核秒切

## 改动说明
- 仅 MPV 分支
- `VideoActivity.refreshAndSwitchPlayerKernel`：
  - 非解析源 + 已有有效播放 URL → 直接 return false，走本地 `player().switchPlayer(type)`（秒切）
  - 解析源仍走原来的 `SiteApi.playerContent` 重取链，功能不变

## 文件清单
- `app/src/mobile/.../VideoActivity.java` （已打补丁）
- `app/src/leanback/.../VideoActivity.java` （已打补丁）
- `hooks/inject_fast_kernel_switch.py` （幂等注入脚本）
- `apply.sh` （已加入对上述 hook 的调用）

## 使用
直接把本包 `mod/` 目录内容覆盖到仓库的 `mod/` 即可，然后照常跑你的 apply-mod-and-build。
无需再手工改任何东西。
