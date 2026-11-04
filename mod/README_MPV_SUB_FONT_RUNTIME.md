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

## v47 变更（TV 端彻底去掉 MPV 截图项）

TV 端没有截图功能，所以截图相关的设置项也一并隐藏（保留节点，否则 ViewBinding 字段消失会编译不过）：

```java
playbackScreenshotBtn / mpvScreenshotBtn / mpvScreenshotBrightnessBoost → android:visibility="gone"
```

「MPV截图对比度」**只加在手机端**；TV 端不加这一项（也无任何 `mpvScreenshotContrast*` 引用）。

TV 个性设置现在剩下的与 MPV 相关的只有「MPV字体默认放大」。

| 项 | 手机 | TV |
|---|---|---|
| 播放器MPV截图 | ✅ | 隐藏 |
| MPV截图亮度 | ✅ | 隐藏 |
| MPV截图对比度 | ✅ | 无 |
| MPV字体默认放大 | ✅ | ✅ |

## v46 变更（新增「MPV截图对比度」，手机端）

个性设置里「MPV截图亮度」下面新增一项「**MPV截图对比度**」，点一下切下一个等级：

```
+5 → +10 → +15 → +20 → +25 → +30 → +35 → 关 → (回到 +5)
```

默认 **+15**。

### 设置层（`Setting.java`，两端共用）

```java
private static final int[] MPV_SCREENSHOT_CONTRAST_LEVELS = {5, 10, 15, 20, 25, 30, 35, 0};
getMpvScreenshotContrast()        // 默认 15
cycleMpvScreenshotContrast()
getMpvScreenshotContrastLabel()   // 0 → "关"，否则 "+15"
```

key：`mpv_screenshot_contrast`

### 生效方式（`PhoneScreenshot.applyContrastBoost`）

保存前对 Bitmap 做一次对比度拉伸（围绕 128 中点，避免整体变亮/变暗）：

```java
float c = 1f + (level / 100f);   // +15 → 1.15
float t = (1f - c) * 128f;
ColorMatrix{ c,0,0,0,t,  0,c,0,0,t,  0,0,c,0,t,  0,0,0,1,0 }
```

管线顺序：电平增强 → 轻度锐化 → **亮度** → **对比度** → 保存。
`+0（关）` 直接返回原图，不做任何拷贝。

### 入口

| 端 | 位置 |
|---|---|
| 手机 | `mod/app/src/mobile/res/layout/activity_setting_personal.xml`（亮度项之后） |
| TV | hook 注入 leanback 版 `activity_setting_personal.xml`（亮度项之后） |

两端都在 `initView()` 里初始化右侧值，点击时即时更新（只影响之后生成的截图，不改已保存的图）。

## v45 变更（回退 TV 端截图）

用户决定不在 TV 端做截图，本版把所有 TV 侧截图内容撤掉：

| 撤掉的东西 | 说明 |
|---|---|
| `mod/app/src/leanback/java/.../utils/PhoneScreenshot.java` | 删除（leanback 不再有截图类） |
| `mod/app/src/leanback/res/layout/view_control_vod_action.xml` | 不再交付（用仓库 mod 里那份，`@+id/screenshot` 仍是 `visibility="gone"`） |
| leanback `VideoActivity` 的截图注入 | 删除（`capturePlaybackScreenshot` / 监听 / `updateScreenshotAction`） |

保留的部分：

- **手机端**截图照旧：`PhoneScreenshot`（mobile 那份）+ `VideoActivity` 的按钮监听与显隐规则
  （只在「MPV + MPV截图未关」时显示）
- TV 个性设置里「**MPV字体默认放大**」照样加（用户要求）
- TV 个性设置「播放器MPV截图」右侧值的初始化修复（原来进页面是空的）

hook 改名为 `inject_tv_screenshot.py`（文件名没变，内容是「手机端截图 + TV 字体项」）。

## v44 变更（hook 锚点改按方法名定位 / TV 只加字体项 / 日志默认关）

### 1. 修 CI 报错 `anchor for applyActionButtonVisibility not found`

V43 用整段文本做锚点，但 `inject_player_buttons_fix.py` 会先改写
`applyActionButtonVisibility()`（在花括号后插入 OP/ED 隐藏逻辑），文本锚点因此失配。

