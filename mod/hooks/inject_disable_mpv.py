#!/usr/bin/env python3
"""
全局禁用 MPV（针对 fish2018/webhtv 源码结构）。

用法（android-release 中 apply.sh 之后）:
  python3 mod/hooks/inject_disable_mpv.py .

常量（鱼壳）: EXO=0, IJK=1, MPV=2（无 SYSTEM / KERNEL_ORDER）
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
ERRORS: list[str] = []
OK: list[str] = []
SKIP: list[str] = []


def rel(p: Path) -> str:
    try:
        return str(p.relative_to(ROOT))
    except ValueError:
        return str(p)


def require_file(path: Path) -> str | None:
    if not path.is_file():
        ERRORS.append(f"MISSING file: {rel(path)}")
        return None
    return path.read_text(encoding="utf-8")


def save(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def replace_once(
    name: str,
    path: Path,
    text: str,
    old: str,
    new: str,
    *,
    already_markers: list[str] | None = None,
) -> str:
    markers = already_markers or []
    if any(m in text for m in markers):
        SKIP.append(f"{name}: already applied ({rel(path)})")
        return text
    if old not in text:
        ERRORS.append(
            f"{name}: pattern not found in {rel(path)}\n"
            f"  expected starts with: {old[:90]!r}..."
        )
        return text
    text2 = text.replace(old, new, 1)
    if text2 == text:
        ERRORS.append(f"{name}: replace produced no change ({rel(path)})")
        return text
    OK.append(f"{name}: OK ({rel(path)})")
    return text2


def patch_player_setting() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java"
    text = require_file(path)
    if text is None:
        return

    # Detect lineage
    if "KERNEL_ORDER" in text and "SYSTEM" in text:
        ERRORS.append(
            "PlayerSetting looks like Silent1566 fork (KERNEL_ORDER present). "
            "This script targets fish2018 (EXO/IJK/MPV only). Update script or tree."
        )
        return

    text = replace_once(
        "isPlayer",
        path,
        text,
        "public static boolean isPlayer(int player) {\n"
        "        return player == EXO || player == IJK || player == MPV;\n"
        "    }",
        "public static boolean isPlayer(int player) {\n"
        "        // MPV_DISABLED\n"
        "        return player == EXO || player == IJK;\n"
        "    }",
        already_markers=["// MPV_DISABLED\n        return player == EXO || player == IJK;"],
    )

    text = replace_once(
        "sanitizePlayer",
        path,
        text,
        "public static int sanitizePlayer(int player) {\n"
        "        return player == IJK || player == MPV ? player : EXO;\n"
        "    }",
        "public static int sanitizePlayer(int player) {\n"
        "        // MPV_DISABLED: MPV maps to EXO\n"
        "        return player == IJK ? player : EXO;\n"
        "    }",
        already_markers=["MPV_DISABLED: MPV maps to EXO"],
    )

    text = replace_once(
        "nextPlayer",
        path,
        text,
        "public static int nextPlayer(int player) {\n"
        "        return switch (sanitizePlayer(player)) {\n"
        "            case EXO -> IJK;\n"
        "            case IJK -> MPV;\n"
        "            default -> EXO;\n"
        "        };\n"
        "    }",
        "public static int nextPlayer(int player) {\n"
        "        // MPV_DISABLED: cycle EXO <-> IJK only\n"
        "        return switch (sanitizePlayer(player)) {\n"
        "            case EXO -> IJK;\n"
        "            case IJK -> EXO;\n"
        "            default -> EXO;\n"
        "        };\n"
        "    }",
        already_markers=["MPV_DISABLED: cycle EXO <-> IJK only"],
    )

    text = replace_once(
        "getPlayer",
        path,
        text,
        "public static int getPlayer() {\n"
        "        int player = Prefers.getInt(\"player\", EXO);\n"
        "        if (isPlayer(player)) return player;\n"
        "        putPlayer(EXO);\n"
        "        return EXO;\n"
        "    }",
        "public static int getPlayer() {\n"
        "        int player = Prefers.getInt(\"player\", EXO);\n"
        "        // MPV_DISABLED: migrate legacy preference\n"
        "        if (player == MPV) {\n"
        "            putPlayer(EXO);\n"
        "            return EXO;\n"
        "        }\n"
        "        if (isPlayer(player)) return player;\n"
        "        putPlayer(EXO);\n"
        "        return EXO;\n"
        "    }",
        already_markers=["MPV_DISABLED: migrate legacy preference"],
    )

    text = replace_once(
        "putPlayer",
        path,
        text,
        "public static void putPlayer(int player) {\n"
        "        Prefers.put(\"player\", sanitizePlayer(player));\n"
        "    }",
        "public static void putPlayer(int player) {\n"
        "        // MPV_DISABLED: never persist MPV\n"
        "        if (player == MPV) player = EXO;\n"
        "        Prefers.put(\"player\", sanitizePlayer(player));\n"
        "    }",
        already_markers=["MPV_DISABLED: never persist MPV"],
    )

    save(path, text)


def patch_player_manager() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java"
    text = require_file(path)
    if text is None:
        return

    # fish2018 compact buildEngine
    text = replace_once(
        "buildEngine",
        path,
        text,
        "    private PlayerEngine buildEngine(int type, int decode) {\n"
        "        return switch (type) {\n"
        "            case PlayerSetting.IJK -> new IjkPlayerEngine(decode, listener);\n"
        "            case PlayerSetting.MPV -> new MpvPlayerEngine(decode, listener, this::onMpvVideoSizeProbed);\n"
        "            default -> new ExoPlayerEngine(decode, listener);\n"
        "        };\n"
        "    }",
        "    private PlayerEngine buildEngine(int type, int decode) {\n"
        "        // MPV_DISABLED: never construct MpvPlayerEngine\n"
        "        if (type == PlayerSetting.MPV) type = PlayerSetting.EXO;\n"
        "        return switch (type) {\n"
        "            case PlayerSetting.IJK -> new IjkPlayerEngine(decode, listener);\n"
        "            case PlayerSetting.MPV -> new ExoPlayerEngine(decode, listener); // MPV_DISABLED\n"
        "            default -> new ExoPlayerEngine(decode, listener);\n"
        "        };\n"
        "    }",
        already_markers=["MPV_DISABLED: never construct MpvPlayerEngine"],
    )

    save(path, text)
    text2 = path.read_text(encoding="utf-8")
    if "new MpvPlayerEngine" in text2:
        ERRORS.append(
            "PlayerManager still contains `new MpvPlayerEngine` after patch"
        )
    else:
        OK.append("PlayerManager: no remaining new MpvPlayerEngine")


def patch_kernel_dialog() -> None:
    """Dialog lists full R.array; force only EXO/IJK labels and indices."""
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/ui/dialog/PlayerKernelDialog.java"
    text = require_file(path)
    if text is None:
        return

    if "MPV_DISABLED" in text:
        SKIP.append(f"PlayerKernelDialog: already applied ({rel(path)})")
        return

    # Replace entire class body logic with filtered kernels — exact fish2018 file is small
    old = '''    public static void show(FragmentActivity activity, int selected, Listener listener) {
        int current = PlayerSetting.sanitizePlayer(selected);
        ChoiceDialog.showSingleNoCancel(activity, R.string.player_kernel, activity.getResources().getStringArray(R.array.select_player_kernel), current, which -> notifySelected(current, which, listener));
    }

    public static void show(Fragment fragment, int selected, Listener listener) {
        int current = PlayerSetting.sanitizePlayer(selected);
        ChoiceDialog.showSingleNoCancel(fragment, R.string.player_kernel, fragment.getResources().getStringArray(R.array.select_player_kernel), current, which -> notifySelected(current, which, listener));
    }

    private static void notifySelected(int current, int selected, Listener listener) {
        int target = PlayerSetting.sanitizePlayer(selected);
        if (target != current && listener != null) listener.onSelected(target);
    }
'''
    new = '''    /** MPV_DISABLED: only EXO / IJK appear in the kernel picker. */
    private static String[] kernelsWithoutMpv(android.content.res.Resources res) {
        String[] all = res.getStringArray(R.array.select_player_kernel);
        if (all.length <= 2) return all;
        return new String[]{all[PlayerSetting.EXO], all[PlayerSetting.IJK]};
    }

    public static void show(FragmentActivity activity, int selected, Listener listener) {
        int raw = PlayerSetting.sanitizePlayer(selected);
        final int current = (raw == PlayerSetting.MPV) ? PlayerSetting.EXO : raw;
        String[] items = kernelsWithoutMpv(activity.getResources());
        int checked = current == PlayerSetting.IJK ? 1 : 0;
        ChoiceDialog.showSingleNoCancel(activity, R.string.player_kernel, items, checked, which -> {
            int target = which == 1 ? PlayerSetting.IJK : PlayerSetting.EXO;
            notifySelected(current, target, listener);
        });
    }

    public static void show(Fragment fragment, int selected, Listener listener) {
        int raw = PlayerSetting.sanitizePlayer(selected);
        final int current = (raw == PlayerSetting.MPV) ? PlayerSetting.EXO : raw;
        String[] items = kernelsWithoutMpv(fragment.getResources());
        int checked = current == PlayerSetting.IJK ? 1 : 0;
        ChoiceDialog.showSingleNoCancel(fragment, R.string.player_kernel, items, checked, which -> {
            int target = which == 1 ? PlayerSetting.IJK : PlayerSetting.EXO;
            notifySelected(current, target, listener);
        });
    }

    private static void notifySelected(int current, int selected, Listener listener) {
        int target = PlayerSetting.sanitizePlayer(selected);
        if (target == PlayerSetting.MPV) target = PlayerSetting.EXO;
        if (target != current && listener != null) listener.onSelected(target);
    }
'''
    text = replace_once("PlayerKernelDialog", path, text, old, new, already_markers=["MPV_DISABLED: only EXO / IJK"])
    save(path, text)


def patch_set_mpv_rows(path: Path) -> None:
    text = require_file(path)
    if text is None:
        return
    text = replace_once(
        f"setMpvRows({rel(path)})",
        path,
        text,
        "        boolean visible = PlayerSetting.getPlayer() == PlayerSetting.MPV;",
        "        boolean visible = false; // MPV_DISABLED",
        already_markers=["boolean visible = false; // MPV_DISABLED"],
    )
    save(path, text)


def patch_settings_ui() -> None:
    candidates = [
        ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingPlayerFragment.java",
        ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/fragment/SettingPlayerFragment.java",
        ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingPlayerActivity.java",
        ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/SettingPlayerActivity.java",
    ]
    found = 0
    for p in candidates:
        if p.is_file():
            found += 1
            patch_set_mpv_rows(p)
    if found == 0:
        ERRORS.append("No SettingPlayer Fragment/Activity found")
    else:
        OK.append(f"settings UI: processed {found} file(s)")


def verify() -> None:
    ps = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java"
    pm = ROOT / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java"
    pk = ROOT / "app/src/main/java/com/fongmi/android/tv/ui/dialog/PlayerKernelDialog.java"

    for path, markers in (
        (ps, [
            "MPV_DISABLED\n        return player == EXO || player == IJK;",
            "MPV_DISABLED: MPV maps to EXO",
            "MPV_DISABLED: cycle EXO <-> IJK only",
            "MPV_DISABLED: migrate legacy preference",
            "MPV_DISABLED: never persist MPV",
        ]),
        (pm, [
            "MPV_DISABLED: never construct MpvPlayerEngine",
            "case PlayerSetting.MPV -> new ExoPlayerEngine",
        ]),
        (pk, ["MPV_DISABLED: only EXO / IJK"]),
    ):
        if not path.is_file():
            ERRORS.append(f"verify: missing {rel(path)}")
            continue
        t = path.read_text(encoding="utf-8")
        for m in markers:
            if m not in t:
                ERRORS.append(f"verify FAIL: {rel(path)} missing {m!r}")
            else:
                OK.append(f"verify OK: {rel(path)} has marker")

    if pm.is_file() and "new MpvPlayerEngine" in pm.read_text(encoding="utf-8"):
        ERRORS.append("verify FAIL: new MpvPlayerEngine still in PlayerManager")
    elif pm.is_file():
        OK.append("verify OK: no new MpvPlayerEngine")

    ui_ok = False
    for p in ROOT.glob("app/src/**/SettingPlayer*.java"):
        if "boolean visible = false; // MPV_DISABLED" in p.read_text(encoding="utf-8"):
            ui_ok = True
            OK.append(f"verify OK: {rel(p)} setMpvRows forced false")
    if not ui_ok:
        ERRORS.append("verify FAIL: setMpvRows not forced false")


def main() -> int:
    print(f"[mod] inject_disable_mpv ROOT={ROOT}")
    if not (ROOT / "app").is_dir():
        print(f"[mod] ERROR: {ROOT}/app not found", file=sys.stderr)
        return 2

    patch_player_setting()
    patch_player_manager()
    patch_kernel_dialog()
    patch_settings_ui()
    verify()

    print("---- results ----")
    for line in OK:
        print("  OK   ", line)
    for line in SKIP:
        print("  SKIP ", line)
    for line in ERRORS:
        print("  FAIL ", line, file=sys.stderr)

    if ERRORS:
        print(f"[mod] FAILED with {len(ERRORS)} error(s)", file=sys.stderr)
        return 1
    print("[mod] inject_disable_mpv SUCCESS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
