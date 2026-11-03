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

## v4 变更（本轮）

1. **日志默认开启**：`ModDebugLog.isEnabled()` 默认返回 true，日志直接写在
   `/data/data/com.fongmi.android.tv/cache/mod_debug/mod.log`（2MB 滚动）。关闭请调用 `setEnabled(false)`。
2. **字幕字体下拉只保留内置 4 项**（义启粗楷/毁片和圆/方正细圆/系统默认）；
   「自定义外部字体」不再出现在列表里，旧配置仍可正常回显与生效。
3. **sub-reload 改为条件触发**：仅当字体/颜色/样式参数真正变化且当前有字幕轨时才 `sub-reload`，
   修掉"拉进度条后暂停、不走进度"的副作用。
4. **sub-reload 后 400ms 重新校正中文轨**，避免重载把中文轨重置回 default 轨。
5. **每个播放器实例首次应用样式时校正一次轨道**（`sModLangApplied`），修 EXO↔MPV 切换回 MPV 后中文优先失效。

> 若"拉进度条暂停"仍复现，可临时把 `MpvPlayer` 里 `mpvCommand(new String[]{"sub-reload"})` 整段注释掉验证是否为本补丁引起。

## v5 根因修复（内置字体全部无效）

**根因**：`MpvSubtitleStylePolicy.getSubFontsDirProperty()` 写成了
```java
File dir = tryFontsDir();
if (dir == null) dir = ensureFontsReady();   // ← 目录永远存在（getFontsDir 会 mkdir），所以这句永不执行
```
于是 `ensureFontsReady()` 的"把选中字体复制成 `subfont.ttf`"从未生效，
`sub-fonts-dir` 里始终是**上一次**的字体（也就是旧的 `heiyuan.ttf`），
所以切义启粗楷/毁片和圆/方正细圆都没反应，看起来"只有外部字体有效"。

**修法**：
1. `getSubFontsDirProperty()` 改为**始终** `ensureFontsReady()`；
2. `ensureFontsReady()` 在 `src == null` 时增加 assets 兜底复制（`AssetManager.open("fonts/<id>.ttf")`）；
3. 复制结果打日志：`ensureFontsReady copy id=... src=... ok=true`。

**验证**：切任意内置字体后重进播放，日志应出现
```
MpvSubtitleStyle: ensureFontsReady copy id=fangzhengzhunyuan src=/data/.../files/subtitle_fonts/fangzhengzhunyuan.ttf ok=true
MpvSubtitleStyle: fontProbe idx=2 id=fangzhengzhunyuan file=... family=FZZhunYuan-M02S
MpvSubtitleStyle: sub-reload font=FZZhunYuan-M02S ...
```
（三个内置字体的真实 family 名：`iekie-Cukaiti` / `KTXP_ComRound` / `FZZhunYuan-M02S`）