V44 改成**按方法名定位、插到花括号内**：

```python
i = t.find("private void %s(" % name)
b = t.find("{", i)
t = t[:b+1] + "\n        updateScreenshotAction();" + t[b+1:]
```

依次尝试 `updateCustomButtonVisibility` → `applyActionButtonVisibility` →
`forceHideConfiguredActionButtons`，命中即止。

### 2. TV 个性设置只加「MPV字体默认放大」

TV 上不再加「播放器临时调试日志」—— TV 遥控器不好操作，日志也不好捞。

### 3. 日志默认关闭

`ModDebugLog`：

```java
public static boolean isRuntimeOverrideOn() { return runtimeOverride != null && runtimeOverride; }
public static boolean isEnabled() {
    if (!LOG_ACTIVE) return false;
    return runtimeOverride != null && runtimeOverride;   // 默认 null → false → 不打日志
}
```

- 默认：**不打日志**
- 手机端点「播放器临时调试日志」→ 打开（内存态，重启恢复关闭）
- TV 端没有开关，想抓 TV 日志就临时把 `LOG_ACTIVE` 改 false→true 出包
- `PhoneScreenshot.diag()` 同样跟随，默认静默

## v43 变更（截图按钮只在「MPV + 截图未关」时出现）

V42 只做到「EXO 点截图报不支持」，按钮仍然一直显示。V43 把显隐规则做成统一的一处：

```java
/** 截图按钮只在「MPV 播放 + MPV截图未关闭」时出现（EXO 不显示） */
private void updateScreenshotAction() {
    boolean show = player() != null && player().isMpv()
            && Setting.getMpvScreenshotMode() != Setting.SCREENSHOT_OFF;
    mBinding.control.action.screenshot.setVisibility(show ? View.VISIBLE : View.GONE);
}
```

挂载点：两端 `updateCustomButtonVisibility()` 的第一行（`updateCustomButtonStates();`）之后
—— 这个方法在控制栏 show/hide、切内核、切全屏时都会跑，所以状态永远同步。

| 端 | 文件 |
|---|---|
| TV | `app/src/leanback/java/.../ui/activity/VideoActivity.java` |
| 手机 | `app/src/mobile/java/.../ui/activity/VideoActivity.java` |

另外补了两端**原来完全没有**的点击处理（控制栏 `view_control_vod_action.xml` 里
`@+id/screenshot` 节点一直存在，但没有任何代码引用）：

```java
mBinding.control.action.screenshot.setOnClickListener(view -> capturePlaybackScreenshot());
```

以及两端各自新增 `public void capturePlaybackScreenshot()`
（TV 用 `getExoView()`，手机同样），成功后 Toast「截图已保存」。

底部控制面板（`dialog_control.xml`）的按钮注入已**停用** —— 截图入口只保留控制栏这一排，
避免两套入口状态不一致。

## v42 变更（双端彻底砍掉 EXO 截图）

用户要求：EXO 截图**两端都不要**，只保留 MPV 原画截图。

1. `PhoneScreenshot.capture()`（mobile + leanback 两份）的 EXO 分支现在是：

```java
// EXO：双端都不再支持截图（只保留 MPV 原画截图），直接返回失败提示。
main.post(() -> {
    if (callback != null) callback.onError("EXO 播放不支持截图（仅 MPV 支持）");
});
```

2. `saveImage()` 不再读 `Setting.getExoScreenshotMode()`（那个开关已无意义），
   只在 MPV 时取 `getMpvScreenshotMode()`，其余按 PNG。
3. 手机端个性设置里两个 EXO 截图项设成 `android:visibility="gone"`：
   `@+id/playbackScreenshotBtn`（截图格式）、`@+id/screenshotBrightnessBoost`（截图亮度）。
   TV 端这两项本来就是 gone / 不存在。
4. `Setting` 里的 `getExoScreenshotMode*` 方法保留不删（手机/TV 的
   `SettingPersonalActivity` 仍在读它做 `setText`，删了会编译不过），但已没有任何入口能改。
