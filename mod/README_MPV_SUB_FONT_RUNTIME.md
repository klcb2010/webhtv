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

## v20 变更（日志体验：实时可见 + 降噪）

1. **`ModDebugLog` 同步投一份到 logcat**：`Log.i/Log.w` 与文件写入同时进行，
   文件查看器不刷新时可以直接 `adb logcat -s MpvSubtitleStyle Updater`（或算法助手）实时抓。
2. **每次写入后 `flush()` + `getFD().sync()`**：保证用文件管理器打开就能看到刚写的行，
   不用等进程退出。
3. **`fontProbe` 去重**：同一字体 key（id + family + 文件长度）只打印一次。
   之前每次应用字幕样式都打一行，日志里一半是它。

## v19 变更（修纯 MPV 起播仍是英文 + 检查更新改成主分支路径）

1. **轨道表就绪后自动校正**。原来只靠 `file-loaded` 后固定 900ms / 2600ms 两次校正，
   而纯起播时字幕轨（内封/外挂）常常在 2.6s 之后才进 `track-list` → 两次都 skip，
   于是停在 mpv 默认的英文轨。现在挂在 `refreshTracks()` 确认 `track-list/count > 0`
   之后，延迟 600ms 校正一次（节流 3 秒），起播/换集/切解码都能兜住。
2. **外挂字幕 `sub-add` 之后再校正一次**（700ms），覆盖「字幕比视频晚挂上」的情况。
3. **检查更新改回主分支的路径**：先请求 `/releases/latest`（响应几十 KB，秒回），
   只有当 latest 被 main 分支抢走（tag 不含 `-mpv`）时才退回 `/releases` 列表过滤。
   原实现一律拉列表（20 个 release + 全部 assets），在大陆网络下必然超时 → 提示「检查更新失败」。

## v18 变更（按真机日志定案）

日志证据：`exoForceCn(v14) select index=0 score=80 label=Traditional`

1. **打分顺序修正：label/title 优先，language 兜底**。
   原来先按 `language` 打分，而字幕的 language 常常只是泛 `"zh"`（我们的表给 80 分），
   于是标着 `Traditional` 的轨也拿 80 分，遍历时先命中谁就选谁（通常第一条是繁体）。
   现在先看 label/title（`Simplified`=100 / `Traditional`=40），没有再回落到 language。
2. **EXO 侧加防抖**：同一目标轨 1 秒内只设一次参数。日志里 1 分钟内反复
   `select` 同一轨几十次，说明设置被回滚后又被新的 `onTracksChanged` 重新触发。
3. **MPV 侧改用「实际生效」的轨判断**：原来读 `sid`（请求值），重新加载后可能和画面上
   不一致 → 误判成 `keep`（日志 `keep id=3` 但画面是英文）。现在读
   `current-tracks/sub/id`，空时再回落 `sid`。
   并且 `keep` 分支也会 `setInitialSubtitleTrackId()`，避免下次重新加载丢预选。
4. **检查更新失败的两个真实原因**：
   - `/releases?per_page=20` 响应体远大于 `/releases/latest`，而超时只有
     `GITHUB_REQUEST_TIMEOUT_MS=4s`、总限 `UPDATE_CHECK_TIMEOUT_MS=10s` → 超时 →
     `hasErrorOnly()` → 提示「检查更新失败」。现改为 `per_page=3`，并把超时放宽到 8s / 16s。
   - 「自动检测更新」开关关闭时 `Updater.start()` 直接 return，不会报错也不会检查——
     需要菜单里的「检查更新」走 `force()`。

## v17 变更（修 MPV 侧简中完全不生效 + EXO 切解码回落）

1. **MPV 侧不再把「自动」当成"不干预"**。原来门控是
   `if (p.startsWith("en") || "auto".equals(p) || p.isEmpty()) return;`，
   而字幕偏好默认就是 auto → 整条规则**从未执行**，MPV 起播直接是 mpv 自选的英文轨。
   现在只有「明确选了非中文语言」才跳过，auto / 空 / 中文都继续强制简中。
