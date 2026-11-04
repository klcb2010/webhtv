# MOD_FAST_KERNEL_SWITCH_V1 完整包

## 用法（覆盖复制，不用手改）

```bash
# 仓库根目录
unzip -o mod-mpv-fast-kernel-switch-v4-20261104.zip
# 合并覆盖（不要先删整个 mod，否则其它 hooks 会丢）
cp -a mod/. 你的仓库路径/mod/
# 照常跑 apply-mod-and-build
```

## 包含
- 已打补丁的 TV / 手机 VideoActivity
- hooks/inject_fast_kernel_switch.py
- 已加入调用的 apply.sh

非解析源切换内核秒切；解析源逻辑不变。