5. TV 控制栏的「截图」按钮仍显示（MPV 下正常出原画）；EXO 下点它会提示
   「EXO 播放不支持截图（仅 MPV 支持）」。

## v41 变更（TV 个性设置补两项）

TV 的个性设置是**独立的 leanback 版**（`mod/app/src/leanback/.../SettingPersonalActivity.java`
+ `activity_setting_personal.xml`），与手机端那两份是不同文件。之前只在手机端加了项，
所以 TV 上既没有「MPV字体默认放大」，也没有「播放器临时调试日志」。

v41 由 hook 补进 TV 那份（都加在列表末尾，顺序与手机端一致）：

```
... hdrToSdr (HDR转码为SDR)
 ├─ @+id/mpvFontScale    MPV字体默认放大    mpvFontScaleText
 └─ @+id/debugLog        播放器临时调试日志  debugLogText
```

- 布局项用 `@drawable/selector_item`、`focusableInTouchMode=true`，与相邻项一致（遥控器可聚焦）
- 点击处理：`setMpvFontScale()` 调 `Setting.cycleMpvFontScale()` +
  `MpvSubtitleStylePolicy.applyRuntimeToActive()` + `ExoUtil.reapplySubtitleStyle()`；
  `setDebugLog()` 调 `ModDebugLog.setRuntimeOverride()`（内存态，立即生效）
- 初值在 `initView()` 里补（`mpvFontScaleText` / `debugLogText` / `mpvScreenshotBtnText`）

顺便修掉 v39 漏的「播放器MPV截图」右侧空值：leanback 版只有点击时才 `setText`，
进页面从未初始化。

## v40 变更（TV 截图按钮真正生效 / 砍掉 EXO 原画截图）

### 1. TV 控制栏的截图按钮（v39 没显示出来的根因）

v39 把按钮加到了 `dialog_control.xml`（底部弹出的控制面板），但 TV 播放页的**控制栏**是
`@layout/view_control_vod` → `view_control_vod_action.xml` 那一排（next/prev/…/repeat）。

仓库 `mod/` 里那份 `view_control_vod_action.xml` **本来就有** `@+id/screenshot` 节点，
但被 `android:visibility="gone"` 显式隐藏，且 `VideoActivity` 里没有任何点击处理
—— 这是「砍掉 TV 截图」时留下的半截状态。

v40 覆盖包提供去掉 `gone` 的版本，并新增 hook 注入：

```java
mBinding.control.action.repeat.setOnClickListener(view -> onRepeat());
mBinding.control.action.screenshot.setOnClickListener(view -> capturePlaybackScreenshot());  // 新增
```

按钮文字用 `@string/play_screenshot`（main 的 strings_patch 已定义：Screenshot / 截图）。

### 2. TV 个性设置「播放器MPV截图」右侧值为空

leanback 版 `SettingPersonalActivity` 只写了 `setMpvScreenshotBtn()`（点击时刷新），
`initView()` 里**没有给 `mpvScreenshotBtnText` 赋初值**，所以进页面右侧是空的。
hook 补一行：

```java
try { mBinding.mpvScreenshotBtnText.setText(Setting.getMpvScreenshotModeLabel()); } catch (Throwable ignored) {}
```

### 3. 砍掉 EXO 截图相关

`PhoneScreenshot` 里 EXO 分支不再独立解码取原画，直接 `captureSurface()`：

```java
// EXO：直接截当前 Surface
captureSurface(activity, playerView, main, callback);
```

原来那套（`snapshotPlayer` / `decodeFromSnapshot` / `tryRetrieverUri` / `tryMediaCodecFrame` /
`isNearlyBlack` / `frameStats` / `Snapshot`）已从两份文件里删除。
代价：EXO 截图分辨率=屏幕；收益：不再出黑图、不再等几十秒。

## v39 变更（TV 端移植截图）

TV 端（leanback）原来没有截图入口。本版把手机端整套截图能力移植过去：

1. 新增 `mod/app/src/leanback/java/com/fongmi/android/tv/utils/PhoneScreenshot.java`
   —— 与 mobile 那份同名同实现（不同 source set，互不冲突）；
   `MpvScreenshot` 用 `app/src/main/.../utils/` 的共用版本，不重复放。