2. **EXO 侧的简体校核移到每次 `onTracksChanged`**（原来只在
   `if (!tracks.isEmpty() && !initTrack)` 首次块内）。切解码时 `initTrack` 可能已为 true，
   整块不执行 → EXO 回落到默认轨（表现为 MPV→EXO 变繁体）。同时加了
   「已经是这条轨就不再设参数」的幂等判断，避免重复触发回调。
3. **更新检查加诊断日志**：每次检查会输出
   `Updater: stable start channel=.. releases=.. manifest=.. mark=-mpv`
   和每个 release 的 `tag=.. mark=.. beta=.. asset=..`，
   失败时区分是「接口异常 / 没有 -mpv 的 release / asset 名没命中」。

## v15 变更（修复「检查更新失败」）

**根因**：本分支 release 的 asset 名是 `leanback-arm64_v8a-mpv.json`，而 `Updater.getManifestName()`
拼出来的是 `leanback-arm64_v8a.json`（`getName()` = `FLAVOR_mode + "-" + FLAVOR_abi`，不含后缀），
原 `findAsset()` 用 `equals` 精确比对 → 永远返回 null → `Update.empty()` → UI 提示「检查更新失败」。

**修复**：`findAsset()` 改为两级匹配
1. 先按原名精确匹配（main 分支行为不变）；
2. 再按「前缀相同 + 扩展名相同」兜底，并排除 `-beta`（除非本次就是要 beta 通道）。

配合 v13 的 tag 过滤，stable/beta 两条通道都能正确命中 `*-mpv` 的 release。

## v14 变更（字幕轨改走「手动选轨」链路）

1. **MPV 侧不再只发裸命令**：`forceCnTrack` 选中后改为
   `setInitialSubtitleTrackId(sid)` + `setTrackSelection(C.TRACK_TYPE_TEXT, sid)`，
   与用户手动点选字幕等价（`MpvPlayerEngine.setTrack` 走的就是这两步）。
   区别在于会记住 `initialSubtitleTrackId`，下次 `startPreparedMedia()` 前自动预选 sid，
   不会被 mpv 的默认轨覆盖 —— 这是「切回 MPV 变英文」的根因。
2. **EXO 侧与 `TrackUtil.setTrackSelection` 对齐**：补
   `setExceedRendererCapabilitiesIfNecessary(true)`，防止字幕轨被 renderer 能力过滤掉。
3. **两侧各加延迟重试**：MPV 在 `file-loaded` 后 900ms / 2600ms 各校正一次；
   EXO 在 `onTracksChanged` 后立即 + 1500ms + 4000ms 各校正一次（取最新 tracks）。
   覆盖「外挂字幕晚于首个回调才挂上」的情况。
4. 日志版本号升到 `(v14)`：
   `forceCnTrack(v14) select ... via=manual-equivalent` / `exoForceCn(v14) select ...`

## v13 变更

`Updater` 注入改为按方法边界整体替换（不依赖方法内部文本，前面的 hook 改过也不影响），
并且该注入失败只打 WARN、不再让 `apply.sh` 退出。

## v12 变更

1. **检查更新只看本分支的 release**：`mod/.../utils/Github.java` 新增 `RELEASE_TAG_MARK = "-mpv"`，
   `Updater` 的 stable/beta 分支都改成遍历 `releases` 并过滤 `tag_name` 含 `-mpv`。
   原因：`/releases/latest` 返回的是仓库里最新的 release（可能是 main 分支的包），MPV 版会误报更新。
   main 分支的代码没有这个过滤（hook 只在 MPV 分支运行），两边各查各的。
2. 字幕轨选择维持 v11 的静默打分（含「中文」/`Simplified` → 优先，含「繁」/`Traditional` → 降级）。

## GitHub Actions 默认分支（"Use workflow from"）

代码改不了这个下拉：它显示的是**仓库默认分支**。在 GitHub 网页设置一次即可：
`Settings → General → Default branch → 切换到 MPV → Update`
之后 Actions 的 "Use workflow from" 默认就是 MPV，schedule 也跑 MPV 分支的 workflow。
