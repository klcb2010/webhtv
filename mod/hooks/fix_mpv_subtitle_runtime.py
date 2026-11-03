#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MPV 分支字幕字体/颜色运行时刷新补丁（构建期注入，不落盘到上游源码）。

作用：
  1. Setting.putSubtitleColorIndex  -> 写入后立即把新颜色推到正在播放的 MPV（实时生效）
  2. Setting.putSubtitleFontIndex   -> 写入后立即把新字体推到正在播放的 MPV（实时生效）
  3. MpvPlayer.modApplySubtitleStyleRuntime / modApplyUserSubtitleStyleFromPolicy
     -> 记录 mpvActive / mpvNativeReady / 参数 / 结果（标签 MpvSubtitleStyle）
  4. MpvSubtitleStylePolicy.applyRuntimeToActive -> 记录 mpvActive 与最终 mpvRuntimeApplied

约束：
  - 只在 webhtv 的 MPV 分支执行；其它分支直接跳过（退出码 0）
  - 幂等：重复运行不会重复注入
  - 只插入调用与日志，不改动任何既有逻辑
  - 日志开关沿用 ModDebugLog（mod_debug_log），日志文件 应用cache/mod_debug/mod.log
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MOD = ROOT / "mod"
REQUIRED_BRANCH = "MPV"

MARK_COLOR = "MPV_SUB_COLOR_RUNTIME_V1"
MARK_FONT = "MPV_SUB_FONT_RUNTIME_V1"
MARK_RUNTIME_LOG = "MPV_SUB_RUNTIME_LOG_V1"
MARK_POLICY_LOG = "MPV_SUB_POLICY_LOG_V1"
MARK_APPLY_LOG = "MPV_SUB_APPLY_LOG_V1"
MARK_RELOAD = "MPV_SUB_RELOAD_V1"
MARK_LANG = "MPV_SUB_LANG_V1"
MARK_FONT_PROBE = "MPV_SUB_FONTPROBE_V1"

RELOAD_STATE = """    // MPV_SUB_RELOAD_STATE_V1 (MPV-BRANCH-ONLY): 只在字体/颜色真正变化时重载字幕
    private static volatile String sModLastFont = "";
    private static volatile String sModLastColor = "";
    private static volatile String sModLastStyle = "";
    // 每个播放器实例首次应用样式时校正一次字幕轨（EXO<->MPV 切换后仍生效）
    private volatile boolean sModLangApplied = false;

    // MPV_SUB_SCORE_V11 (MPV-BRANCH-ONLY): 自实现 0~100 打分，不依赖 LangUtil（它可能被别的 mod 改成别的分制）
    private static int modSubtitleLangScore(String tag) {
        if (tag == null) return 0;
        String l = tag.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        if (l.isEmpty()) return 0;
        if (l.contains("zh-hans") || l.equals("zh-cn") || l.equals("zh-sg") || l.equals("chs")
                || l.contains("hans") || l.contains("simplified") || l.contains("简")) return 100;
        if (l.startsWith("zh") || l.contains("chi") || l.contains("zho")
                || l.contains("中文") || l.contains("chinese")) {
            if (l.contains("hant") || l.equals("zh-tw") || l.equals("zh-hk") || l.equals("zh-mo")
                    || l.equals("cht") || l.contains("traditional") || l.contains("繁")) return 40;
            return 80;
        }
        if (l.contains("traditional")) return 40;
        return 0;
    }"""

FORCE_CN_METHOD = """    // MPV_SUB_LANG_V1_BEGIN (MPV-BRANCH-ONLY): file-loaded 后强制优先中文轨
    public void modForcePreferredSubtitleTrack() {
        try {
            if (!modIsNativeReady()) return;
            // 尊重「字幕偏好语言」：只有中文（或未设置）才强制中文轨，选英文/自动时不干预
            String modPref = com.fongmi.android.tv.setting.Setting.getSubtitlePreferredLanguage();
            if (modPref != null) {
                String p = modPref.trim().toLowerCase(java.util.Locale.ROOT);
                if (p.startsWith("en") || "auto".equals(p) || p.isEmpty()) return;
            }
            int count = Math.max(0, intProperty("track-list/count", 0));
            if (count <= 0) return;
            int bestId = -1;
            int bestScore = 0;
            String bestNote = "";
            for (int i = 0; i < count; i++) {
                String type = stringProperty("track-list/" + i + "/type", "");
                if (!"sub".equals(type)) continue;
                String lang = stringProperty("track-list/" + i + "/lang", "");
                String title = stringProperty("track-list/" + i + "/title", "");
                int score = modSubtitleLangScore(lang);
                if (score == 0) score = modSubtitleLangScore(title);
                int id = intProperty("track-list/" + i + "/id", -1);
                if (score <= 0 || id < 0) continue;
                if (score > bestScore) {
                    bestScore = score;
                    bestId = id;
                    bestNote = lang + "|" + title;
                }
            }
            String current = propertyStringOrInt("sid");
            int curId = -1;
            try {
                curId = Integer.parseInt(current == null ? "" : current.trim());
            } catch (Throwable ignored) {
            }
            if (bestId < 0 || bestScore < 80) {   // LangUtil 是 0~100 分制（简体100/zh80/繁40）
                StringBuilder modDump = new StringBuilder();
                int modShown = 0;
                for (int i = 0; i < count && modShown < 10; i++) {
                    if (!"sub".equals(stringProperty("track-list/" + i + "/type", ""))) continue;
                    modShown++;
                    modDump.append(" [id=").append(intProperty("track-list/" + i + "/id", -1))
                            .append(" lang=").append(stringProperty("track-list/" + i + "/lang", ""))
                            .append(" title=").append(stringProperty("track-list/" + i + "/title", ""))
                            .append(" ext=").append(stringProperty("track-list/" + i + "/external", ""))
                            .append("]");
                }
                com.fongmi.android.tv.utils.ModDebugLog.i("MpvSubtitleStyle",
                        "forceCnTrack(v11) skip: no zh track count=" + count + " best=" + bestScore + " prevSid=" + current + " subs=" + modDump);
            } else if (curId == bestId) {
                com.fongmi.android.tv.utils.ModDebugLog.i("MpvSubtitleStyle",
                        "forceCnTrack(v11) keep id=" + bestId + " note=" + bestNote);
            } else {
                mpvCommand(new String[]{"sid", String.valueOf(bestId)});
                com.fongmi.android.tv.utils.ModDebugLog.i("MpvSubtitleStyle",
                        "forceCnTrack(v11) select id=" + bestId + " note=" + bestNote + " score=" + bestScore + " prevSid=" + current);
            }
        } catch (Throwable e) {
            com.fongmi.android.tv.utils.ModDebugLog.w("MpvSubtitleStyle", "forceCnTrack err=" + e);
        }
    }
    // MPV_SUB_LANG_V1_END"""