2. 新增 `mod/hooks/inject_tv_screenshot.py`，注入三处：
   - `app/src/leanback/res/layout/dialog_control.xml`
     → 控制面板按钮组（`panDiagnostic` 之后）加 `@+id/screenshotBtn`「截图」
   - `.../ui/dialog/ControlDialog.java`
     → `initEvent()` 加 `binding.screenshotBtn.setOnClickListener(v -> onScreenshot())`，
       新增 `onScreenshot()` 方法转调 VideoActivity
   - `.../ui/activity/VideoActivity.java`
     → `getExoView()` 之后新增 `public void capturePlaybackScreenshot()`，
       调 `PhoneScreenshot.capture(this, getExoView(), callback)`，Toast 提示结果
3. hook 幂等（重复跑不会重复插入），已用真实上游文件跑通并通过 XML 校验。

## v37 变更（黑帧判废 + 回退）

EXO 原画截图在 **HDR / 10bit 源**上会拿到「尺寸正确、内容全黑」的帧：
`getScaledFrameAtTime CLOSEST 3840x2160` 返回非 null，代码当成成功直接保存 → 得到黑图。

现在 `decodeFromSnapshot()` 对 Retriever / MediaCodec 两条路的结果都做黑帧判废
（采样 8×8，**最亮采样点亮度 < 24** 判黑 —— 用最大值而非平均值，暗场景不会被误杀）：
判黑就销毁换下一条路，两条都黑则返回 null
→ 自动回退 `captureSurface()`（屏幕分辨率，但颜色正常，已 tone-map）。

日志可区分：
```
retriever frame black 3840x2160 avg=9 max=17 BLACK, trying MediaCodec
codec frame black ..., fallback surface
savePng bitmap=1920x1080 stats=1920x1080 avg=118 max=255
```
物理限制：EXO 侧对 HDR 源无法同时拿到「原画分辨率 + 正确色彩」；
要 HDR 原画请用 MPV 播放（mpv 自带 tone-mapping，`screenshot-to-file video` 是真原画）。

## v36 变更（EXO 只认楷体档位 / 截图日志进文件）

1. **EXO 与 MPV 彻底解耦**：`getEffectiveSubtitleFontScale()` = `楷体系 ? 楷体档位 : 1.0`，
   **不吃**「MPV字体默认放大」倍率。EXO 只认楷体档位，MPV 才吃倍率（且不分字体）。
2. **截图诊断日志改为写 `ModDebugLog` 文件**（不再只进 logcat），并新增帧内容统计：
   `savePng bitmap=WxH stats=3840x2160 avg=... BLACK`。`BLACK` 表示解出来的就是黑帧
   （多为 HDR/10bit 源），据此判断是取帧失败还是回退 Surface。

## v33 变更（字号放大只对楷体生效 / 日志开关即时生效）

1. **回退 v30 的「EXO 也乘倍率」**：`Setting.getEffectiveSubtitleFontScale()` 恢复成
   `楷体系 ? 楷体档位 : 1.0`。v30 里乘了「MPV字体默认放大」，结果非楷体字也被整体放大
   1.15 倍、字号调到最小仍然偏大一档。
2. **mpv 侧保持全局放大**：新增 `Setting.getMpvSubFontSizePx()` = `round(55 × 倍率)`，
   不分字体，mpv 下所有字幕都放大（这是 MPV 分支新增的逻辑）。
   同时**不再覆盖 `sub-scale`**：该属性由上游按用户字号设置，覆盖会让字号调节失灵。
3. **调试日志项改名「播放器临时调试日志」**，点一下立即生效
   （`ModDebugLog.setRuntimeOverride`，内存态，不落盘）。`PhoneScreenshot` 的 DIAG
   也跟随这个开关，关掉后截图路径不再打日志。

## v30 变更（EXO 侧也用同一倍率）

「MPV字体默认放大」原来只作用于 mpv。EXO 的字幕字号走 media3 `SubtitleView` 的
**fractional text size**，由 `Setting.getEffectiveSubtitleFontScale()` 决定，之前只对楷体系生效。

改法：`= (楷体系 ? 楷体档位 : 1.0) × getMpvFontScaleFactor()`

