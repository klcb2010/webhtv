Place: yiqicukai / huipianheyuan / fangzhengzhunyuan (.ttf/.otf)

MPV-BRANCH-ONLY: 内置字幕字体目录
- 字体源文件放 mod/fonts/，由 mod/apply.sh 的 sync_subtitle_fonts 同步到本目录并打进 APK assets。
- custom.ttf 为「自定义外部字体」（可选）；缺失时下拉第 5 项自动回退系统字体，不弹文件选择器。
- 运行时由 SubtitleFontManager.loadBuiltinFont() 在后台单线程静默复制到
  应用私有目录 files/subtitle_fonts/，全程无文件选择器、无 toast。