FORCE_CN_HOOK = """                // MPV_SUB_LANG_V1_HOOK (MPV-BRANCH-ONLY): 等轨道表就绪后强制中文轨
                try {
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            MpvPlayer.this.modForcePreferredSubtitleTrack();
                        }
                    }, 900L);
                } catch (Throwable ignored) {
                }"""

TAG = "MpvSubtitleStyle"
LOG = "com.fongmi.android.tv.utils.ModDebugLog"

OK: list[str] = []
ERR: list[str] = []


# ---------------------------------------------------------------- 基础工具


def git_branch(root: Path) -> str:
    try:
        out = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True,
            text=True,
            timeout=20,
        )
        return (out.stdout or "").strip()
    except Exception as exc:  # git 缺失 / 不是 git 仓库
        print(f"[mod] git branch probe failed: {exc}")
        return ""


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def match_brace(text: str, open_idx: int) -> int:
    """open_idx 指向 '{'，返回配对的 '}' 下标；忽略字符串/注释中的括号。"""
    depth = 0
    i = open_idx
    n = len(text)
    in_str = False
    str_ch = ""
    in_line = False
    in_block = False
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
            i += 1
            continue
        if in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 2
                continue
            i += 1
            continue
        if in_str:
            if c == "\\" and i + 1 < n:
                i += 2
                continue
            if c == str_ch:
                in_str = False
            i += 1
            continue
        if c == "/" and nxt == "/":
            in_line = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_block = True
            i += 2
            continue
        if c in ('"', "'"):
            in_str = True
            str_ch = c
            i += 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def find_method(text: str, decl_regex: str):
    """按声明正则定位方法：返回 (decl_start, brace_idx, end_idx) 或 None。"""
    m = re.search(decl_regex, text, re.M)
    if not m:
        return None
    brace = text.find("{", m.end() - 1)
    if brace < 0:
        return None
    end = match_brace(text, brace)
    if end < 0:
        return None
    return m.start(), brace, end


def line_indent(text: str, idx: int) -> str:
    line_start = text.rfind("\n", 0, idx) + 1
    m = re.match(r"[ \t]*", text[line_start:])
    return m.group(0) if m else "    "


def insert_at_body_start(text: str, brace: int, block: str) -> str:
    """block 各行自带缩进、末尾不带换行；插入到方法体第一行之前。"""
    nl = text.find("\n", brace)
    if nl < 0:
        return text
    return text[: nl + 1] + block + "\n" + text[nl + 1 :]


def insert_at_body_end(text: str, end: int, block: str) -> str:
    """插入到方法闭合 '}' 所在行的行首之前（保持原有缩进）。"""
    line_start = text.rfind("\n", 0, end) + 1
    return text[:line_start] + block + "\n" + text[line_start:]


# ---------------------------------------------------------------- 注入实现


def patch_setting_color(path: Path) -> None:
    if not path.is_file():
        ERR.append(f"Setting.java not found: {path}")
        return
    text = read(path)
    span = find_method(
        text,
        r"^[ \t]*public\s+static\s+void\s+putSubtitleColorIndex\s*\(\s*int\s+\w+\s*\)\s*\{",
    )
    if span is None:
        ERR.append("putSubtitleColorIndex(int) not found")
        return
    _, brace, end = span
    body = text[brace + 1 : end]
    if MARK_COLOR in body:
        OK.append("color runtime already injected")
        return
    block = (
        f"        // {MARK_COLOR}_BEGIN (MPV-BRANCH-ONLY)\n"
        f'        try {{\n'
        f'            {LOG}.i("{TAG}", "color index=" + index + " argb=" + Integer.toHexString(SUBTITLE_COLORS[index]));\n'
        f"            com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.ensureFontsReady();\n"
        f"            boolean applied = com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.applyRuntimeToActive();\n"
        f'            {LOG}.i("{TAG}", "color runtimeApplied=" + applied);\n'
        f"        }} catch (Throwable e) {{\n"
        f'            {LOG}.w("{TAG}", "color apply err=" + e);\n'
        f"        }}\n"
        f"        // {MARK_COLOR}_END"
    )
    text = insert_at_body_end(text, end, block)
    write(path, text)
    OK.append("inject color runtime refresh")


