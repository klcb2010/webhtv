#!/usr/bin/env python3
"""WebHome: TV focus + OkHttp main-frame recovery for net::ERR_FAILED."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER = "WEBHOME_OKHTTP_MAIN_RECOVERY"


def patch_webview_util() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/utils/WebViewUtil.java"
    if not path.is_file():
        print("[mod] WARN WebViewUtil missing")
        return
    t = path.read_text(encoding="utf-8")
    if "setNeedInitialFocus" in t and MARKER in t:
        print("[mod] WebViewUtil already")
        return
    if "setNeedInitialFocus" not in t:
        t2, n = re.subn(
            r"(public static void configureHome\(WebView webView\) \{)([\s\S]*?)(\n    \})",
            r"\1\2\n        // " + MARKER + r"""
        try { webView.getSettings().setNeedInitialFocus(true); } catch (Throwable ignored) {}
\3""",
            t,
            count=1,
        )
        if n:
            t = t2
            print("[mod] WebViewUtil NeedInitialFocus")
        else:
            print("[mod] WARN configureHome not matched")
    path.write_text(t, encoding="utf-8")


def patch_home_web_controller() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/web/HomeWebController.java"
    if not path.is_file():
        print("[mod] WARN HomeWebController missing")
        return
    t = path.read_text(encoding="utf-8")
    if MARKER in t and "recoverMainFrameViaOkHttp" in t:
        print("[mod] HomeWebController already")
        return

    # 1) focus assist (if not present)
    if "injectTvSearchFocusAssist" not in t:
        old_focus = """    private boolean focusWebView(String reason) {
        if (webView.hasFocus()) return true;
        boolean ok = webView.requestFocus();
        SpiderDebug.log("webhome-focus", "request reason=%s ok=%s visible=%s width=%s height=%s url=%s", reason, ok, isVisible(), webView.getWidth(), webView.getHeight(), webView.getUrl());
        return ok;
    }"""
        new_focus = """    private boolean focusWebView(String reason) {
        try {
            webView.setFocusable(true);
            webView.setFocusableInTouchMode(true);
        } catch (Throwable ignored) {
        }
        if (webView.hasFocus()) {
            if (Util.isLeanback()) injectTvSearchFocusAssist();
            return true;
        }
        boolean ok = webView.requestFocus();
        if (ok && Util.isLeanback()) injectTvSearchFocusAssist();
        SpiderDebug.log("webhome-focus", "request reason=%s ok=%s visible=%s width=%s height=%s url=%s", reason, ok, isVisible(), webView.getWidth(), webView.getHeight(), webView.getUrl());
        return ok;
    }

    private void injectTvSearchFocusAssist() {
        try {
            String js = "(function(){try{"
                + "var d=document;if(!d||!d.documentElement)return;"
                + "d.documentElement.classList.add('fm-tv');"
                + "var q=d.getElementById('q')||d.querySelector('input[type=search],input[type=text]');"
                + "if(q){try{q.setAttribute('tabindex','0');}catch(e){}}"
                + "try{d.querySelectorAll('button,.tab,.gear,.go,.chip').forEach(function(el){el.setAttribute('tabindex','0');});}catch(e){}"
                + "if(!window.__fmTvFocusAssist){window.__fmTvFocusAssist=1;"
                + "d.addEventListener('keydown',function(e){"
                + "var k=e.keyCode||e.which;"
                + "var ae=d.activeElement;var tag=ae&&ae.tagName?ae.tagName.toLowerCase():'';"
                + "if((k===40||k===38||k===37||k===39||k===13)&&(tag==='body'||tag==='html'||!ae||ae===d.body)){"
                + "var t=d.getElementById('q')||d.querySelector('input,button.tab,button');"
                + "if(t&&t.focus){try{t.focus();}catch(ex){}}"
                + "}"
                + "},true);}"
                + "}catch(e){}})();";
            webView.post(() -> webView.evaluateJavascript(js, null));
        } catch (Throwable ignored) {
        }
    }"""
        if old_focus in t:
            t = t.replace(old_focus, new_focus, 1)
            print("[mod] TV focus assist")
        else:
            print("[mod] WARN focusWebView not matched")

    # 2) Replace onReceivedError main-frame handling to try OkHttp recovery
    old_err = """            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                SpiderDebug.log("webhome-webview", "resource error main=%s code=%s desc=%s url=%s", request.isForMainFrame(), error.getErrorCode(), error.getDescription(), request.getUrl());
                listener.onWebConsole("ERROR " + error.getErrorCode() + " " + error.getDescription() + " " + request.getUrl());
                if (request.isForMainFrame()) {
                    loadToken++;
                    homePage = null;
                    rawAdapter = null;
                    Notify.show(error.getDescription().toString());
                    listener.onWebError();
                }
            }"""

    new_err = """            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                SpiderDebug.log("webhome-webview", "resource error main=%s code=%s desc=%s url=%s", request.isForMainFrame(), error.getErrorCode(), error.getDescription(), request.getUrl());
                listener.onWebConsole("ERROR " + error.getErrorCode() + " " + error.getDescription() + " " + request.getUrl());
                if (request.isForMainFrame()) {
                    // """ + MARKER + """: 不立即放弃，先走 OkHttp 拉主文档
                    String failUrl = request.getUrl() != null ? request.getUrl().toString() : homePage;
                    if (recoverMainFrameViaOkHttp(failUrl)) return;
                    loadToken++;
                    homePage = null;
                    rawAdapter = null;
                    Notify.show(error.getDescription().toString());
                    listener.onWebError();
                }
            }"""

    if old_err in t:
        t = t.replace(old_err, new_err, 1)
        print("[mod] onReceivedError recovery hook")
    elif "private boolean recoverMainFrameViaOkHttp" not in t:
        print("[mod] WARN onReceivedError block not exact")
        # try softer match
        t2, n = re.subn(
            r"if \(request\.isForMainFrame\(\)\) \{\s*loadToken\+\+;\s*homePage = null;\s*rawAdapter = null;\s*Notify\.show\(error\.getDescription\(\)\.toString\(\)\);\s*listener\.onWebError\(\);\s*\}",
            """if (request.isForMainFrame()) {
                    String failUrl = request.getUrl() != null ? request.getUrl().toString() : homePage;
                    if (recoverMainFrameViaOkHttp(failUrl)) return;
                    loadToken++;
                    homePage = null;
                    rawAdapter = null;
                    Notify.show(error.getDescription().toString());
                    listener.onWebError();
                }""",
            t,
            count=1,
        )
        if n:
            t = t2
            print("[mod] onReceivedError soft replace")
        else:
            print("[mod] FAIL onReceivedError")

    # 3) Add recoverMainFrameViaOkHttp method before focusWebView or after loadUrl
    if "private boolean recoverMainFrameViaOkHttp" not in t:
        method = """
    private int mainRecoverAttempts = 0;

    /** """ + MARKER + """：WebView 主文档 net::ERR_FAILED 时用 App OkHttp 拉取并 loadDataWithBaseURL */
    private boolean recoverMainFrameViaOkHttp(String url) {
        if (TextUtils.isEmpty(url) || mainRecoverAttempts >= 2) return false;
        if (isEmptyDocumentUrl(url)) url = homePage;
        if (TextUtils.isEmpty(url)) return false;
        mainRecoverAttempts++;
        final String target = url;
        final int token = loadToken;
        new Thread(() -> {
            try {
                Map<String, String> headers = site == null ? Collections.emptyMap() : site.getHeader();
                okhttp3.Request.Builder b = new okhttp3.Request.Builder().url(target).get();
                if (headers != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        if (e.getKey() != null && e.getValue() != null) b.header(e.getKey(), e.getValue());
                    }
                }
                String ua = header(headers, "User-Agent");
                if (!TextUtils.isEmpty(ua)) b.header("User-Agent", ua);
                else b.header("User-Agent", webView.getSettings().getUserAgentString());
                okhttp3.Response resp = com.github.catvod.net.OkHttp.client().newCall(b.build()).execute();
                okhttp3.ResponseBody body = resp.body();
                if (!resp.isSuccessful() || body == null) {
                    // recovery fail silent
                    return;
                }
                final String html = body.string();
                final String base = target;
                App.post(() -> {
                    if (token != loadToken || activity.isFinishing()) return;
                    try {
                        // recovery ok silent
                        webView.loadDataWithBaseURL(base, html, "text/html", "utf-8", base);
                        homePage = base;
                        show();
                    } catch (Throwable e) {
                        // recovery apply silent
                    }
                });
            } catch (Throwable e) {
                // recovery exception silent
            }
        }).start();
        return true;
    }

