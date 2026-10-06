#!/usr/bin/env python3
"""Port llb0 WebView live: Source + LiveActivity (mobile/leanback)."""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")

def patch_source(path: pathlib.Path) -> None:
    if not path.exists():
        print("[mod] Source.java missing")
        return
    t = path.read_text(encoding="utf-8")
    if "WebViewResolver" in t:
        print("[mod] Source already has WebViewResolver")
        return
    if "import com.fongmi.android.tv.player.extractor.Youtube;" in t:
        t = t.replace(
            "import com.fongmi.android.tv.player.extractor.Youtube;",
            "import com.fongmi.android.tv.player.extractor.Youtube;\nimport com.fongmi.android.tv.player.extractor.WebViewResolver;",
            1,
        )
    elif "import com.fongmi.android.tv.player.extractor.Video;" in t:
        t = t.replace(
            "import com.fongmi.android.tv.player.extractor.Video;",
            "import com.fongmi.android.tv.player.extractor.Video;\nimport com.fongmi.android.tv.player.extractor.WebViewResolver;",
            1,
        )
    else:
        print("[mod] WARN Source import site missing")
        return
    m = re.search(r"(extractors\.add\(new Youtube\(\)\);\s*)", t)
    if m:
        t = t.replace(m.group(1), m.group(1) + "        extractors.add(new WebViewResolver());\n", 1)
    else:
        m = re.search(r"(extractors\.add\(new Video\(\)\);\s*)", t)
        if m:
            t = t.replace(m.group(1), m.group(1) + "        extractors.add(new WebViewResolver());\n", 1)
        else:
            print("[mod] WARN Source add site missing")
            return
    path.write_text(t, encoding="utf-8")
    print("[mod] Source WebViewResolver registered")

LIVE_METHODS_MOBILE = r'''
    private boolean isWebViewChannel() {
        try {
            if (!com.fongmi.android.tv.setting.Setting.isWebViewLiveEnabled()) return false;
        } catch (Throwable ignored) {
            return false;
        }
        return mChannel != null && mChannel.getCurrent() != null && mChannel.getCurrent().startsWith("webview://");
    }

    private void startWebView(String url) {
        try {
            if (url != null && url.startsWith("webview://")) url = url.substring("webview://".length()); // normalize webview prefix
            if (isFinishing() || isDestroyed()) return;
            if (service() != null) {
                try { player().stop(); } catch (Throwable ignored) {}
                try { player().clear(); } catch (Throwable ignored) {}
            }
            try { hideProgress(); } catch (Throwable ignored) {}
            try { hideUI(); } catch (Throwable ignored) {}
            try { if (mBinding.exo != null) mBinding.exo.setVisibility(android.view.View.GONE); } catch (Throwable ignored) {}
            if (mWebViewPlayer == null) mWebViewPlayer = new com.fongmi.android.tv.ui.custom.WebViewPlayer();
            android.view.View.OnTouchListener webTouchListener = (v, e) -> {
                try { mKeyDown.onTouchEvent(e); } catch (Throwable ignored) {}
                return true;
            };
            mWebViewPlayer.attach(this, mBinding.video, url, webTouchListener, playing -> {
                try {
                    mBinding.control.play.setImageResource(playing
                            ? androidx.media3.ui.R.drawable.exo_icon_pause
                            : androidx.media3.ui.R.drawable.exo_icon_play);
                } catch (Throwable ignored) {}
            });
            bringWebViewOverlaysToFront();
            try {
                if (com.fongmi.android.tv.setting.Setting.isLiveDefaultFullscreen()) {
                    try { enterFullscreenLive(); } catch (Throwable t1) {
                        try { onRotate(); } catch (Throwable t2) {
                            try { hideUI(); } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
            try { bringWebViewOverlaysToFront(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        }
    }

    private void bringWebViewOverlaysToFront() {
        try {
            // WebView must stay under these; otherwise brightness/volume bars and channel list are invisible
            if (mBinding.widget != null) mBinding.widget.getRoot().bringToFront();
            if (mBinding.control != null) mBinding.control.getRoot().bringToFront();
            if (mBinding.progress != null) mBinding.progress.getRoot().bringToFront();
            try { if (mBinding.osd != null) mBinding.osd.getRoot().bringToFront(); } catch (Throwable ignored) {}
            try { if (mBinding.recycler != null) mBinding.recycler.bringToFront(); } catch (Throwable ignored) {}
            try {
                if (mBinding.video != null) mBinding.video.requestLayout();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        }
    }
'''