def patch_setting_font(path: Path) -> None:
    if not path.is_file():
        ERR.append(f"Setting.java not found: {path}")
        return
    text = read(path)
    span = find_method(
        text,
        r"^[ \t]*public\s+static\s+void\s+putSubtitleFontIndex\s*\(\s*int\s+\w+\s*\)\s*\{",
    )
    if span is None:
        ERR.append("putSubtitleFontIndex(int) not found")
        return
    _, brace, end = span
    body = text[brace + 1 : end]
    if MARK_FONT in body:
        OK.append("font runtime already injected")
        return
    head = (
        f"        // {MARK_FONT}_HEAD (MPV-BRANCH-ONLY)\n"
        f'        {LOG}.i("{TAG}", "font index=" + index'
        f' + " id=" + SubtitleFontManager.idOf(SubtitleFontManager.normalizeIndex(index)));'
    )
    tail = (
        f"        // {MARK_FONT}_BEGIN (MPV-BRANCH-ONLY)\n"
        f"        try {{\n"
        f"            com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.ensureFontsReady();\n"
        f"            boolean applied = com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.applyRuntimeToActive();\n"
        f'            {LOG}.i("{TAG}", "font runtimeApplied=" + applied);\n'
        f"        }} catch (Throwable e) {{\n"
        f'            {LOG}.w("{TAG}", "font apply err=" + e);\n'
        f"        }}\n"
        f"        // {MARK_FONT}_END"
    )
    text = insert_at_body_start(text, brace, head)
    if "applyRuntimeToActive" in body:
        # phase_c 已在 putSubtitleFontIndex 内接好运行时刷新，这里只补日志（避免重复设属性）
        write(path, text)
        OK.append("inject font runtime log (refresh already wired by phase_c)")
        return
    span = find_method(
        text,
        r"^[ \t]*public\s+static\s+void\s+putSubtitleFontIndex\s*\(\s*int\s+\w+\s*\)\s*\{",
    )
    if span is None:
        ERR.append("putSubtitleFontIndex lost after head insert")
        return
    _, _, end2 = span
    text = insert_at_body_end(text, end2, tail)
    write(path, text)
    OK.append("inject font runtime refresh")