"""
        # insert before private boolean focusWebView
        if "private boolean focusWebView" in t:
            t = t.replace("private boolean focusWebView", method + "    private boolean focusWebView", 1)
            print("[mod] recoverMainFrameViaOkHttp added")
        else:
            print("[mod] FAIL insert recovery method")

    # reset recover attempts on successful load start
    if "mainRecoverAttempts = 0" not in t.split("loadUrl(String url)")[1][:400] if "loadUrl(String url)" in t else True:
        t = t.replace(
            "private void loadUrl(String url) {",
            "private void loadUrl(String url) {\n        mainRecoverAttempts = 0;",
            1,
        )

    # ensure App import
    if "import com.fongmi.android.tv.App;" not in t:
        t = t.replace("package com.fongmi.android.tv.web;", "package com.fongmi.android.tv.web;\n\nimport com.fongmi.android.tv.App;", 1)

    path.write_text(t, encoding="utf-8")
    # brace check
    if t.count("{") != t.count("}"):
        print("[mod] FAIL brace mismatch", t.count("{"), t.count("}"))
    else:
        print("[mod] HomeWebController braces ok")


def main() -> None:
    print("[mod] inject_webhome_fix ROOT", ROOT)
    patch_webview_util()
    patch_home_web_controller()
    print("[mod] inject_webhome_fix done")


if __name__ == "__main__":
    main()