LIVE_METHODS_TV = r'''
    private boolean isWebViewChannel() {
        try {
            if (!com.fongmi.android.tv.setting.Setting.isWebViewLiveEnabled()) return false;
        } catch (Throwable ignored) {
            return false;
        }
        return mChannel != null && mChannel.getCurrent() != null && mChannel.getCurrent().startsWith("webview://");
    }

    private void startWebView(String url) {
        try {
            if (url != null && url.startsWith("webview://")) url = url.substring("webview://".length()); // normalize webview prefix
            if (player() != null) {
                try { player().stop(); } catch (Throwable ignored) {}
                try { player().clear(); } catch (Throwable ignored) {}
            }
            try { hideProgress(); } catch (Throwable ignored) {}
            try { hideUI(); } catch (Throwable ignored) {}
            try { if (mBinding.exo != null) mBinding.exo.setVisibility(android.view.View.GONE); } catch (Throwable ignored) {}
            if (mWebViewPlayer == null) mWebViewPlayer = new com.fongmi.android.tv.ui.custom.WebViewPlayer();
            mWebViewPlayer.attach(this, mBinding.video, url);
            bringWebViewOverlaysToFront();
            try {
                if (com.fongmi.android.tv.setting.Setting.isLiveDefaultFullscreen()) {
                    try { hideUI(); } catch (Throwable ignored) {}
                    try { com.fongmi.android.tv.utils.Util.hideSystemUI(this); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        }
    }

    private void bringWebViewOverlaysToFront() {
        try {
            if (mBinding.widget != null) mBinding.widget.getRoot().bringToFront();
            if (mBinding.control != null) mBinding.control.getRoot().bringToFront();
            if (mBinding.progress != null) mBinding.progress.getRoot().bringToFront();
            try { if (mBinding.osd != null) mBinding.osd.getRoot().bringToFront(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        }
    }
'''

def patch_live(path: pathlib.Path, mobile: bool) -> None:
    if not path.exists():
        print("[mod] LiveActivity missing", path)
        return
    t = path.read_text(encoding="utf-8")
    if "isWebViewChannel" in t and "WebViewPlayer" in t:
        print("[mod] LiveActivity webview already", path)
        return

    if "import com.fongmi.android.tv.ui.custom.WebViewPlayer;" not in t:
        t = t.replace(
            "package com.fongmi.android.tv.ui.activity;",
            "package com.fongmi.android.tv.ui.activity;\n\nimport com.fongmi.android.tv.ui.custom.WebViewPlayer;",
            1,
        )

    if "mWebViewPlayer" not in t:
        m = re.search(r"(public class LiveActivity[^{]*\{)", t)
        if m:
            t = t[:m.end()] + "\n\n    private WebViewPlayer mWebViewPlayer;\n" + t[m.end():]

    if "mWebViewPlayer = new WebViewPlayer" not in t and "new com.fongmi.android.tv.ui.custom.WebViewPlayer" not in t:
        if "checkLive();" in t:
            t = t.replace(
                "checkLive();",
                "checkLive();\n        try { if (mWebViewPlayer == null) mWebViewPlayer = new WebViewPlayer(); } catch (Throwable ignored) {}\n",
                1,
            )
        elif "setVideoView();" in t:
            t = t.replace(
                "setVideoView();",
                "setVideoView();\n        try { if (mWebViewPlayer == null) mWebViewPlayer = new WebViewPlayer(); } catch (Throwable ignored) {}\n",
                1,
            )

    if "startWebView(realUrl)" not in t and "startWebView(result.getRealUrl())" not in t:
        injected = False
        t2, n = re.subn(
            r"(mPlaybackKey\s*=\s*realUrl\s*;\s*\n(?:\s*updateNavigationKey\(\);\s*\n)?)",
            r"\1        if (isWebViewChannel()) {\n            startWebView(realUrl);\n            return;\n        }\n        try { if (mWebViewPlayer != null) mWebViewPlayer.detach(); } catch (Throwable ignored) {}\n",
            t,
            count=1,
        )
        if n:
            t = t2
            injected = True
            print("[mod] injected start() branch", path)
        # Newer PlaybackService path: startPlayback(Result...)
        if not injected and "startPlayback" in t:
            t3, n3 = re.subn(
                r"(public void startPlayback\s*\(\s*Result\s+(\w+)\s*,\s*long\s+\w+\s*,\s*MediaMetadata\s+\w+\s*\)\s*\{)",
                r"\1\n        if (isWebViewChannel()) {\n            try { mPlaybackKey = \2.getRealUrl(); } catch (Throwable ignored) {}\n            startWebView(\2.getRealUrl());\n            return;\n        }\n        try { if (mWebViewPlayer != null) mWebViewPlayer.detach(); } catch (Throwable ignored) {}\n",
                t,
                count=1,
            )
            if n3:
                t = t3
                injected = True
                print("[mod] injected startPlayback() branch", path)
        if not injected:
            m = re.search(r"(private void start\s*\(\s*Result\s+\w+\s*\)\s*\{)|(public void startPlayback\s*\()", t)
            if m:
                # find startPlayer after match
                start = m.start()
                region = t[start:start+3500]
                sp = region.find("startPlayer(")
                if sp > 0:
                    abs_sp = start + sp
                    line_start = t.rfind("\n", 0, abs_sp) + 1
                    indent = re.match(r"[ \t]*", t[line_start:]).group(0)
                    # prefer result.getRealUrl() if startPlayback
                    url_expr = "result.getRealUrl()" if "startPlayback" in t[start:start+80] else "realUrl"
                    inject = (
                        f"{indent}if (isWebViewChannel()) {{\n"
                        f"{indent}    startWebView({url_expr});\n"
                        f"{indent}    return;\n"
                        f"{indent}}}\n"
                        f"{indent}try {{ if (mWebViewPlayer != null) mWebViewPlayer.detach(); }} catch (Throwable ignored) {{}}\n"
                    )
                    t = t[:line_start] + inject + t[line_start:]
                    print("[mod] injected startPlayer fallback", path)
                else:
                    print("[mod] WARN no startPlayer near start methods", path)
            else:
                print("[mod] WARN no start/startPlayback method", path)

    if "mWebViewPlayer.onResume" not in t:
        t = re.sub(
            r"(protected void onResume\(\)\s*\{[\s\S]*?super\.onResume\(\);)",
            r"\1\n        try { if (mWebViewPlayer != null) mWebViewPlayer.onResume(); } catch (Throwable ignored) {}",
            t,
            count=1,
        )
    if "mWebViewPlayer.onPause" not in t:
        t = re.sub(
            r"(protected void onPause\(\)\s*\{)",
            r"\1\n        try { if (mWebViewPlayer != null) mWebViewPlayer.onPause(); } catch (Throwable ignored) {}",
            t,
            count=1,
        )
    if t.count("mWebViewPlayer.detach") < 1:
        t = re.sub(
            r"(protected void onDestroy\(\)\s*\{)",
            r"\1\n        try { if (mWebViewPlayer != null) mWebViewPlayer.detach(); } catch (Throwable ignored) {}",
            t,
            count=1,
        )

    if mobile and "private void checkPlay" in t and "mWebViewPlayer.isWebPlaying" not in t:
        t = re.sub(
            r"(private void checkPlay\(\)\s*\{)",
            r"""\1
        if (isWebViewChannel()) {
            try {
                if (mWebViewPlayer != null && mWebViewPlayer.isWebPlaying()) {
                    mWebViewPlayer.pause();
                } else if (mWebViewPlayer != null) {
                    mWebViewPlayer.play();
                }
            } catch (Throwable ignored) {}
            return;
        }
""",
            t,
            count=1,
        )

    if "private void startWebView(" not in t:
        methods = LIVE_METHODS_MOBILE if mobile else LIVE_METHODS_TV
        idx = t.rfind("\n}")
        t = t[:idx] + "\n" + methods + t[idx:]

    path.write_text(t, encoding="utf-8")
    print("[mod] LiveActivity webview patched mobile=%s braces=%s" % (mobile, t.count("{") - t.count("}")))