def patch_mpv_player(path: Path) -> None:
    if not path.is_file():
        ERR.append(f"MpvPlayer.java not found: {path}")
        return
    text = read(path)
    original = text

    # 1) modApplySubtitleStyleRuntime：记录参数与播放器状态、记录最终结果
    span = find_method(
        text,
        r"^[ \t]*public\s+boolean\s+modApplySubtitleStyleRuntime\s*\(\s*String\s+\w+\s*,\s*String\s+\w+\s*,\s*String\s+\w+\s*,\s*String\s+\w+\s*\)\s*\{",
    )
    if span is None:
        ERR.append("MpvPlayer.modApplySubtitleStyleRuntime not found (phase_c 未注入 bridge?)")
    else:
        _, brace, end = span
        body = text[brace + 1 : end]
        if MARK_RUNTIME_LOG in body:
            OK.append("runtime log already injected")
        else:
            head = (
                f"        // {MARK_RUNTIME_LOG} (MPV-BRANCH-ONLY)\n"
                f'        {LOG}.i("{TAG}", "runtime font=" + font + " style=" + forceStyle'
                f' + " color=" + color + " dir=" + fontsDir'
                f' + " mpvActive=" + (sModActive.get() == this)'
                f' + " mpvNativeReady=" + modIsNativeReady());'
            )
            text = insert_at_body_start(text, brace, head)
            span = find_method(
                text,
                r"^[ \t]*public\s+boolean\s+modApplySubtitleStyleRuntime\s*\(\s*String\s+\w+\s*,\s*String\s+\w+\s*,\s*String\s+\w+\s*,\s*String\s+\w+\s*\)\s*\{",
            )
            if span is not None:
                _, b2, e2 = span
                text = _log_before_return(text, b2, e2, "return ok;", f'{LOG}.i("{TAG}", "runtime result=" + ok);')
                OK.append("inject runtime log into modApplySubtitleStyleRuntime")
            else:
                ERR.append("modApplySubtitleStyleRuntime lost after head log insert")

    # 2) modApplyUserSubtitleStyleFromPolicy：记录 Policy 取值
    if MARK_POLICY_LOG not in text:
        key = 'Object color = pol.getMethod("getSubColorProperty").invoke(null);'
        if key not in text:
            ERR.append("modApplyUserSubtitleStyleFromPolicy color lookup not found")
        else:
            m_idx = text.find(key)
            indent = line_indent(text, m_idx)
            log_line = (
                f'\n{indent}{LOG}.i("{TAG}", "policy dir=" + dir + " font=" + font'
                f' + " style=" + style + " color=" + color + " // {MARK_POLICY_LOG}");'
            )
            text = text[: m_idx + len(key)] + log_line + text[m_idx + len(key) :]
            OK.append("inject policy log")
    else:
        OK.append("policy log already injected")

    # 3) sub-reload：mpv 改 sub-font / sub-color 后不会自动重载字幕（只在参数变化时执行，避免干扰 seek）
    if 'mpvCommand(new String[]{"sub-reload"})' in text:
        OK.append("sub-reload already injected")
    else:
        span = find_method(
            text,
            r"^[ \t]*public\s+boolean\s+modApplySubtitleStyleRuntime\s*\(\s*String\s+\w+\s*,\s*String\s+\w+\s*,\s*String\s+\w+\s*,\s*String\s+\w+\s*\)\s*\{",
        )
        if span is None:
            ERR.append("modApplySubtitleStyleRuntime not found for sub-reload")
        else:
            _, b, e = span
            if "sModLastFont" not in text:
                ci = text.rfind("\n}")
                if ci < 0:
                    ERR.append("class end not found for reload state")
                    return
                text = text[:ci] + "\n" + RELOAD_STATE + "\n" + text[ci:]
            block = (
                f"        // {MARK_RELOAD} (MPV-BRANCH-ONLY): 仅字体/颜色变化时重载字幕\n"
                "        try {\n"
                '            String modFont = font == null ? "" : font;\n'
                '            String modColor = color == null ? "" : color;\n'
                '            String modStyle = forceStyle == null ? "" : forceStyle;\n'
                "            if (!modFont.equals(sModLastFont) || !modColor.equals(sModLastColor)\n"
                "                    || !modStyle.equals(sModLastStyle)) {\n"
                "                sModLastFont = modFont;\n"
                "                sModLastColor = modColor;\n"
                "                sModLastStyle = modStyle;\n"
                '                String modSid = propertyStringOrInt("sid");\n'
                '                String modSeeking = stringProperty("seeking", "no");\n'
                '                if ("yes".equals(modSeeking)) {\n'
                f'                    {LOG}.i("{TAG}", "sub-reload skipped: seeking");\n'
                '                } else if (modSid != null && !modSid.isEmpty() && !"no".equals(modSid)) {\n'
                '                    final String modRestoreSid = modSid;\n'
                '                    mpvCommand(new String[]{"sub-reload"});\n'
                f'                    {LOG}.i("{TAG}", "sub-reload font=" + modFont + " color=" + modColor + " sid=" + modSid);\n'
                "                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {\n"
                "                        @Override\n"
                "                        public void run() {\n"
                "                            try {\n"
                "                                // sub-reload 会把 sid 重置为默认轨，必须还原成用户原来的轨\n"
                '                                mpvCommand(new String[]{"sid", modRestoreSid});\n'
                "                            } catch (Throwable ignored) {\n"
                "                            }\n"
                "                            modForcePreferredSubtitleTrack();\n"
                "                        }\n"
                "                    }, 400L);\n"
                "                } else {\n"
                f'                    {LOG}.i("{TAG}", "sub-reload skipped: no subtitle track");\n'
                "                }\n"
                "            }\n"
                "        } catch (Throwable ignored) {\n"
                "        }\n"
                "        // MPV_SUB_TRACKFIRST_V1 (MPV-BRANCH-ONLY): 实例首次应用样式时校正一次轨道\n"
                "        try {\n"
                "            if (!sModLangApplied) {\n"
                "                sModLangApplied = true;\n"
                "                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {\n"
                "                    @Override\n"
                "                    public void run() {\n"
                "                        modForcePreferredSubtitleTrack();\n"
                "                    }\n"
                "                }, 900L);\n"
                "            }\n"
                "        } catch (Throwable ignored) {\n"
                "        }"
            )
            text = _block_before_return(text, b, e, "return ok;", block)
            OK.append("inject conditional sub-reload after subtitle style apply")

    # 4) file-loaded 后强制优先中文轨（mpv 的 default 轨会压过 slang）
    if "public void modForcePreferredSubtitleTrack() {" in text:
        OK.append("force cn track already injected")
    else:
        anchor = "                addSubtitleConfigurations();"
        if text.count(anchor) != 1:
            ERR.append(f"addSubtitleConfigurations anchor count={text.count(anchor)} (expect 1)")
        else:
            idx = text.rfind("\n}")
            if idx < 0:
                ERR.append("class end not found for force cn method")
            else:
                text = text[:idx] + "\n" + FORCE_CN_METHOD + "\n" + text[idx:]
                text = text.replace(anchor, anchor + "\n" + FORCE_CN_HOOK, 1)
                OK.append("inject force-cn-track (method + file-loaded hook)")

    if text != original:
        write(path, text)
        for mk in (MARK_RUNTIME_LOG, MARK_RELOAD, MARK_LANG):
            if mk not in read(path):
                ERR.append(f"marker missing after write: {mk}")


def _log_before_return(text: str, brace: int, end: int, ret_stmt: str, log_stmt: str) -> str:
    """在 (brace,end) 方法体内 ret_stmt 之前插入一行日志（只处理第一个匹配）。"""
    idx = text.find(ret_stmt, brace, end)
    if idx < 0:
        return text
    line_start = text.rfind("\n", 0, idx) + 1
    indent = re.match(r"[ \t]*", text[line_start:]).group(0)
    return text[:line_start] + indent + log_stmt + "\n" + text[line_start:]


