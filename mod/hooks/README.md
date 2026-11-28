# 禁用 MPV v2

## 必须在 CI/apply 里真正执行

```bash
python3 mod/hooks/inject_disable_mpv.py "$ROOT"
```

日志应出现：
- `[mod] patched .../PlayerSetting.java`
- `[mod] patched .../PlayerManager.java`
- `[mod] patched UI .../SettingPlayerFragment.java` 或 `SettingPlayerActivity.java`

若全部是 `skip missing` / `no change`，说明路径不对或补丁未进包。

## 覆盖范围

1. KERNEL_ORDER 去掉 MPV → 设置/播放页 PlayerKernelDialog 列表无 MPV
2. putPlayer / getPlayer / putActivePlayer 拒绝 MPV
3. buildEngine 强制 MPV→EXO
4. setMpvRows 永久隐藏 MPV 配置/渲染/蓝光菜单

## 自检

安装后：
- 设置 → 播放器内核：只有 EXO / IJK / 系统（IJK 不可用则更少）
- 无「MPV 配置」「MPV 渲染」行
- 播放页换内核弹窗同样无 MPV
