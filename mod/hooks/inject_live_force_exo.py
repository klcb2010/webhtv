#!/usr/bin/env python3
"""Temporarily force EXO while LiveActivity is active (MPV branch only)."""
from pathlib import Path
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
PLAYER_SETTING = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java"
LIVE_FILES = (
    ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java",
    ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java",
)


def patch_player_setting():
    if not PLAYER_SETTING.is_file():
        raise SystemExit("[mod] ERROR: PlayerSetting.java not found")
    text = PLAYER_SETTING.read_text(encoding="utf-8")
    if "MOD_LIVE_FORCE_EXO_RUNTIME" in text:
        print("[mod] runtime EXO override already installed")
        return

    class_anchor = "public class PlayerSetting {"
    if class_anchor not in text:
        raise SystemExit("[mod] ERROR: PlayerSetting class declaration not found")
    text = text.replace(
        class_anchor,
        class_anchor + """

    // MOD_LIVE_FORCE_EXO_RUNTIME: in-memory only; never changes the saved player.
    private static volatile int runtimePlayerOverride = -1;
""",
        1,
    )

    get_anchor = """    public static int getPlayer() {
        int player = Prefers.getInt("player", EXO);"""
    get_replacement = """    public static int getPlayer() {
        int runtimePlayer = runtimePlayerOverride;
        if (isPlayer(runtimePlayer)) return runtimePlayer;
        int player = Prefers.getInt("player", EXO);"""
    if get_anchor not in text:
        raise SystemExit("[mod] ERROR: PlayerSetting.getPlayer anchor not found")
    text = text.replace(get_anchor, get_replacement, 1)

    put_anchor = """    public static void putPlayer(int player) {
        Prefers.put("player", sanitizePlayer(player));
    }"""
    put_replacement = put_anchor + """

    public static void setRuntimePlayerOverride(int player) {
        runtimePlayerOverride = isPlayer(player) ? sanitizePlayer(player) : -1;
    }

    public static void clearRuntimePlayerOverride() {
        runtimePlayerOverride = -1;
    }"""
    if put_anchor not in text:
        raise SystemExit("[mod] ERROR: PlayerSetting.putPlayer anchor not found")
    text = text.replace(put_anchor, put_replacement, 1)

    PLAYER_SETTING.write_text(text, encoding="utf-8")
    print("[mod] installed temporary runtime EXO override")


def patch_live_activity(path):
    if not path.is_file():
        raise SystemExit("[mod] ERROR: LiveActivity not found: " + str(path))
    text = path.read_text(encoding="utf-8")
    if "MOD_LIVE_FORCE_EXO_ACTIVITY" not in text:
        init_anchor = """    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);"""
        init_replacement = """    protected void initView(Bundle savedInstanceState) {
        applyModLiveForceExo();
        super.initView(savedInstanceState);"""
        if init_anchor not in text:
            raise SystemExit("[mod] ERROR: LiveActivity.initView anchor not found: " + str(path))
        text = text.replace(init_anchor, init_replacement, 1)

        method = """    // MOD_LIVE_FORCE_EXO_ACTIVITY: set before PlaybackActivity binds PlaybackService.
    private void applyModLiveForceExo() {
        try {
            if (com.fongmi.android.tv.setting.Setting.isLiveForceExo()) {
                PlayerSetting.setRuntimePlayerOverride(PlayerSetting.EXO);
            } else {
                PlayerSetting.clearRuntimePlayerOverride();
            }
        } catch (Throwable ignored) {
            PlayerSetting.clearRuntimePlayerOverride();
        }
    }

"""
        end = text.rfind("\n}")
        if end < 0:
            raise SystemExit("[mod] ERROR: LiveActivity class end not found: " + str(path))
        text = text[:end] + "\n" + method + text[end:]

    if "MOD_LIVE_FORCE_EXO_RELEASE" not in text:
        start = text.find("protected void onDestroy()")
        if start < 0:
            raise SystemExit("[mod] ERROR: LiveActivity.onDestroy not found: " + str(path))
        close = text.find("super.onDestroy();", start)
        if close < 0:
            raise SystemExit("[mod] ERROR: LiveActivity super.onDestroy not found: " + str(path))
        release = """super.onDestroy();
        // MOD_LIVE_FORCE_EXO_RELEASE: keep the override while the live Activity is recreated.
        if (!isChangingConfigurations()) PlayerSetting.clearRuntimePlayerOverride();"""
        text = text[:close] + release + text[close + len("super.onDestroy();"):]

    path.write_text(text, encoding="utf-8")
    print("[mod] live EXO override patched: " + str(path))


def main():
    patch_player_setting()
    for path in LIVE_FILES:
        patch_live_activity(path)
    print("[mod] live force EXO hook complete")


if __name__ == "__main__":
    main()