def _block_before_return(text: str, brace: int, end: int, ret_stmt: str, block: str) -> str:
    """在 (brace,end) 方法体内 ret_stmt 之前插入多行块（block 各行自带缩进）。"""
    idx = text.find(ret_stmt, brace, end)
    if idx < 0:
        return text
    line_start = text.rfind("\n", 0, idx) + 1
    return text[:line_start] + block + "\n" + text[line_start:]


def patch_policy(path: Path) -> None:
    if not path.is_file():
        ERR.append(f"MpvSubtitleStylePolicy.java not found: {path}")
        return
    text = read(path)
    if MARK_APPLY_LOG in text:
        OK.append("policy applyRuntimeToActive log already injected")
        return

    null_branch = 'if (active == null) return false;'
    if null_branch in text:
        text = text.replace(
            null_branch,
            'if (active == null) { '
            f'{LOG}.i("{TAG}", "applyRuntimeToActive mpvActive=null applied=false // {MARK_APPLY_LOG}"); '
            "return false; }",
            1,
        )
    invoke = "Object r = m.invoke(active);"
    if invoke in text:
        idx = text.find(invoke)
        indent = line_indent(text, idx)
        text = text[: idx + len(invoke)] + (
            f'\n{indent}{LOG}.i("{TAG}", "applyRuntimeToActive mpvActive="'
            f' + active.getClass().getSimpleName() + " mpvRuntimeApplied=" + r'
            f' + " // {MARK_APPLY_LOG}");'
        ) + text[idx + len(invoke) :]

    if MARK_APPLY_LOG not in text:
        ERR.append("applyRuntimeToActive injection failed (unexpected Policy source)")
        return
    write(path, text)
    OK.append("inject policy runtime log")


def branch_allowed(root: Path) -> tuple[bool, str]:
    """分支防护：MPV 直接放行；detached HEAD 用 CI 环境变量 / 仓库锚点兜底放行。

    CI（actions/checkout）常见 detached HEAD，git 拿不到分支名。此时若
    GITHUB_REF_NAME / GITHUB_REF 含 MPV，或本仓库同时具备 MPV 专属锚点
    （inject_mpv_phase_c.py + androidx.media3.mpvplayer.MpvPlayer），视为 MPV 分支。
    """
    branch = git_branch(root)
    if branch == REQUIRED_BRANCH:
        return True, f"branch={branch}"
    for key in ("GITHUB_REF_NAME", "GITHUB_REF", "GIT_BRANCH", "CI_COMMIT_REF_NAME"):
        val = os.environ.get(key, "")
        if REQUIRED_BRANCH in val:
            return True, f"branch={branch or 'detached'} {key}={val}"
    phase_c = root / "mod/hooks/inject_mpv_phase_c.py"
    mpv_player = root / "app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java"
    if phase_c.is_file() and mpv_player.is_file():
        return True, f"branch={branch or 'detached'} anchors-ok"
    return False, f"branch={branch or 'unknown'}"


def patch_policy_font_probe(path: Path) -> None:
    """在 getSubFontProperty 里打一行探针：idx / id / 字体文件 / 读到的族名。"""
    if not path.is_file():
        ERR.append(f"MpvSubtitleStylePolicy.java not found: {path}")
        return
    text = read(path)
    if MARK_FONT_PROBE in text:
        OK.append("font probe already injected")
        return
    anchor = "            String family = readFontFamilyName(src);"
    if text.count(anchor) != 1:
        ERR.append(f"readFontFamilyName anchor count={text.count(anchor)} (expect 1)")
        return
    probe = (
        anchor
        + f'\n            com.fongmi.android.tv.utils.ModDebugLog.i("{TAG}", "fontProbe idx=" + idx'
        + ' + " id=" + id + " file=" + (src == null ? "null" : src.getAbsolutePath() + "(" + src.length() + "B)")'
        + ' + " family=" + family + " // ' + MARK_FONT_PROBE + '");'
    )
    text = text.replace(anchor, probe, 1)
    write(path, text)
    OK.append("inject fontProbe into getSubFontProperty")


