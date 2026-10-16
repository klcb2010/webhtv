# 必须把楷体缩放接到 apply.sh

你当前 CI 日志显示：

- 有 `inject_subtitle_style done`（会写入 `reapplySubtitleStyle`）
- **没有** `inject_kaiti_scale`

因此运行时只有 `reapplySubtitleStyle ok`，没有 `scale=`。

## 改法

在 `mod/apply.sh` 里，**紧接** `inject_subtitle_style.py` 之后加入：

```bash
if [[ -f "$MOD/hooks/inject_kaiti_scale.py" ]]; then
  echo "[mod] inject_kaiti_scale"
  python3 "$MOD/hooks/inject_kaiti_scale.py" "$ROOT" || exit 1
fi
```

顺序不能反：先 subtitle_style 创建/改 reapply，再 kaiti_scale 往里打字号。

## 成功日志应出现

```
[mod] inject_kaiti_scale
[mod] replaced reapplySubtitleStyle method ...
[mod] VERIFY OK ...
[mod] inject_kaiti_scale SUCCESS
```

## 运行时 logcat

```
SubtitleStyle: applySubtitleViewTextSize scale=1.2 kaiti=true
SubtitleStyle: reapplySubtitleStyle ok scale=1.2 kaiti=true
```