def patch_info_and_size(path: pathlib.Path, mobile: bool) -> None:
    if not path.exists():
        return
    t = path.read_text(encoding="utf-8")
    orig = t
    old = "mBinding.control.info.setVisibility(player().isEmpty() ? View.GONE : View.VISIBLE);"
    new = "mBinding.control.info.setVisibility((player().isEmpty() && !isWebViewChannel()) ? View.GONE : View.VISIBLE);"
    if old in t and "isWebViewChannel()) ? View.GONE" not in t:
        t = t.replace(old, new, 1)
        print("[mod] webview info btn", path)
    if "mWebViewPlayer.getSizeText()" not in t and "private void setSizeText()" in t:
        t2, n = re.subn(
            r"(private void setSizeText\(\)\s*\{)\s*String text = service\(\) == null \? \"\" : player\(\)\.getSizeText\(\);",
            r"""\1
        String text = "";
        try {
            if (isWebViewChannel() && mWebViewPlayer != null) text = mWebViewPlayer.getSizeText();
            else if (service() != null) text = player().getSizeText();
        } catch (Throwable ignored) {
            try { if (service() != null) text = player().getSizeText(); } catch (Throwable ignored2) {}
        }
        if (text == null) text = "";""",
            t,
            count=1,
        )
        if n:
            t = t2
            print("[mod] webview setSizeText", path)
        else:
            print("[mod] WARN setSizeText pattern", path)
    old3 = "InfoDialog.create().title(mBinding.control.title.getText()).headers(player().getHeaders()).url(player().getUrl()).show(this);"
    new3 = "InfoDialog.create().title(mBinding.control.title.getText()).headers(isWebViewChannel() ? java.util.Collections.emptyMap() : player().getHeaders()).url(isWebViewChannel() && mChannel != null ? mChannel.getCurrent() : player().getUrl()).show(this);"
    if old3 in t and "isWebViewChannel() && mChannel" not in t:
        t = t.replace(old3, new3, 1)
        print("[mod] webview onInfo", path)
    if t != orig:
        path.write_text(t, encoding="utf-8")


patch_source(ROOT / "app/src/main/java/com/fongmi/android/tv/player/Source.java")
patch_live(ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java", True)
patch_live(ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java", False)
patch_info_and_size(ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java", True)
patch_info_and_size(ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java", False)
print("[mod] inject_webview_live done")