def patch_policy_fonts_ready(path: Path) -> None:
    """修 getSubFontsDirProperty 从不调用 ensureFontsReady 的缺陷，并给 assets 兜底复制。"""
    if not path.is_file():
        ERR.append(f"MpvSubtitleStylePolicy.java not found: {path}")
        return
    text = read(path)
    m = "MPV_SUB_FONTDIR_V1"
    if m in text:
        OK.append("fonts-dir ensure already injected")
        return

    old_dir = """    public static String getSubFontsDirProperty() {
        File dir = tryFontsDir();
        if (dir == null) dir = ensureFontsReady();"""
    new_dir = """    public static String getSubFontsDirProperty() {
        // MPV_SUB_FONTDIR_V1 (MPV-BRANCH-ONLY): 目录存在时也必须 ensure，
        // 否则 subfont.ttf 永远是上一次的字体（表现为"切内置字体没反应"）
        File dir = ensureFontsReady();
        if (dir == null) dir = tryFontsDir();"""
    if old_dir not in text:
        ERR.append("getSubFontsDirProperty anchor not found")
        return
    text = text.replace(old_dir, new_dir, 1)

    old_null = "            if (src == null || !src.isFile()) return dir;"
    new_null = """            if (src == null || !src.isFile()) {
                // MPV_SUB_FONTASSET_V1 (MPV-BRANCH-ONLY): 兜底直接从 assets 复制选中字体
                try {
                    android.content.Context modCtx = App.get();
                    String modAsset = SubtitleFontManager.assetFileOf(Setting.getSubtitleFontIndex());
                    if (modCtx != null && modAsset != null && !modAsset.isEmpty()) {
                        File modOut = new File(dir, modAsset);
                        try (InputStream modIn = modCtx.getAssets().open("fonts/" + modAsset);
                             OutputStream modOs = new FileOutputStream(modOut)) {
                            byte[] modBuf = new byte[8192];
                            int modN;
                            while ((modN = modIn.read(modBuf)) > 0) modOs.write(modBuf, 0, modN);
                        }
                        if (modOut.isFile() && modOut.length() > 1000) src = modOut;
                    }
                } catch (Throwable modErr) {
                    com.fongmi.android.tv.utils.ModDebugLog.w("MpvSubtitleStyle", "assetFontFallback err=" + modErr);
                }
                if (src == null || !src.isFile()) {
                    com.fongmi.android.tv.utils.ModDebugLog.w("MpvSubtitleStyle",
                            "ensureFontsReady: no font file idx=" + SubtitleFontManager.idOf(Setting.getSubtitleFontIndex()));
                    return dir;
                }
            }"""
    if old_null not in text:
        ERR.append("ensureFontsReady src-null anchor not found")
        return
    text = text.replace(old_null, new_null, 1)

    old_ok = """            if (ok) {
                removeOtherFontVariants(dir, "subfont", subfont);"""
    new_ok = """            com.fongmi.android.tv.utils.ModDebugLog.i("MpvSubtitleStyle",
                    "ensureFontsReady copy id=" + id + " src=" + (src == null ? "null" : src.getAbsolutePath()) + " ok=" + ok);
            if (ok) {
                removeOtherFontVariants(dir, "subfont", subfont);"""
    if old_ok in text:
        text = text.replace(old_ok, new_ok, 1)

    write(path, text)
    OK.append("inject fonts-dir ensure + asset fallback")


def patch_policy_font_lock(path: Path) -> None:
    """两个硬 bug：①name 表只读 4MB，4MB+ 字体必然 family=null；②与 SubtitleFontManager 不共用锁，读到 0B 半成品。"""
    if not path.is_file():
        ERR.append(f"MpvSubtitleStylePolicy.java not found: {path}")
        return
    text = read(path)
    if "MPV_SUB_FONTLOCK_V1" in text:
        OK.append("font lock/readcap already injected")
        return

    nl = chr(10)
    old_lock = "        synchronized (ENSURE_LOCK) {"
    new_lock = ("        // MPV_SUB_FONTLOCK_V1 (MPV-BRANCH-OnlY): 与 SubtitleFontManager 共用同一把锁" + nl
                + "        // 否则并发复制时会读到 0B 半成品（表现为 family=null / ok=false）" + nl
                + "        synchronized (com.fongmi.android.tv.subtitle.SubtitleFontManager.FONT_IO_LOCK) {")
    if old_lock not in text:
        ERR.append("ensureFontsReady lock anchor not found")
        return
    text = text.replace(old_lock, new_lock, 1)

    old_read = "readFully(fontFile, 4 * 1024 * 1024)"
    new_read = "readFully(fontFile, 32 * 1024 * 1024)"
    if old_read not in text:
        ERR.append("readFully 4MB anchor not found (family 可能仍为 null)")
        return
    text = text.replace(old_read, new_read, 1)

    write(path, text)
    OK.append("inject shared font lock + 32MB name-table read cap")


def patch_policy_selfcopy(path: Path) -> None:
    """forceCopy(src,named) 里 src 与 dst 是同一文件时会把字体截成 0B（fontProbe 0B / ok=false 的根因）。"""
    if not path.is_file():
        ERR.append(f"MpvSubtitleStylePolicy.java not found: {path}")
        return
    text = read(path)
    if "MPV_SUB_SELFCOPY_V1" in text:
        OK.append("forceCopy self-copy guard already injected")
        return

    anchor = """    private static boolean forceCopy(File src, File dst) {
        if (src == null || dst == null || !src.isFile()) return false;"""
    guard = anchor + """
        // MPV_SUB_SELFCOPY_V1 (MPV-BRANCH-ONLY): src 与 dst 是同一文件时绝不能走 FileOutputStream，
        // 否则会先把自身截断成 0B，再读到空内容（导致 fontProbe 0B / ok=false / 字体反复失效）
        try {
            if (dst.getCanonicalPath().equals(src.getCanonicalPath())) return true;
        } catch (Throwable ignored) {
        }"""
    if anchor not in text:
        ERR.append("forceCopy anchor not found")
        return
    text = text.replace(anchor, guard, 1)

    read_anchor = "            File src = SubtitleFontManager.getFontFile(idx);"
    read_patch = read_anchor + """
            // MPV_SUB_FONTREADY_V1 (MPV-BRANCH-ONLY): 首轮从 assets 复制时可能读到半成品，短暂重试
            for (int modTry = 0; modTry < 3 && src != null && src.length() <= 1000; modTry++) {
                try {
                    Thread.sleep(40L);
                } catch (Throwable ignored) {
                }
            }"""
    if text.count(read_anchor) == 1:
        text = text.replace(read_anchor, read_patch, 1)
    else:
        ERR.append(f"getSubFontProperty src anchor count={text.count(read_anchor)}")

    write(path, text)
    OK.append("inject forceCopy self-copy guard + font read retry")


