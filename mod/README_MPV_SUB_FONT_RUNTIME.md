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

## v6 根因修复（用你提供的日志定位）

日志证据：
```
ensureFontsReady copy id=yiqicukai src=.../yiqicukai.ttf ok=false
fontProbe idx=0 id=yiqicukai file=.../yiqicukai.ttf(0B)      family=null
fontProbe idx=0 id=yiqicukai file=.../yiqicukai.ttf(4387988B) family=null
```

两个独立 bug：

1. **`family=null`（文件 4.4MB 完整也读不到族名）**
   `readFontFamilyName()` 里 `readFully(fontFile, 4 * 1024 * 1024)` 只读前 4MB，
   而 `yiqicukai.ttf`=4.39MB、`huipianheyuan.ttf`=5.12MB 的 `name` 表在 4MB 之后
   → 永远读不到 → `family=null` → 回退成 `sub-font=yiqicukai`（不存在的族名）→ 字体不生效。
   **修**：上限提到 32MB。

2. **`ok=false` 且文件大小在 0B / 完整之间跳变（并发写）**
   `loadBuiltinFont`（后台线程池）与 `MpvSubtitleStylePolicy.ensureFontsReady` 同时复制同一个 ttf，
   互相读到写了一半的 0B 文件 → `forceCopy` 校验失败（ok=false）、族名解析失败。
   **修**：新增 `SubtitleFontManager.FONT_IO_LOCK`，`ensureFontFile` 与 `ensureFontsReady` 共用同一把锁串行化。

顺带修：
3. **切字体后字幕变英文**：`sub-reload` 会把 `sid` 重置为默认轨。现在 reload 前记录原 `sid`，
   400ms 后**还原原 sid** 再做中文优先校正。
4. **seek 期间不 reload**：读取 mpv `seeking` 属性，为 `yes` 时跳过（拖进度不再受影响）。
5. **内封轨没 lang 时选不到中文**：语言/标题里出现 `chi/zho/简/中文` 也按中文计分（不再只认标准语言码）。
6. **字体选择改回"点一下换一个"**：去掉弹窗，循环 义启粗楷→毁片和圆→方正细圆→系统默认。

## v7 根因修复（字体永远 0B 的真凶）

v6 日志：
```
ensureFontsReady copy id=huipianheyuan src=.../huipianheyuan.ttf ok=false
fontProbe ... file=...huipianheyuan.ttf(0B)     family=null
fontProbe ... file=...huipianheyuan.ttf(5122272B) family=KTXP_ComRound
```

**根因：`forceCopy(src, named)` 里 src 与 named 是同一个文件**
- `named = new File(dir, id + ".ttf")`，而 `src = SubtitleFontManager.getFontFile(idx)` 返回的正是 `dir/id.ttf` → 同一个文件；
- `forceCopy` 内部先 `new FileInputStream(src)` 再 `new FileOutputStream(dst, false)`，后者**把文件截断成 0B**，
  于是读到空内容、写出 0B、`contentMatches` 返回 false（`ok=false`）；
- 下一轮 `ensureFontFile` 发现文件 <1000B，又从 assets 重新复制 5MB → 再被截断 → 永远在 0B ↔ 5MB 之间跳变，
  所以字体**永远不生效**（`family` 时有时无）。

**修法**：`forceCopy` 开头判断 `dst.getCanonicalPath().equals(src.getCanonicalPath())` → 直接 `return true`，绝不自我截断。
另加：`getSubFontProperty()` 读到文件 <1000B 时最多重试 3×40ms（首轮 assets 复制期间的半成品）。

## v8 新增

1. **新内置字体「筑紫A圆D」**（`zhuziAyuanD.ttf`，index=5，排在 custom 之后，index 0~4 语义不变）
   - `mod/apply.sh` 的 `sync_subtitle_fonts` 已加入 `zhuziAyuanD`
   - 个性设置点击顺序：义启粗楷 → 毁片和圆 → 方正细圆 → 系统默认 → 筑紫A圆D（跳过「自定义」）
   - `arrays.xml` 三个数组同步增加第 6 项
