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

    if text != original:
        write(path, text)
        if MARK_RUNTIME_LOG not in read(path):
            ERR.append("runtime log marker missing after write")


def _log_before_return(text: str, brace: int, end: int, ret_stmt: str, log_stmt: str) -> str:
    """在 (brace,end) 方法体内 ret_stmt 之前插入一行日志（只处理第一个匹配）。"""
    idx = text.find(ret_stmt, brace, end)
    if idx < 0:
        return text
    line_start = text.rfind("\n", 0, idx) + 1
    indent = re.match(r"[ \t]*", text[line_start:]).group(0)
    return text[:line_start] + indent + log_stmt + "\n" + text[line_start:]


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


def main() -> int:
    branch = git_branch(ROOT)
    allow = os.environ.get("MOD_ALLOW_NON_MPV", "") == "1"
    if branch != REQUIRED_BRANCH and not allow:
        print(f"[mod] fix_mpv_subtitle_runtime: skip (branch='{branch}', required='{REQUIRED_BRANCH}')")
        return 0
    if branch != REQUIRED_BRANCH:
        print(f"[mod] fix_mpv_subtitle_runtime: WARN branch='{branch}' forced by MOD_ALLOW_NON_MPV=1")

    print(f"[mod] fix_mpv_subtitle_runtime ROOT={ROOT} branch={branch or 'unknown'}")

    setting = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    policy = ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvSubtitleStylePolicy.java"
    player = ROOT / "app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java"

    patch_setting_color(setting)
    patch_setting_font(setting)
    patch_policy(policy)
    patch_mpv_player(player)

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
