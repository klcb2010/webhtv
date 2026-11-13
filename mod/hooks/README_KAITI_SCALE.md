# 楷体自动放大

## CI
在 `inject_disable_mpv.py` / 字体相关 apply 之后执行：

```bash
python3 mod/hooks/inject_kaiti_scale.py .
```

## 行为
- 仅当前字体为楷体系（义启粗楷 / KaiTi / 楷体等）时使用 1.00/1.15/1.20/1.30
- 圆体、系统默认倍率恒为 1.0
- EXO：`SubtitleView.setFractionalTextSize(0.0533f * scale)`，每次从基准重算不叠加
