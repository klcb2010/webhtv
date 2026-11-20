WebHome 修复说明 2026-10-24 20:20

1) 手机 HTML 不加载：日志已证实主文档 net::ERR_FAILED。
   若开启「算法助手 / Hook」会导致 WebView 主文档失败，请关闭 Hook 再测。
   本包仍保留 OkHttp 主文档回退（无例行调试日志），作为兜底。

2) csp_Builtin：补空壳类，避免回退原生时 ClassNotFound。

3) TV 搜索定焦：以 HTML 为主。请用 artifacts/YouTube_2.1.22-tv-focus.html
   覆盖 GitHub Pages 上的 YouTube_2.1.22.html 后强刷/清缓存。