- 非楷体字 + 1.15x → 1.15（EXO 画面里字幕同样变大）
- 楷体系 + 1.15x → 楷体档位 × 1.15（两个设置各自独立，会叠加）
- 切换倍率时同时调 `ExoUtil.reapplySubtitleStyle()`，EXO 正在播也立即生效

## v30 变更（EXO 原画截图 / 日志默认开 / 修一个空指针）

1. **EXO 截图改成原画，实现直接对齐 main 分支**。`PhoneScreenshot` 的 EXO 分支原来直接
   `captureSurface()`（= 屏幕分辨率，降级）。现在与 main 分支的截图入口完全一致：
   `snapshotPlayer()` 取 uri/位置/尺寸 → 独立线程（`THREAD_PRIORITY_URGENT_DISPLAY`）
   → `decodeFromSnapshot()` 串行 Retriever→Codec（Codec 软解，避免与播放中的硬解并行
   导致 CRASH_NATIVE）→ 失败才回退 Surface。上限仍是 3840（超过 4K 才缩）。
   MPV 分支仍保留 mpv 的 `screenshot-to-file video` 原画路径。
2. **日志默认开启**（`LOG_ACTIVE = true`），并在个性设置**最后**加了一项
   「调试日志（临时，重启恢复）」，点一下切换本次运行是否打日志，不落盘。
   与系统自带的调试开关不冲突（个性设置里原本没有类似项）。
3. **修 `SubtitleMatch` 的空指针刷屏**：切换到 EXO 的瞬间 `engine` 为 null，
   而字幕匹配的重试（0/400/1200/3000/8000ms）正好撞上 → 连续
   `Attempt to invoke interface method ... on a null object reference`。
   给 `PlayerManager.getCurrentTracks()` 加了空保护，返回 `Tracks.EMPTY`。

## v29 变更（修「MPV字体默认放大」不生效）

**根因**：`sub-ass-override` 被强制成 `force`。这个模式下 mpv **忽略 ASS 文件里的样式，
改用 `--sub-*` 系列选项**，字幕字号来自 `--sub-font-size`（默认 55）；
`--sub-scale` 对 ASS 不生效（只在 `scale` 模式下才作用于 ASS）。
V26/V27 设的是 `sub-scale`，所以界面上改了倍率但画面没变化。

**修法**：按倍率换算字号

```java
float f = Setting.getMpvFontScaleFactor();
modSetRuntimeProperty("sub-scale", String.valueOf(f));                      // 非 ASS 字幕
modSetRuntimeProperty("sub-font-size", String.valueOf(Math.round(55f * f)));// 55 → 63 / 66 / 72
```

三处都设：初始化选项、`modApplySubtitleStyleRuntime`、`modForcePreferredSubtitleTrack`（就绪兜底）。

另外修了一个幂等标记冲突：`patch_mpv_sub_scale` 原先用 `MPV_SUB_SCALE_V1` 做判重，
而 `modForcePreferredSubtitleTrack` 里也有同名标记，导致整段注入被误判为「已注入」而跳过。

## v28 变更（起播阶段点击倍率无效）
## v26 变更（新增「MPV字体默认放大」）

个性设置**最后一项**：`MPV字体默认放大`，点一下切下一个倍率。

| 项 | 值 |
|---|---|
| 倍率 | 1.00x / 1.15x / 1.20x / 1.30x |
| 默认 | **1.15x** |
| 作用范围 | mpv 下所有字幕（ASS / 内封 / 外挂），不影响 EXO |
| 生效方式 | 立即生效（播放中切换也立刻变） |

**实现**：用 mpv 原生 `sub-scale` 属性（渲染层全局缩放），只加一个属性，不动任何现有逻辑。

- 存储：`Setting.getMpvFontScaleFactor()` / `cycleMpvFontScale()`，键 `mpv_font_scale_index`
- 运行时：`modApplySubtitleStyleRuntime()` 开头设 `sub-scale`
- 初始化：`modSetInitOption("sub-scale", ...)`，保证首帧就是目标倍率（不会先 1.0 再跳）
- 点击：`MpvSubtitleStylePolicy.applyRuntimeToActive()` 立即刷新
- **切换倍率同时触发 `sub-reload`**：`MPV_SUB_RELOAD_V1` 的判定条件里加上了 scale，
  所以改倍率会连字幕一起重载（400ms 后自动恢复原 sid），ASS 字幕也会立刻重绘。