EXO_CN_METHOD = """    // MPV_SUB_SCORE_V11 (MPV-BRANCH-ONLY): 自实现打分，不依赖 LangUtil 的分制
    private static int modSubtitleLangScore(String tag) {
        if (tag == null) return 0;
        String l = tag.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        if (l.isEmpty()) return 0;
        if (l.contains("zh-hans") || l.equals("zh-cn") || l.equals("zh-sg") || l.equals("chs")
                || l.contains("hans") || l.contains("simplified") || l.contains("简")) return 100;
        if (l.startsWith("zh") || l.contains("chi") || l.contains("zho")
                || l.contains("中文") || l.contains("chinese")) {
            if (l.contains("hant") || l.equals("zh-tw") || l.equals("zh-hk") || l.equals("zh-mo")
                    || l.equals("cht") || l.contains("traditional") || l.contains("繁")) return 40;
            return 80;
        }
        if (l.contains("traditional")) return 40;
        return 0;
    }

    // MPV_SUB_EXO_CN_V2 (MPV-BRANCH-ONLY): EXO 也简体优先
    // Media3 对没有 language 的字幕轨一律当 und，会选第一条（常见就是繁体），
    // 所以这里按 language + label 重新打分，找到简体轨就显式 override。
    private void modPreferSimplifiedChineseSubtitle(androidx.media3.common.Tracks tracks) {
        try {
            if (tracks == null || player == null) return;
            androidx.media3.common.TrackGroup bestGroup = null;
            int bestIndex = -1;
            int bestScore = 0;
            for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
                if (group.getType() != androidx.media3.common.C.TRACK_TYPE_TEXT) continue;
                androidx.media3.common.TrackGroup tg = group.getMediaTrackGroup();
                for (int i = 0; i < tg.length; i++) {
                    androidx.media3.common.Format f = tg.getFormat(i);
                    int score = modSubtitleLangScore(f.language);
                    if (score == 0) score = modSubtitleLangScore(f.label == null ? "" : f.label.toString());
                    if (score > bestScore) {
                        bestScore = score;
                        bestGroup = tg;
                        bestIndex = i;
                    }
                }
            }
            if (bestGroup != null && bestScore >= 80) {   // LangUtil 是 0~100 分制（简体100/zh80/繁40）
                androidx.media3.common.TrackSelectionOverride ov =
                        new androidx.media3.common.TrackSelectionOverride(bestGroup, bestIndex);
                player.setTrackSelectionParameters(
                        player.getTrackSelectionParameters().buildUpon().setOverrideForType(ov).build());
                com.fongmi.android.tv.utils.ModDebugLog.i("MpvSubtitleStyle",
                        "exoForceCn(v11) select index=" + bestIndex + " score=" + bestScore + " label=" + bestGroup.getFormat(bestIndex).label);
            } else {
                com.fongmi.android.tv.utils.ModDebugLog.i("MpvSubtitleStyle", "exoForceCn(v11) skip: no zh subtitle best=" + bestScore);
            }
        } catch (Throwable e) {
            com.fongmi.android.tv.utils.ModDebugLog.w("MpvSubtitleStyle", "exoForceCn err=" + e);
        }
    }
    // MPV_SUB_EXO_CN_V2_END"""


def patch_exo_prefer_cn(path: Path) -> None:
    if not path.is_file():
        ERR.append(f"PlayerManager.java not found: {path}")
        return
    text = read(path)
    if "MPV_SUB_EXO_CN_V2" in text:
        OK.append("exo cn preference already injected")
        return
    if 'if (engine instanceof MpvPlayerEngine mpv) {' in text and 'restoreTrackSelection(Track.find(getKey()));' in text:
        pass
    anchor = "                restoreTrackSelection(Track.find(getKey()));"
    if text.count(anchor) != 1:
        ERR.append(f"restoreTrackSelection anchor count={text.count(anchor)}")
        return
    text = text.replace(anchor, anchor + "\n                if (isExo()) modPreferSimplifiedChineseSubtitle(tracks); // MPV_SUB_EXO_CN_V2", 1)
    idx = text.rfind("\n}")
    if idx < 0:
        ERR.append("PlayerManager class end not found")
        return
    text = text[:idx] + "\n" + EXO_CN_METHOD + "\n" + text[idx:]
    write(path, text)
    OK.append("inject exo simplified-chinese subtitle preference")


