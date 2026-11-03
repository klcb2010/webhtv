# MPV-BRANCH-ONLY：字幕字体 + 颜色运行时刷新（覆盖包说明）

本目录下随包覆盖的文件（共 7 个，全部是**完整文件**，不是 diff）：

| 文件 | 类型 |
|---|---|
| `mod/apply.sh` | 修改（末尾追加 5 行 hook 注册，其余原样） |
| `mod/install_mpv_subtitle_runtime.sh` | 新增（755） |
| `mod/hooks/fix_mpv_subtitle_runtime.py` | 新增（755） |
| `mod/app/src/main/java/com/fongmi/android/tv/subtitle/SubtitleFontManager.java` | 修改（+`loadBuiltinFont()` / 自定义外部字体探测） |
| `mod/app/src/mobile/java/com/fongmi/android/tv/ui/activity/SettingPersonalActivity.java` | 修改（字幕字体循环点击 → 下拉单选） |
| `mod/app/src/main/res/values/arrays.xml` | 新增（字体显示名 / assets 文件名） |
| `mod/app/src/main/assets/fonts/README.txt` | 修改（目录约定说明） |

## 使用

```bash
# 1) 切 MPV 分支，确认干净
cd <webhtv> && git switch MPV && git status --short

# 2) 覆盖（把本压缩包解压到仓库根，选择"覆盖同名文件"）
#    解压后仓库根会出现 mod/ 目录的同名文件被覆盖，其余 mod 文件不受影响
bash mod/install_mpv_subtitle_runtime.sh    # 分支校验 + 注册 hook + 立即注入
chmod +x mod/install_mpv_subtitle_runtime.sh mod/hooks/fix_mpv_subtitle_runtime.py
bash mod/apply.sh
```

字体：把 `yiqicukai.ttf` / `huipianheyuan.ttf` / `fangzhengzhunyuan.ttf`（可选 `custom.ttf`）
放进 `mod/fonts/`，`apply.sh` 的 `sync_subtitle_fonts` 会同步到 `assets/fonts/`。

## 说明

- 覆盖包**只含 7 个变更文件**，不会删除或替换你 `mod/` 下其它 200+ 文件（含 `mod/fonts/*.ttf`）。
- 若想回退：`git checkout -- mod/apply.sh mod/app/src/main/java/com/fongmi/android/tv/subtitle/SubtitleFontManager.java mod/app/src/mobile/java/com/fongmi/android/tv/ui/activity/SettingPersonalActivity.java mod/app/src/main/assets/fonts/README.txt`
  然后 `rm -f mod/hooks/fix_mpv_subtitle_runtime.py mod/install_mpv_subtitle_runtime.sh mod/app/src/main/res/values/arrays.xml mod/README_MPV_SUB_FONT_RUNTIME.md`
- 日志：`MpvSubtitleStyle` / `MpvSubtitleBuiltin` → `应用cache/mod_debug/mod.log`（开关 `mod_debug_log`）。

## v3 新增（本轮）

1. **切换后强制重载字幕**：`modApplySubtitleStyleRuntime()` 末尾追加 `mpvCommand({"sub-reload"})`。
   mpv 改 `sub-font` / `sub-color` 后**不会自动重载当前字幕**，这是"播放中切字体没反应"的直接原因。
2. **file-loaded 后强制优先中文轨**：新增 `modForcePreferredSubtitleTrack()`，在 `file-loaded` 后 900ms
   按 `LangUtil` 打分选第一条简体中文字幕轨（`sid`），绕开 mpv "default 轨压过 slang" 的行为。
   尊重设置：`字幕偏好语言` 为英文 / 自动 时不干预，只在中文时执行。
3. **字体探针日志**：`MpvSubtitleStylePolicy.getSubFontProperty()` 输出
   `fontProbe idx= id= file= family=`，一眼看出内置字体文件是否取到、族名读成了什么。

## 排障：日志怎么开

`mod/app/src/main/java/com/fongmi/android/tv/utils/ModDebugLog.java`：
```java
public static boolean isEnabled() {
    if (true) return true;   // 临时诊断，测完删掉这行
```
日志：`/data/data/com.fongmi.android.tv/cache/mod_debug/mod.log`（算法助手可导出）。

关键行：
```
MpvSubtitleBuiltin: ok index=0 id=yiqicukai file=/data/.../files/subtitle_fonts/yiqicukai.ttf
MpvSubtitleStyle: fontProbe idx=0 id=yiqicukai file=... family=FZYaSong
MpvSubtitleStyle: runtime font=... mpvNativeReady=true / runtime result=true
MpvSubtitleStyle: forceCnTrack select id=2 note=chi|简体中文 score=400
```