- **起播阶段点击也能生效**：`modApplySubtitleStyleRuntime` 在 `!modIsNativeReady()` 时会直接返回，
  这时点倍率不会有任何效果（真机日志实测）。为此：
  1. 设置页点击时若返回 `false`，延迟 1.5s / 4s 各重试一次；
  2. `modForcePreferredSubtitleTrack()` 在 native 就绪后也会补设 `sub-scale`（并在倍率变化时 sub-reload）。

涉及文件：
```
mod/app/src/main/java/com/fongmi/android/tv/setting/Setting.java            （新增 4 个方法 + 数组）
mod/app/src/mobile/res/layout/activity_setting_personal.xml                 （末尾加一项 mpvFontScale）
mod/app/src/mobile/java/com/fongmi/android/tv/ui/activity/SettingPersonalActivity.java
mod/hooks/fix_mpv_subtitle_runtime.py                                       （patch_mpv_sub_scale）
```

注意：`Setting.java` 与布局都是**整文件覆盖**，基于仓库 `mod/` 里现有版本改的。

## v25 变更（检查更新：只在同分支 release 里找最新）

**问题**：`releases/latest` 是「仓库最新那个 release」。main 一同步上游就先发版，
latest 就变成 main 的包，里面没有 `-mpv` 资产 → MPV 版检测必然失败。
这跟补丁无关，是「用发布时间判断最新版」这个前提本身错了。

**改法**（`MPV_SUB_UPD_SELFBASE_V1`）：本版是 MPV 版，就只在带 `-mpv` 的 release 里找最新的。

```
1) releases/latest/download/<name>-mpv.json        ← 快，latest 恰好是 mpv 时一次命中
2) 失败 → releases.atom（github.com 域，约 20KB）
   → 按时间倒序的 tag 列表 → 挑第一个含 -mpv 的 tag
   → releases/download/<该 tag>/<name>-mpv.json   ← 命中
3) 两条都没拿到 manifest → 明确标记失败（不会假装「已是最新」）
```

为什么用 `releases.atom` 而不是 `api.github.com`：atom 在 **github.com 主域**，
和下载 apk 同一个域（你那边确定能通）；`api.github.com` 在大陆经常超时。
两个 URL 都会走「用户加速源 → 直连 → 内置镜像」的路由。

## v22 变更（检查更新：真正的原因）

**之前的方向全错了**：`app/.../Updater.java` 早就被 `mod/app/.../Updater.java`（仓库自带的定制版）
覆盖，我对 `app/` 的所有注入都不会生效——这也是真机上一行 `Updater` 日志都没有的原因。

仓库的 `mod` 版 Updater **不走 `api.github.com`**，而是直接下 release 资产：

```java
String direct = Github.getJson(getName());
// → https://github.com/klcb2010/webhtv/releases/latest/download/leanback-arm64_v8a.json
```

而 MPV 分支的资产名带后缀：`leanback-arm64_v8a-mpv.json`。
→ 按原名请求 **404**，加速镜像全部 404 → `lastError` → 提示「检查更新失败」。
主分支能成功，正因为它的资产名不带后缀。

**V22 改动**（直接改 `mod/app/.../Updater.java`）：
1. `Github.getJson(modMarkedName(getName()))` —— 请求名补上 `-mpv` 后缀（已带则不重复加）
2. `getAssetName()` 同样补后缀，保证默认 apk 名正确
3. 加诊断日志：`check start name=.. direct=..` / `try <url>` / `ok name=..` / `fail url=.. err=..`
4. 超时放宽：单请求 4s→8s，总限 10s→16s

**注意**：这份 `Updater.java` 会覆盖仓库 `mod/` 里的同名文件。如果之后上游改了 `mod/.../Updater.java`，
需要把新版本拿来重新打一遍这几个改动（改点都标了 `MPV_SUB_UPD_ASSET_V1`）。

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