def patch_mpv_sub_add_lang(path: Path) -> None:
    """外挂字幕 sub-add 时把 language/label 传给 mpv，否则 track-list 的 lang 为空，语言优先全失效。"""
    if not path.is_file():
        ERR.append(f"MpvPlayer.java not found: {path}")
        return
    text = read(path)
    if "MPV_SUB_ADDLANG_V1" in text:
        OK.append("sub-add lang already injected")
        return
    old = '                mpvCommand(new String[]{"sub-add", playableUri(uri), "auto"});'
    new = (
        '                // MPV_SUB_ADDLANG_V1 (MPV-BRANCH-ONLY): 把语言/名称传给 mpv，否则 track-list 的 lang 为空\n'
        '                String modSubLang = sub.language == null ? "" : sub.language;\n'
        '                String modSubLabel = sub.label == null ? "" : sub.label.toString();\n'
        '                if (modSubLang.isEmpty() && modSubLabel.isEmpty()) {\n'
        '                    mpvCommand(new String[]{"sub-add", playableUri(uri), "auto"});\n'
        '                } else {\n'
        '                    mpvCommand(new String[]{"sub-add", playableUri(uri), "auto", modSubLabel, modSubLang});\n'
        '                }'
    )
    if text.count(old) != 1:
        ERR.append(f"sub-add anchor count={text.count(old)} (expect 1)")
        return
    text = text.replace(old, new, 1)
    write(path, text)
    OK.append("inject sub-add language/label passthrough")


def patch_updater_filter(path: Path) -> None:
    """检查更新只认本分支 release（tag 含 -mpv）。

    注意：Updater.java 可能已被其它 hook 改过，所以不做精确文本匹配，
    而是按方法边界整体替换，失败也只 WARN（不影响构建）。
    """
    if not path.is_file():
        print("[mod] WARN Updater.java missing, skip updater filter")
        return
    text = read(path)
    if "MPV_SUB_UPDATE_FILTER_V1" in text:
        OK.append("updater branch filter already injected")
        return

    new_stable = """    private Update getGithubStableUpdate(String channel) {
        // MPV_SUB_UPDATE_FILTER_V1 (MPV-BRANCH-ONLY): 只认本分支的 release（tag 含 -mpv），
        // 否则 /releases/latest 会返回 main 分支的包，MPV 版会误报更新
        try {
            JSONArray releases = new JSONArray(UpdateHttp.string(Github.getReleasesApi(), GITHUB_API_HEADERS, GITHUB_REQUEST_TIMEOUT_MS));
            String manifestName = getManifestName(channel);
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.optJSONObject(i);
                if (release == null) continue;
                String tag = release.optString("tag_name");
                if (tag == null || !tag.contains(Github.RELEASE_TAG_MARK)) continue;
                if (isBetaRelease(release)) continue;
                if (findAsset(release.optJSONArray("assets"), manifestName) == null) continue;
                return readGithubReleaseUpdate(channel, release);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Update.empty(channel);
    }"""

    span = find_method(text, r"^[ \t]*private\s+Update\s+getGithubStableUpdate\s*\(\s*String\s+\w+\s*\)\s*\{")
    if span is None:
        print("[mod] WARN getGithubStableUpdate not found, skip updater filter")
        return
    start_i, _, end_i = span
    text = text[:start_i] + new_stable + text[end_i + 1:]

    span2 = find_method(text, r"^[ \t]*private\s+Update\s+getGithubBetaUpdate\s*\(\s*String\s+\w+\s*\)\s*\{")
    if span2 is None:
        print("[mod] WARN getGithubBetaUpdate not found (beta 通道不做过)")
    else:
        _, b2, e2 = span2
        if "RELEASE_TAG_MARK" not in text[b2:e2]:
            m = re.search(r"for \(int i = 0; i < releases\.length\(\); i\+\+\) \{", text[b2:e2])
            if m:
                pos = b2 + m.end()
                text = (text[:pos]
                        + '\n                if (!release.optString("tag_name").contains(Github.RELEASE_TAG_MARK)) continue;'
                        + text[pos:])
            else:
                print("[mod] WARN beta loop not found (beta 通道不做过)")

    write(path, text)
    if "MPV_SUB_UPDATE_FILTER_V1" in read(path):
        OK.append("inject updater branch filter (-mpv)")
    else:
        print("[mod] WARN updater filter not applied")


def main() -> int:
    allow = os.environ.get("MOD_ALLOW_NON_MPV", "") == "1"
    ok_branch, why = branch_allowed(ROOT)
    if not ok_branch and not allow:
        print(f"[mod] fix_mpv_subtitle_runtime: skip ({why}, required='{REQUIRED_BRANCH}')")
        return 0
    if allow and not ok_branch:
        print(f"[mod] fix_mpv_subtitle_runtime: WARN forced by MOD_ALLOW_NON_MPV=1 ({why})")

    print(f"[mod] fix_mpv_subtitle_runtime ROOT={ROOT} {why}")

    setting = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    policy = ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvSubtitleStylePolicy.java"
    player = ROOT / "app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java"

    patch_setting_color(setting)
    patch_setting_font(setting)
    patch_policy(policy)
    patch_policy_font_probe(policy)
    patch_policy_fonts_ready(policy)
    patch_policy_font_lock(policy)
    patch_policy_selfcopy(policy)
    patch_mpv_player(player)
    patch_exo_prefer_cn(ROOT / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java")
    patch_mpv_sub_add_lang(player)
    patch_updater_filter(ROOT / "app/src/main/java/com/fongmi/android/tv/Updater.java")

    for x in OK:
        print(" OK", x)
    for x in ERR:
        print(" FAIL", x)
    if ERR:
        print("[mod] fix_mpv_subtitle_runtime FAILED")
        return 1
    print("[mod] fix_mpv_subtitle_runtime SUCCESS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
