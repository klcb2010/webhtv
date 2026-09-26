#!/usr/bin/env python3
"""Buffering 过久 / 未识别类备注时，在开启「自动换源」时触发 startFlow，避免无限转圈。"""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")

HOOK = r"""
    // ---- mod: stalled playback auto-change ----
    private int mStallWatchGen;
    private static final long STALL_AUTO_CHANGE_MS = 15000L;
    private static final long STALL_FAST_MS = 8000L;

    private void cancelStallWatch() {
        mStallWatchGen++;
    }

    private void armStallWatch() {
        armStallWatch(STALL_AUTO_CHANGE_MS);
    }

    private void armStallWatch(long delayMs) {
        final int gen = ++mStallWatchGen;
        com.fongmi.android.tv.App.post(() -> {
            if (gen != mStallWatchGen || isFinishing()) return;
            try {
                if (!com.fongmi.android.tv.setting.PlayerSetting.isAutoChange()) return;
            } catch (Throwable e) {
                return;
            }
            try {
                if (player() != null) {
                    if (player().isPlaying()) return;
                    long pos = player().getPosition();
                    if (pos > 1500) return;
                }
            } catch (Throwable ignored) {
            }
            try {
                startFlow();
            } catch (Throwable ignored) {
            }
        }, delayMs);
    }

    private void maybeArmFastStallFromRemark(String remark) {
        if (remark == null || remark.isEmpty()) return;
        String r = remark;
        if (r.contains("未识别") || r.contains("已失效") || r.contains("链接失效")
                || r.contains("提取码") || r.contains("已过期") || r.contains("无法播放")) {
            armStallWatch(STALL_FAST_MS);
        }
    }
"""


def inject(path: pathlib.Path):
    if not path.exists():
        print("[mod] skip", path)
        return
    t = path.read_text(encoding="utf-8")
    if "armStallWatch" in t:
        # re-inject clean: strip old block
        t = re.sub(
            r"\n[ \t]*// ---- mod: stalled playback auto-change ----[\s\S]*?private void maybeArmFastStallFromRemark[\s\S]*?\n[ \t]*\}\n",
            "\n",
            t,
        )

    # append methods before last class closing brace
    if t.rstrip().endswith("}"):
        t = t.rstrip()[:-1] + HOOK + "\n}\n"

    # on STATE_BUFFERING arm; on READY/ENDED cancel
    def patch_state(m):
        body = m.group(0)
        if "armStallWatch" in body:
            return body
        body = body.replace(
            "case Player.STATE_BUFFERING:\n                showProgress();\n                break;",
            "case Player.STATE_BUFFERING:\n                showProgress();\n                armStallWatch();\n                break;",
        )
        body = body.replace(
            "case Player.STATE_READY:\n",
            "case Player.STATE_READY:\n                cancelStallWatch();\n",
        )
        return body

    t2, n = re.subn(
        r"protected void onStateChanged\(int state\) \{[\s\S]*?\n    \}",
        patch_state,
        t,
        count=1,
    )
    if n == 0:
        print("[mod] WARN onStateChanged not found", path)
    else:
        t = t2

    # onError cancel + already has startFlow upstream
    t = t.replace(
        "protected void onError(String msg) {\n        recordPlayHealth(false, msg);",
        "protected void onError(String msg) {\n        cancelStallWatch();\n        recordPlayHealth(false, msg);",
    )
    if "cancelStallWatch();\n        recordPlayHealth" not in t:
        t = t.replace(
            "protected void onError(String msg) {\n",
            "protected void onError(String msg) {\n        cancelStallWatch();\n",
            1,
        )

    # remark path: setText(mBinding.remark...
    if "maybeArmFastStallFromRemark" not in t.split("setText(mBinding.remark")[0] if "setText(mBinding.remark" in t else t:
        t = t.replace(
            "setText(mBinding.remark, 0, item.getRemarks());",
            "setText(mBinding.remark, 0, item.getRemarks());\n        try { maybeArmFastStallFromRemark(item.getRemarks()); } catch (Throwable ignored) {}",
        )

    path.write_text(t, encoding="utf-8")
    print("[mod] stalled-auto-change", path, "braces", t.count("{") == t.count("}"))


for rel in [
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
]:
    inject(ROOT / rel)