2. **EXO 也简体优先**：`PlayerManager` 在 `onTracksChanged` 首次恢复轨后调用
   `modPreferSimplifiedChineseSubtitle(tracks)`，按 `language` + `label`（简/繁/CHS/CHT/Hans/Hant/GB/Big5）
   重新打分并 `setTrackSelectionParameters` 覆盖。原因：Media3 对没有 language 的字幕轨一律当 `und`，
   会直接选第一条（很多片源第一条是繁体）。
3. **MPV 选轨诊断**：`forceCnTrack` 找不到中文轨时，日志会 dump 前 10 条字幕轨的
   `id / lang / title / external`，用于判断片源到底有没有语言标签。

## v9 根因修复（MPV 选不到简体/切回变英文）

**根因**：外挂字幕走的是 `MpvPlayer.addSubtitleConfigurations()`：
```java
mpvCommand(new String[]{"sub-add", playableUri(uri), "auto"});   // 只传了 url + auto
```
mpv 的 `sub-add` 语法是 `sub-add <url> [<flags> [<title> [<lang>]]]` ——
**language / label 从来没传给 mpv**，所以 `track-list/N/lang` 全是空，
`slang` 与 `forceCnTrack` 怎么匹配都命中不了（日志 `count=35 ... no zh track` 就是这个原因）。

而 app 自己的字幕列表能显示"中文，Simplified"，是因为那是 Media3 `SubtitleConfiguration.label/language`，
不是 mpv 的轨道属性。

**修法**：`sub-add` 时把 `sub.label` / `sub.language` 作为 title / lang 传入：
```java
mpvCommand(new String[]{"sub-add", playableUri(uri), "auto", modSubLabel, modSubLang});
```
之后 mpv 的 track-list 就有 `lang=zh-Hans` / `title=中文，Simplified`，
`slang` 与 `forceCnTrack` 都能正常工作，`forceCnTrack` 的 dump 也能看到真实标签。

## v10 根因修复（明明有 zh-Hans 却 skip）

v9 日志已经证明 sub-add 透传生效了：
```
subs= [id=2 lang=zh-Hant title=Traditional] [id=3 lang=zh-Hans title=Simplified] ...
forceCnTrack skip: no zh track
```

**根因：分数域不匹配**。`mod/hooks/inject_prefer_text_lang.py` 把 `LangUtil.getPreferredTextLanguageScore`
改写成了 **0~100 分制**（PREFER_HANS_SCORE：简体=100 / zh=80 / 繁体=40），
而 v8/v9 的判断写的是 `>= 200`（MPV）与 `>= 300`（EXO）——**永远不成立**，所以两个播放器都走 skip。

**修法**：阈值统一改成 **80**（≥80 视为中文，简体 100 仍优先），宽松兜底分数改成 95 / 40。
兼容原版 400/200/100 分制（400 ≥ 80 同样成立）。

## v11 变更（不再依赖 LangUtil 的分制）

上一版把阈值改成 80，日志仍 `skip: no zh track`。v11 直接**自实现打分**（`modSubtitleLangScore`，0~100），
完全绕开 `LangUtil.getPreferredTextLanguageScore`（它被 inject_prefer_text_lang.py 改成 100/80/40 分制）：

| 输入 | 分数 |
|---|---|
| `zh-Hans` / `zh-CN` / `chs` / `Simplified` / 含「简」 | 100 |
| `zh` 或其它 `zh-*` / `chi` / `zho` / `Chinese` | 80 |
| `zh-Hant` / `zh-TW` / `zh-HK` / `cht` / `Traditional` / 含「繁」 | 40 |
| 其它 | 0 |

阈值 = 80（简体 100 优先于 zh 80 与繁体 40）。

日志带版本号，可直接确认装的是哪一版：
```
forceCnTrack(v11) select id=3 note=zh-Hans|Simplified score=100 prevSid=1
forceCnTrack(v11) skip: no zh track count=35 best=0 prevSid=1 subs=[...]
exoForceCn(v11) select index=2 score=100 label=中文，Simplified，SRT
```
看到 `(v11)` 才是新包；仍打 `forceCnTrack skip:`（无版本号）说明包没换成功。
